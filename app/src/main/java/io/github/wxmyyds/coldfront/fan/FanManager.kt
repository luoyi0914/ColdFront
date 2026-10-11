package io.github.wxmyyds.coldfront.fan

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.wxmyyds.coldfront.ble.BackgroundLinkLoss
import io.github.wxmyyds.coldfront.ble.BleScanDiagnostic
import io.github.wxmyyds.coldfront.ble.CoolerTransport
import io.github.wxmyyds.coldfront.ble.ScanState
import io.github.wxmyyds.coldfront.data.SettingsRepository
import io.github.wxmyyds.coldfront.domain.CoolerBleConstants
import io.github.wxmyyds.coldfront.domain.CoolerCapabilities
import io.github.wxmyyds.coldfront.domain.CoolerDevice
import io.github.wxmyyds.coldfront.domain.CoolerDeviceType
import io.github.wxmyyds.coldfront.domain.CoolerLiveState
import io.github.wxmyyds.coldfront.domain.ConnectionState
import io.github.wxmyyds.coldfront.domain.FanMode
import io.github.wxmyyds.coldfront.domain.RGBConfig
import io.github.wxmyyds.coldfront.domain.RgbWriteState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * K90 Ultra / K90 Max 内置风扇控制器。
 *
 * 实现 [CoolerTransport]，因此 ColdFront 的 UI / Service / Tile 层只需少量适配，
 * 底下从红魔散热器的 BLE GATT 换成 root sysfs。
 *
 * ## 接管时机（本次行为变更）
 * 上游实现（以及本 fork 的旧版）在**构造时**就连接并执行接管写（
 * `settings put system cooling_fan_enable 0` + `target_level 1`）——启动即改变系统风扇策略。
 * 现在拆成两步：
 *
 * 1. [activate] 只做**只读**挂载：探测 Root、确认风扇节点可读，随后开始轮询遥测。
 *    不写任何节点，因此打开应用看到的是真实状态，而不是「已经被本应用改过」的状态。
 * 2. **接管写只在用户真正操作风扇时发生**（[setFanSpeed]/[setCooling]/[setSmart]/[setBoost]）。
 *    第一次控制前先夺回控制权，之后不再重复写。
 *
 * ## 状态一致性
 * [controlMode] 是唯一真源，[publishControlState] 由它推导 `coolingOn`/`smartOn`/`boostOn`/`fanMode`。
 * 轮询只更新遥测（温度/转速/占空比），绝不改写控制开关。
 *
 * ## 写入的诚实性
 * 每个控制写都经过 [FanNodes.writeInt] 的退出码判定：写失败会置
 * [CoolerLiveState.controlError]，不会把失败当成功显示。温度读不到时也不再把 0°C
 * 喂给曲线（旧实现会因此把转速压到最低）。
 */
class FanManager(private val context: Context) : CoolerTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(CoolerLiveState())
    override val state: StateFlow<CoolerLiveState> = _state.asStateFlow()

    private val _rgbWriteState = MutableStateFlow<RgbWriteState?>(null)
    override val rgbWriteState: StateFlow<RgbWriteState?> = _rgbWriteState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<CoolerDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<CoolerDevice>> = _discoveredDevices.asStateFlow()

    private val _rawDevices = MutableStateFlow<List<BleScanDiagnostic>>(emptyList())
    override val rawDevices: StateFlow<List<BleScanDiagnostic>> = _rawDevices.asStateFlow()

    private val _scanState = MutableStateFlow(
        // 内置风扇不走蓝牙：永远视为已授权、无需定位、蓝牙恒开。
        ScanState(scanning = false, permissionGranted = true, locationServiceEnabled = true, bluetoothOn = true),
    )
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    override val isBluetoothEnabled: Boolean get() = true

    // ───────────────────── 控制状态（唯一真源） ─────────────────────

    private enum class ControlMode { OFF, MANUAL, SMART, BOOST }

    private var controlMode = ControlMode.MANUAL

    /**
     * 风扇开关关闭前**正在跑的模式**，用于重开时恢复。
     *
     * 「风扇开关」在语义上是**总电源**，不是"切到手动档"：
     * 关掉它只是让风扇停转，用户对调速策略的选择（智能温控 / 手动档位 / 满速）
     * 并没有被撤销，所以重开时必须回到原来的模式。少了这个"记忆"，
     * 用户关一次风扇就会丢掉辛苦调好的智能温控，必须再手点一次开关才能找回来。
     *
     * 只在进入 [ControlMode.OFF] 时冻结，且在**每次落入非 OFF 模式时刷新**；
     * 因此它永远描述"用户最后主动选择的那个运行模式"。
     *
     * 刻意**不持久化**：硬件控制状态本身不跨进程存活（[controlMode] 也是内存态），
     * 重启后由前台服务重新接管并按自己的意图写模式；一个落盘的旧模式反而会在
     * 下次启动时与服务的实际意图打架。这里要修的是同一次会话内的开关往返。
     */
    private var lastActiveMode = ControlMode.MANUAL

    /** 已挂载：Root 可用且风扇节点可读。**不代表已经接管硬件**。 */
    private var attached = false

    /** 已执行过接管写（`cooling_fan_enable 0`）。 */
    private var tookOver = false

    private var nextSessionId = System.nanoTime()
    private var pollJob: Job? = null

    /** 智能温控当前目标占空比：**曲线的直接输出**，不再被"每轮最多动 N%"削减。 */
    private var smartTargetPwm = 0
    /** 一次过渡的起点占空比：进入这次过渡时的实际值。 */
    private var rampFromPwm = 0
    /** 这次过渡的起始时刻（单调时钟）。[fanTransitionMs] = 0 时不参与计算。 */
    private var rampStartNanos = 0L
    /** 最近一次生效的占空比，供 [publishControlState] 使用。 */
    private var lastPwm = 0
    /** 最近一次读到的有效温度；温度读取失败时用于判断是否需要降级提示。 */
    private var lastTemperature: Float? = null

    /**
     * 所有节点写入串行化。上游实现里滑条/开关/轮询各自 `scope.launch`，
     * 快速切换模式时旧命令可能覆盖新选择；这里用一把互斥锁保证写入按到达顺序生效，
     * 且「挂载 → 接管 → 写入」不会交错。
     */
    private val controlMutex = Mutex()

    /** 挂载串行化（见 [attach]）：与 [controlMutex] 分开，避免长时间持锁挡住控制写入。 */
    private val attachMutex = Mutex()

    /** 滑条最新目标值；-1 表示没有待处理的值（用于合并连续拖动）。 */
    private val pendingSpeed = AtomicInteger(-1)

    /**
     * 内置风扇没有真正的蓝牙地址，但 ProfileRepository 会用
     * [io.github.wxmyyds.coldfront.data.normalizeMacAddress] 校验 `AA:BB:CC:DD:EE:FF`
     * 格式（不合规直接丢弃档案）。这里用一个**合成 MAC**：`02` 是本地管理位 + 单播，
     * 格式合法、且不会与任何真实蓝牙设备冲突。
     */
    private val fanAddress: String get() = FAN_ADDRESS

    /**
     * 智能温控曲线。**来自持久化**，构造时不再写死 —— 用户改完曲线退出应用，
     * 下次由前台服务或开机恢复拉起时用的就是他自己那条线。
     */
    @Volatile
    private var smartCurve: FanCurve = FanCurve.DEFAULT

    /** 曲线基准温度源下标。与设置里选中的是同一个值。 */
    @Volatile
    private var temperatureSource: Int = FanNodes.DEFAULT_TEMPERATURE_SOURCE

    /** 息屏停转开关。默认关闭：后台改变硬件行为必须来自显式授权。 */
    @Volatile
    private var screenOffStopEnabled = false

    /** 屏幕是否点亮。**由前台服务告知**，不由本类自己注册广播（生命周期归属要清晰）。 */
    @Volatile
    private var screenInteractive = true

    /** 息屏相位。只有 [ScreenPhase.ACTIVE] 时曲线才允许写转速。 */
    private var screenPhase = ScreenPhase.ACTIVE

    /** 连续读不到温度的轮数，用于触发失效降级。 */
    private var temperatureFailureCount = 0

    /** 已因温度不可读而降级交还系统；避免重复写。 */
    private var degradedToSystem = false

    /**
     * 升降过渡时间（毫秒）。**来自设置**（见 [FanTuning.TRANSITION_RANGE]），默认 0 = **一步到位**。
     *
     * 0 的含义是：温度一变，下一轮轮询就把曲线算出的目标直接写下去（不再有"每秒最多涨 5%"）。
     * 大于 0 时，从目标变化那一刻起在设定时间内线性走到目标，按轮询节拍落地。
     */
    @Volatile
    private var fanTransitionMs: Int = FanTuning.DEFAULT_TRANSITION_MS

    /**
     * 轮询间隔（毫秒）。同样来自设置，默认 1.5 秒。
     *
     * 轮询循环每轮重新读取本字段，因此用户在设置页改完**下一轮就生效**，不必重启服务。
     */
    @Volatile
    private var pollIntervalMs: Int = FanTuning.DEFAULT_POLL_INTERVAL_MS

    private val settingsRepository = SettingsRepository(context)

    init {
        // 只订阅偏好，不碰任何硬件节点：「读取曲线」本身不是接管。
        scope.launch {
            // 联动开关的**边沿**才需要动硬件：每次设置回流都写一遍 sysfs 会让
            // DataStore 的任何无关变更（改个语言、调个轮询间隔）都去抢一次控制权。
            settingsRepository.settings.collect { settings ->
                smartCurve = settings.smartCurve
                temperatureSource = settings.temperatureSource
                screenOffStopEnabled = settings.screenOffStopFan
                pollIntervalMs = settings.pollIntervalMs
                fanTransitionMs = settings.fanTransitionMs
            }
        }
    }

    companion object {
        /** 连续多少轮读不到温度后判定为失效并降级交还系统。 */
        private const val TEMPERATURE_FAILURE_LIMIT = 3

        /**
         * 交还系统时给系统指定的档位：静谧档。
         *
         * 系统冷时自然停转、热了会自己拉起来 —— 这正是"高温兜底"的机制所在。
         * （`settings put system fan_mode <mode>`：-1 智能 / 1 静谧 / 0 高速 / 4 狂暴）
         */
        private const val SYSTEM_SILENT_MODE = 1

        /** 退出自动/满速模式后回到的「静谧档」占空比（与参考实现默认档一致）。 */
        const val MANUAL_EXIT_DUTY = 30

        /** 合成蓝牙地址（见 [fanAddress]）。 */
        const val FAN_ADDRESS = "02:00:00:00:00:01"
    }

    // 注意：**这里没有 init 连接**。旧实现在构造时自动 connectByAddress，
    // 会立刻接管用户的风扇；挂载改由 [activate] 显式发起（只读）。

    // ───────────────────── 挂载 / 连接 ─────────────────────

    override fun refreshScanConditions() = Unit

    /** 内置风扇即时「发现」：扫描即列出本机风扇，不依赖蓝牙。 */
    override fun startScan() {
        // 读机型名要起 su 进程，必须离开主线程，否则 UI 会卡顿甚至 ANR。
        scope.launch {
            val name = FanNodes.readMarketName()
            val device = CoolerDevice(
                address = fanAddress,
                bleName = "$name 内置风扇",
                deviceType = CoolerDeviceType.BUILT_IN_FAN,
                rssi = 0,
            )
            _discoveredDevices.value = listOf(device)
            _rawDevices.value = listOf(
                BleScanDiagnostic(
                    address = fanAddress,
                    name = device.bleName,
                    rssi = 0,
                    msd = emptyList(),
                    serviceUuids = emptyList(),
                    serviceData = emptyList(),
                    coolerType = CoolerDeviceType.BUILT_IN_FAN,
                ),
            )
            _scanState.update { it.copy(scanning = false, errorCode = null) }
        }
    }

    override fun stopScan() {
        _scanState.update { it.copy(scanning = false) }
    }

    override fun connect(device: CoolerDevice) = activate(requestPrompt = true)

    override fun connectRaw(entry: BleScanDiagnostic, type: CoolerDeviceType) = activate(requestPrompt = true)

    override fun connectByAddress(macAddress: String, type: CoolerDeviceType) = activate(requestPrompt = true)

    /**
     * 只读挂载。
     *
     * @param requestPrompt true 时允许等待 Root 授权弹窗（用户显式点击触发）；
     *   false 时用短超时静默探测，用于应用启动后的自动状态刷新。
     */
    override fun activate(requestPrompt: Boolean) {
        scope.launch { attach(requestPrompt) }
    }

    private suspend fun attach(requestPrompt: Boolean): Boolean {
        // 挂载要起多个 su 进程（首次可能等授权弹窗 30s），必须串行化：
        // 否则 activate() 与控制路径可能同时进来，各自 startPolling / 递增会话号。
        return attachMutex.withLock {
            if (attached) return@withLock true
            val root = runCatching { FanNodes.probeRoot(requestPrompt) }.getOrDefault(false)
            if (!root) {
                _state.update { it.copy(connection = ConnectionState.FAILED, deviceAddress = fanAddress) }
                return@withLock false
            }
            if (!FanNodes.isFanSupported()) {
                _state.update { it.copy(connection = ConnectionState.FAILED, deviceAddress = fanAddress) }
                return@withLock false
            }
            attached = true
            nextSessionId++
            val name = FanNodes.readMarketName()
            controlMode = ControlMode.MANUAL
            _state.update {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    connectionSessionId = nextSessionId,
                    deviceType = CoolerDeviceType.BUILT_IN_FAN,
                    deviceName = name,
                    deviceAddress = fanAddress,
                    rssi = null,
                    // 内置风扇支持这些能力；PROTECTION 不上报 → 过冷保护界面不出现
                    capabilities = CoolerCapabilities(
                        hasCoolingSwitch = true,
                        coolingControl = true,
                        fanControl = true,
                        smartControl = true,
                        boostControl = true,
                        protectionControl = false,
                    ),
                    confirmedConfiguration = setOf(
                        CoolerBleConstants.COOLING_SWITCH_UUID,
                        CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID,
                        CoolerBleConstants.AUTO_MODE_CONTROL_UUID,
                        CoolerBleConstants.BOOST_CONTROL_UUID,
                    ),
                    telemetryDegraded = false,
                    controlError = false,
                )
            }
            publishControlState()
            startPolling()
            true
        }
    }

    override fun hasRunningSession(): Boolean = attached

    override fun disconnect() {
        attached = false
        stopPolling()
        nextSessionId++
        _state.update {
            it.copy(connection = ConnectionState.DISCONNECTED, connectionSessionId = nextSessionId)
        }
    }

    // ───────────────────── BackgroundLinkLossStore ─────────────────────
    // 风扇是本地节点，不存在链路丢失；永远返回 null 让 BackgroundResume 无事可做。

    override var foregroundStarted: Boolean = false
    override fun consumeLinkLoss(): BackgroundLinkLoss? = null
    override fun clearLinkLoss() = Unit

    // ───────────────────── 控制 ─────────────────────

    /**
     * 挂载 + 首次接管，然后在锁内执行 [block]。
     * 接管写只发生一次：重复的 `settings put` 是纯浪费（每次都要起一个 su 进程）。
     *
     * 挂载在**锁外**进行：它最多要起 3 个 `su` 进程（机型名 / Root / 节点），最坏情况数秒；
     * 若在 `controlMutex` 里做，会把轮询写入和后续控制点击一起堵住。
     * 并发挂载由 [attachMutex] 串行化。
     *
     * 挂载**不带授权弹窗**（`requestPrompt = false`）：需要弹窗的路径是首页「启用控制」按钮
     * （[activate]），不能让一次拖动去等 30s 的系统授权界面。
     */
    private suspend fun <T> withControl(block: suspend () -> T): T? {
        if (!attached && !attach(requestPrompt = false)) {
            setControlError(true)
            return null
        }
        return controlMutex.withLock {
            if (!tookOver) {
                val takenOver = runCatching {
                    FanNodes.runSu(FanNodes.cmdSetCoolingFanEnable(false)).success
                }.getOrDefault(false)
                tookOver = takenOver
                if (!takenOver) setControlError(true)
            }
            block()
        }
    }

    /**
     * 调速滑条：写 `pwm_duty`（0–99 无极调速）。
     *
     * 连续拖动会被合并（只写最新目标，避免每次触摸都排一个 su 进程），但合并必须**排空**：
     * 循环取到 -1 才结束，否则最后一次拖动可能正好落在两次写入之间而被丢掉。
     */
    override fun setFanSpeed(percent: Int) {
        val pwm = percent.coerceIn(0, 99)
        pendingSpeed.set(pwm)
        controlMode = ControlMode.MANUAL
        // 手动滑条不走过渡：手指在哪就是哪。
        settleRamp(pwm)
        // 立即回显目标值，避免拖动时数字滞后。
        _state.update { it.copy(pendingFanPercent = pwm) }
        scope.launch {
            var failed = false
            while (true) {
                val target = pendingSpeed.getAndSet(-1)
                if (target < 0) break
                val written = withControl {
                    val ok = FanNodes.writeInt(FanNodes.pwmDutyPath, target)
                    if (ok) lastPwm = target
                    ok
                }
                if (written == null || !written) failed = true
                if (written == null) break
            }
            setControlError(failed)
            // 排空后不再有 pending：显示值回落到最近一次确认写入的占空比。
            publishControlState()
        }
    }

    /**
     * 风扇总开关：关 = 停转；开 = **恢复到关闭前的模式**（见 [lastActiveMode]）。
     *
     * 开关本身不理解也不想覆盖调速策略，所以：
     * - 关：先记下当前正在跑的模式，再落到 [ControlMode.OFF]；
     * - 开：按记下的模式恢复 —— 原来在智能温控就回到温控（并按曲线重新开始调速），
     *   原来在满速就回到满速，否则回到手动静谧档。
     *
     * 只有"原来就是 OFF"这种情况没有可恢复的模式，才退回静谧档。
     */
    override fun setCooling(on: Boolean) {
        scope.launch {
            // 关闭前先快照当前模式：进入 OFF 之后就再也读不到它了。
            if (!on && controlMode != ControlMode.OFF) lastActiveMode = controlMode
            val restore = lastActiveMode
            val ok = withControl {
                if (on) {
                    when (restore) {
                        ControlMode.SMART -> restoreSmart()
                        ControlMode.BOOST -> restoreBoost()
                        else -> restoreManual()
                    }
                } else {
                    controlMode = ControlMode.OFF
                    val level = FanNodes.writeInt(FanNodes.targetLevelPath, 0)
                    val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, 0)
                    level && duty
                }
            }
            if (ok == true) {
                if (on) {
                    // 恢复智能/满速时这两个字段已由各自的恢复函数写到落定值上。
                    if (restore != ControlMode.SMART && restore != ControlMode.BOOST) {
                        lastPwm = 0
                        settleRamp(0)
                    }
                } else {
                    lastPwm = 0
                    // 关闭/恢复静谧档都是直接写下来的，过渡状态钉在落定值上。
                    settleRamp(0)
                }
                setControlError(false)
            } else if (ok == false) {
                setControlError(true)
            }
            publishControlState()
        }
    }

    /**
     * 恢复手动静谧档：`target_level 1` + `pwm_duty 0`。
     *
     * 两个节点分别写、分别判定：短路 `&&` 会在第一个失败时静默跳过第二个，
     * 让「只写成一半」的状态被当成整体失败或整体成功。
     *
     * 调用方必须已持有 [controlMutex]。
     */
    private suspend fun restoreManual(): Boolean {
        controlMode = ControlMode.MANUAL
        val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
        val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, 0)
        return level && duty
    }

    /**
     * 恢复智能温控：重新读温度、按曲线算出目标占空比并落到位。
     *
     * **必须重新算曲线**，不能只把 [controlMode] 改回 SMART —— 温度在这段时间里
     * 已经变了，沿用旧的 [smartTargetPwm] 会让风扇按过期的目标转，
     * 直到下一次轮询才纠正。
     *
     * 温度读不到时**绝不进温控**（与 [applySmart] 同一原则：拿 0°C 算曲线会把风扇压到最低），
     * 此时退回静谧档并如实报告失败。
     *
     * 调用方必须已持有 [controlMutex]。
     */
    private suspend fun restoreSmart(): Boolean {
        val temperature = FanNodes.readTemperatureC(temperatureSource)
        if (temperature == null) {
            setControlError(true)
            return restoreManual()
        }
        controlMode = ControlMode.SMART
        beginRamp(smartCurve.pwmFor(temperature))
        val applied = rampPwmNow()
        val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
        val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, applied)
        if (duty) lastPwm = applied
        return level && duty
    }

    /**
     * 恢复满速：`target_level 4` + `pwm_duty 99`。
     *
     * 调用方必须已持有 [controlMutex]。
     */
    private suspend fun restoreBoost(): Boolean {
        controlMode = ControlMode.BOOST
        val level = FanNodes.writeInt(FanNodes.targetLevelPath, 4)
        val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, 99)
        if (level && duty) {
            settleRamp(99)
            lastPwm = 99
        }
        return level && duty
    }

    /** 智能温控：按温度曲线自动调速。 */
    override fun setSmart(on: Boolean) {
        scope.launch { applySmart(on) }
    }

    /**
     * Service / Tile 用的等待版本：**真正**完成写入后才返回结果，
     * 而不是像旧实现那样 `setSmart(on); return true` 无条件报告成功。
     */
    override suspend fun setSmartAndAwait(on: Boolean): Boolean = applySmart(on) == true

    private suspend fun applySmart(on: Boolean): Boolean? {
        return withControl {
            if (on) {
                val temperature = FanNodes.readTemperatureC(temperatureSource)
                if (temperature != null) {
                    controlMode = ControlMode.SMART
                    // 接管瞬间就按过渡设置走：过渡时间 0（默认）时直接落到曲线目标。
                    beginRamp(smartCurve.pwmFor(temperature))
                    val applied = rampPwmNow()
                    // 两个节点都要写、都要判定：`target_level` 写失败而 `pwm_duty` 成功时，
                    // 只按后者返回 true 会让前台服务认为自动模式已生效。
                    val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
                    val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, applied)
                    if (duty) lastPwm = applied
                    setControlError(!(level && duty))
                    level && duty
                } else {
                    // 温度读不到就绝不进温控：旧实现会拿 0°C 算曲线，把风扇压到最低。
                    setControlError(true)
                    false
                }
            } else {
                // 关闭智能温控：回到静谧档、交回手动滑条控制。
                // 这里必须**真的写节点**并如实返回结果 —— 只改本地状态却返回 true，
                // 会让调用方（前台服务）在风扇仍由曲线驱动时报「已切回手动」。
                controlMode = ControlMode.MANUAL
                val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
                val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, MANUAL_EXIT_DUTY)
                if (level && duty) {
                    settleRamp(MANUAL_EXIT_DUTY)
                    lastPwm = MANUAL_EXIT_DUTY
                }
                setControlError(!(level && duty))
                level && duty
            }
        }.also { publishControlState() }
    }

    /**
     * 满速模式（上游叫「破坏神」）：强制满速 `target_level 4` / `pwm_duty 99`。
     * 关闭时必须**回写节点**——只改本地状态会让风扇停在满速而 UI 显示已关闭。
     */
    override fun setBoost(on: Boolean) {
        scope.launch {
            val ok = withControl {
                if (on) {
                    controlMode = ControlMode.BOOST
                    // 两个节点分别写、分别判定（短路 `&&` 会静默跳过第二个写）。
                    val level = FanNodes.writeInt(FanNodes.targetLevelPath, 4)
                    val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, 99)
                    level && duty
                } else {
                    // 退出满速 → 回到静谧档，交回滑条控制。
                    controlMode = ControlMode.MANUAL
                    val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
                    val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, MANUAL_EXIT_DUTY)
                    level && duty
                }
            }
            if (ok == true) {
                if (on) {
                    settleRamp(99)
                    lastPwm = 99
                } else {
                    settleRamp(MANUAL_EXIT_DUTY)
                    lastPwm = MANUAL_EXIT_DUTY
                }
                setControlError(false)
            } else if (ok == false) {
                setControlError(true)
            }
            publishControlState()
        }
    }

    /** 内置风扇没有过冷/冷凝保护硬件；能力里未上报，UI 不会出现该开关。 */
    override fun setOvercoldProtection(on: Boolean) = Unit

    /** 内置风扇没有 RGB。 */
    override fun setRGB(config: RGBConfig) = Unit

    /**
     * Safely return control to Android without relying on the service staying alive.
     * The mutex is acquired here (rather than by callers) because it is not reentrant.
     */
    override suspend fun releaseToSystem(): Boolean {
        val ok = controlMutex.withLock {
            val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
            val enable = FanNodes.runSu(FanNodes.cmdSetCoolingFanEnable(true)).success
            // 交还给系统时固定用静谧档（见 [SYSTEM_SILENT_MODE] 的说明）。
            val mode = FanNodes.runSu(FanNodes.cmdSetFanMode(SYSTEM_SILENT_MODE)).success
            if (level && enable && mode) {
                tookOver = false
                screenPhase = ScreenPhase.HANDED_BACK
                degradedToSystem = false
                true
            } else false
        }
        if (ok) {
            screenPhase = ScreenPhase.HANDED_BACK
            _state.update { it.copy(screenOffStopped = false, controlDegraded = false) }
        } else {
            setControlError(true)
        }
        return ok
    }

    override fun release() {
        attached = false
        stopPolling()
    }

    // ───────────────────── 状态发布 ─────────────────────

    private fun setControlError(failed: Boolean) {
        if (_state.value.controlError == failed) return
        _state.update { it.copy(controlError = failed) }
    }

    /**
     * 由 [controlMode] 推导 UI 上的开关。**只有这里**写 `coolingOn`/`smartOn`/
     * `boostOn`/`fanMode`，轮询不得触碰，保证 UI 与硬件一一对应。
     */
    private fun publishControlState(pendingPercent: Int? = null) {
        val mode = controlMode
        // 每次落入一个非 OFF 的运行模式都刷新记忆，这样它始终是"用户最后选的那个模式"，
        // 而不是"上一个被关掉时的模式"（后者在关-A开-B再关之后会过期）。
        if (mode != ControlMode.OFF) lastActiveMode = mode
        _state.update {
            it.copy(
                coolingOn = mode != ControlMode.OFF,
                smartOn = mode == ControlMode.SMART,
                boostOn = mode == ControlMode.BOOST,
                curvePreset = smartCurve.matchingPreset()?.ordinal ?: -1,
                fanMode = when (mode) {
                    ControlMode.OFF -> FanMode.OFF
                    ControlMode.SMART -> FanMode.AUTO
                    else -> FanMode.MANUAL
                },
                fanPercent = lastPwm,
                fanRaw = lastPwm,
                pendingFanPercent = pendingPercent,
            )
        }
    }

    // ───────────────────── 轮询遥测 ─────────────────────

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (attached) {
                pollOnce()
                // 间隔每轮重读：用户在设置页改完，下一轮就按新值跑，不必重启服务。
                // 息屏时曲线不执行（见 [ScreenPhase]），轮询只剩驱动相位判定这一个用途，因此放慢。
                delay(
                    if (screenInteractive) pollIntervalMs.toLong()
                    else FanTuning.screenOffPollInterval(pollIntervalMs),
                )
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 一次批量遥测。整个批次只起 **一个** `su` 进程 —— `su` 唤起是主要开销。
     * 只读；智能温控的调速写会经过 [controlMutex] 与用户操作串行化。
     */
    private suspend fun pollOnce() {
        val read = FanNodes.readSnapshot(temperatureSource)
        if (!read.executed) {
            // 命令本身没跑成功：保持上一次的显示值，并提示数据可能过期。
            _state.update { it.copy(telemetryDegraded = true) }
            return
        }
        val snapshot = read.snapshot
        val temperature = snapshot.temperatureRaw?.let { if (it > 1000) it / 1000f else it.toFloat() }
        if (temperature != null) {
            lastTemperature = temperature
            temperatureFailureCount = 0
        }
        val realRpm = snapshot.rpmRaw?.let { if (it == 2) 0 else it }
        val pwmPercent = snapshot.pwmRaw?.let { if (it > 100) (it * 100 / 255.0).roundToInt() else it }

        // 息屏相位先算：它决定本轮曲线到底允不允许写。
        // 屏幕亮着时策略恒返回 ACTIVE，因此这里顺带把"离开智能温控 / 关掉开关"后的残留相位复位。
        applyScreenPhase(temperature?.toInt())

        if (controlMode == ControlMode.SMART) {
            if (temperature == null) {
                // 温度断了就停手：保持当前转速并标降级，而不是拿旧值/0 继续写。
                // 连续失败到阈值会降级交还系统（见 [onTemperatureUnreadable]）——
                // 常驻场景下用户不在看界面，静默冻结在某个转速上不可接受。
                onTemperatureUnreadable()
                _state.update {
                    it.copy(telemetryDegraded = true, fanRpm = realRpm ?: it.fanRpm)
                }
                return
            }
            if (screenPhase != ScreenPhase.ACTIVE) {
                // 停转 / 交还系统：本轮不写曲线目标值，只如实回读节点上的真实状态。
                _state.update {
                    it.copy(
                        temperatureC = temperature,
                        fanRpm = realRpm ?: it.fanRpm,
                        fanPercent = pwmPercent ?: it.fanPercent,
                        fanRaw = pwmPercent ?: it.fanRaw,
                        telemetryDegraded = false,
                    )
                }
                return
            }
            controlMutex.withLock {
                if (!attached || controlMode != ControlMode.SMART) return@withLock
                if (screenPhase != ScreenPhase.ACTIVE) return@withLock
                val target = smartCurve.pwmFor(temperature.toInt())
                // 先推进已有过渡，避免目标每轮变化时一直重置为起点而无法调速。
                val progressed = rampPwmNow()
                if (target != smartTargetPwm) beginRamp(target, progressed)
                val write = rampPwmNow()
                val applied = write == pwmPercent || FanNodes.writeInt(FanNodes.pwmDutyPath, write)
                setControlError(!applied)
                if (applied) lastPwm = write
                _state.update {
                    it.copy(
                        temperatureC = temperature,
                        fanRpm = realRpm ?: it.fanRpm,
                        fanPercent = if (applied) write else it.fanPercent,
                        fanRaw = if (applied) write else it.fanRaw,
                        telemetryDegraded = false,
                    )
                }
            }
            return
        }

        if (pwmPercent != null) lastPwm = pwmPercent
        _state.update {
            it.copy(
                temperatureC = temperature ?: it.temperatureC,
                fanRpm = realRpm ?: it.fanRpm,
                fanPercent = pwmPercent ?: it.fanPercent,
                fanRaw = pwmPercent ?: it.fanRaw,
                telemetryDegraded = false,
            )
        }
    }

    // ───────────────────── 息屏停转 ─────────────────────

    /**
     * 由前台服务告知屏幕状态。
     *
     * 屏幕广播由**服务**持有，不让管理器自己去注册：生命周期归属要清晰，
     * 也避免管理器在没有任何宿主时偷偷活着。
     */
    override fun setScreenInteractive(interactive: Boolean) {
        if (screenInteractive == interactive) return
        screenInteractive = interactive
        // 立刻重算一次，不等下一个轮询周期：息屏轮询间隔是 15 秒，
        // 亮屏后最多等 15 秒才恢复曲线是不可接受的。
        scope.launch { applyScreenPhase(lastTemperature?.toInt()) }
    }

    /** 当前息屏相位，供服务/界面判断"风扇是被谁停的"。 */
    val currentScreenPhase: ScreenPhase get() = screenPhase

    /**
     * 重算息屏相位，并只在**相位发生变化**时执行对应的写。
     *
     * 三个相位对应三组完全不同的写：
     * - [ScreenPhase.ACTIVE]：收回控制权，曲线继续。交还系统时我们放弃了接管，
     *   所以这里必须**重新接管**，否则后面曲线写了也不生效。
     * - [ScreenPhase.STOPPED]：保持接管并写 0。复用「关闭风扇」那条写入组合
     *   （`target_level 0` + `pwm_duty 0`），不发明新的节点语义。
     * - [ScreenPhase.HANDED_BACK]：交还系统并指定系统静谧档 —— 冷时系统自然停转，
     *   热了它自己会拉起来，这就是"热兜底"的来源。
     *
     * 写失败时**回退相位**，下一轮会重试；同时置 [CoolerLiveState.controlError] 如实报错。
     */
    private suspend fun applyScreenPhase(temperatureC: Int?) {
        val target = ScreenOffPolicy.next(
            enabled = screenOffStopEnabled,
            smartActive = controlMode == ControlMode.SMART,
            screenInteractive = screenInteractive,
            charging = isCharging(),
            temperatureC = temperatureC,
            current = screenPhase,
        )
        if (target == screenPhase) return
        val previous = screenPhase
        screenPhase = target
        val ok = controlMutex.withLock {
            when (target) {
                ScreenPhase.ACTIVE -> {
                    tookOver = false
                    runCatching { FanNodes.runSu(FanNodes.cmdSetCoolingFanEnable(false)).success }
                        .getOrDefault(false)
                        .also { tookOver = it }
                }
                ScreenPhase.STOPPED -> {
                    val level = FanNodes.writeInt(FanNodes.targetLevelPath, 0)
                    val duty = FanNodes.writeInt(FanNodes.pwmDutyPath, 0)
                    if (level && duty) {
                        lastPwm = 0
                        settleRamp(0)
                    }
                    level && duty
                }
                ScreenPhase.HANDED_BACK -> writeHandedBack()
            }
        }
        if (!ok) screenPhase = previous
        _state.update { it.copy(screenOffStopped = ok && screenPhase == ScreenPhase.STOPPED) }
        if (!ok) setControlError(true)
    }

    /**
     * Caller must already hold [controlMutex]. This operation intentionally attempts all
     * three writes even if an earlier one fails, so partial cleanup is minimized.
     */
    private suspend fun writeHandedBack(): Boolean {
        val level = FanNodes.writeInt(FanNodes.targetLevelPath, 1)
        val enable = FanNodes.runSu(FanNodes.cmdSetCoolingFanEnable(true)).success
        val mode = FanNodes.runSu(FanNodes.cmdSetFanMode(SYSTEM_SILENT_MODE)).success
        val ok = level && enable && mode
        if (ok) tookOver = false
        return ok
    }

    /**
     * 是否在充电。息屏停转的主要豁免条件 —— 充电是最大的发热场景.
     *
     * **读不到就按"在充电"处理**：宁可交给系统，也不要在我们并不了解的热场景下停风扇。
     */
    private fun isCharging(): Boolean = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(true)

    // ───────────────────── 失效降级 ─────────────────────

    /**
     * 温度连续读不到时降级：回静谧档 + 交还系统。
     *
     * 这是**故障兜底**，与息屏停转（用户主动意图）是两回事，但最终都落到"交还系统"。
     * 常驻场景下用户不在看界面，风扇静默冻结在某个转速上是不能被接受的，
     * 所以这里必须动作并让 [CoolerLiveState.controlDegraded] 可见。
     *
     * 进程被 `SIGKILL` 时无法执行任何降级写 —— 这是能力边界，靠服务 `START_STICKY`
     * 与开机恢复抢救。
     */
    private suspend fun onTemperatureUnreadable() {
        temperatureFailureCount++
        if (temperatureFailureCount < TEMPERATURE_FAILURE_LIMIT || degradedToSystem) return
        degradedToSystem = true
        val released = controlMutex.withLock { writeHandedBack() }
        if (!released) setControlError(true)
        screenPhase = ScreenPhase.HANDED_BACK
        _state.update { it.copy(controlDegraded = true, screenOffStopped = false) }
    }

    // ───────────────────── 曲线过渡 ─────────────────────

    /** 开始一次升/降过渡；默认 0ms 会在 [rampPwmNow] 中直接返回目标。 */
    private fun beginRamp(target: Int, fromPwm: Int = lastPwm) {
        smartTargetPwm = target.coerceIn(0, 99)
        rampFromPwm = fromPwm.coerceIn(0, 99)
        rampStartNanos = System.nanoTime()
    }

    /** 手动、满速、停转等直接写入后，清除旧的智能过渡状态。 */
    private fun settleRamp(pwm: Int) = beginRamp(pwm, pwm)

    private fun rampPwmNow(): Int {
        val elapsedMs = ((System.nanoTime() - rampStartNanos) / 1_000_000L).coerceAtLeast(0L)
        return FanTuning.rampPwm(rampFromPwm, smartTargetPwm, elapsedMs, fanTransitionMs)
    }
}
