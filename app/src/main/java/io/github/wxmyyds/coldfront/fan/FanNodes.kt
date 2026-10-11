package io.github.wxmyyds.coldfront.fan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Root 节点访问层 —— 对应 mi-fan-control 的 `FanUtil` + `FanSpeedSimulator`。
 *
 * 控制/读数全部走 `su -c`，节点路径取自 K90 Ultra / K90 Max 实机：
 *
 * | 用途 | K90 Ultra | K90 Max |
 * |---|---|---|
 * | 风扇占空比 | `soc/soc:xiaomi_fan/pwm_duty` | `odm/odm:xiaomi_fan/pwm_duty` |
 * | 目标档位   | `soc/soc:xiaomi_fan/target_level` | `odm/odm:xiaomi_fan/target_level` |
 * | 实时转速   | `soc/soc:xiaomi_fan/real_speed` | `odm/odm:xiaomi_fan/real_speed` |
 * | 风扇支持   | `soc/soc:xiaomi_fan/fan_support` | 同上 |
 *
 * 与 mi-fan-control 的两点差异，都是本机实测踩到的坑：
 * 1. **命令用绝对路径**。`su -c` 继承的是 engine 的 PATH，里面 `settings`/`getprop`
 *    是坏 shim（`.../engine/bin/settings: No such file or directory`），必须写
 *    `/system/bin/settings`、`/system/bin/getprop`。
 * 2. **超时要覆盖读输出阶段**。旧实现先阻塞读完 stdout 再 `waitFor`，`su` 授权弹窗
 *    未响应时根本走不到超时分支；现在输出由独立线程并发抽取（[executeProcess]），
 *    超时必然生效。
 *
 * 本类**只读方法不会写入任何节点**：首次引导和「运行检查」只调用
 * [probeRoot] / [readSnapshot] / [readMarketName] / [isFanSupported]，不含任何 `echo`。
 */
object FanNodes {

    private const val NODE_ULTRA = "/sys/devices/platform/soc/soc:xiaomi_fan"
    private const val NODE_MAX = "/sys/devices/platform/odm/odm:xiaomi_fan"

    const val CMD_GET_MARKET_NAME = "/system/bin/getprop ro.product.marketname"

    /** 系统是否接管了风扇（1 = 系统控制，0 = 本应用已夺回） */
    const val CMD_GET_COOLING_FAN_ENABLE = "/system/bin/settings get system cooling_fan_enable"
    fun cmdSetCoolingFanEnable(enable: Boolean) =
        "/system/bin/settings put system cooling_fan_enable ${if (enable) 1 else 0}"

    /** 系统风扇档位：-1 智能 / 1 静谧 / 0 高速 / 4 狂暴 */
    const val CMD_GET_FAN_MODE = "/system/bin/settings get system fan_mode"
    fun cmdSetFanMode(mode: Int) = "/system/bin/settings put system fan_mode $mode"

    /** 智能温控档位。**开关智能温控与选中档位是同一件事**，见 [cmdSetFanMode]。 */
    const val SYSTEM_FAN_MODE_SMART = -1

    /** 只为确认 uid：授权成功后输出 `0`。 */
    private const val CMD_GET_UID = "/system/bin/id -u"

    /**
     * 首次 `su` 会弹出授权界面（KernelSU / Magisk），用户点击需要时间；
     * 之后调用走已授权路径，用短超时即可。
     */
    const val ROOT_PROMPT_TIMEOUT_MS = 30_000L
    const val ROOT_CACHED_TIMEOUT_MS = 3_000L

    /** 节点读写：正常 <100ms，留足余量但不至于让 UI 长时间悬挂。 */
    const val NODE_TIMEOUT_MS = 2_500L

    @Volatile
    private var cachedMarketName: String? = null

    /** null = 尚未探测；true/false = 已知结果。 */
    @Volatile
    private var rootGranted: Boolean? = null

    /** K90 Max 的节点在 odm 下，Ultra 在 soc 下；按机型选路径。 */
    val nodePath: String
        get() = if (isMaxModel) NODE_MAX else NODE_ULTRA

    val pwmDutyPath: String get() = "$nodePath/pwm_duty"
    val targetLevelPath: String get() = "$nodePath/target_level"
    val realSpeedPath: String get() = "$nodePath/real_speed"
    val fanSupportPath: String get() = "$nodePath/fan_support"

    val isMaxModel: Boolean
        get() = (cachedMarketName ?: readMarketName())
            .lowercase().let { it.contains("k90 max") || it.contains("k90max") }

    /** 机型名；读不到返回空串（读操作，不写节点）。 */
    fun readMarketName(): String {
        cachedMarketName?.let { return it }
        val name = runSuBlocking(CMD_GET_MARKET_NAME).output.ifBlank { android.os.Build.MODEL }
        cachedMarketName = name
        return name
    }

    // ───────────────────── Root 命令执行 ─────────────────────

    /** 连续失败达到阈值后熔断一段时间，避免 root 异常时刷屏 / 高频起进程。 */
    private const val SU_FAIL_THRESHOLD = 10
    private const val FUSE_DURATION_MS = 5_000L

    @Volatile private var suFailCount = 0
    @Volatile private var fuseStartMs = 0L

    /**
     * 同步执行 `su -c`。返回 [RootResult]，其中 `exitCode`/`timedOut` 可用于判断
     * 命令是否真的成功 —— 旧实现只返回 stdout 字符串，写失败与写成功无法区分。
     * 只允许在 IO 线程调用。
     */
    fun runSuBlocking(cmd: String, timeoutMs: Long = NODE_TIMEOUT_MS): RootResult {
        val now = System.currentTimeMillis()
        if (suFailCount >= SU_FAIL_THRESHOLD) {
            if (now - fuseStartMs < FUSE_DURATION_MS) {
                return RootResult(timedOut = false, exitCode = -1, fused = true)
            }
            // 熔断窗口结束：重置计数，同时也清掉时间戳，避免陈旧时间戳残留。
            suFailCount = 0
            fuseStartMs = 0L
        }
        val result = executeProcess(listOf(BINARY_SU, "-c", cmd), timeoutMs)
        if (result.success) {
            suFailCount = 0
            fuseStartMs = 0L
        } else {
            onFailure()
        }
        return result
    }

    private const val BINARY_SU = "su"

    private fun onFailure() {
        suFailCount++
        if (suFailCount >= SU_FAIL_THRESHOLD) {
            fuseStartMs = System.currentTimeMillis()
        }
    }

    suspend fun runSu(cmd: String, timeoutMs: Long = NODE_TIMEOUT_MS): RootResult =
        withContext(Dispatchers.IO) { runSuBlocking(cmd, timeoutMs) }

    // ───────────────────── Root 探测（只读） ─────────────────────

    /**
     * 探测 Root 是否可用。
     *
     * @param requestPrompt 首次引导传 true：允许等待用户在授权弹窗上操作（30s）。
     *   之后一律 false，用短超时，避免每次检查都卡半分钟。
     */
    suspend fun probeRoot(requestPrompt: Boolean): Boolean {
        if (!requestPrompt) rootGranted?.let { return it }
        val timeout = if (requestPrompt) ROOT_PROMPT_TIMEOUT_MS else ROOT_CACHED_TIMEOUT_MS
        val result = runSu(CMD_GET_UID, timeout)
        val granted = result.success && result.output.trim() == "0"
        rootGranted = granted
        return granted
    }

    /** 已知结论；未探测过返回 null。 */
    val cachedRootGranted: Boolean? get() = rootGranted

    // ───────────────────── 节点读取（只读） ─────────────────────

    /**
     * 读单个整数节点；节点不存在 / 不可读 / 非整数返回 null。
     * **null 与真实的 0 是两件事**：温度读不到不能当成 0°C 去算曲线。
     */
    suspend fun readInt(path: String): Int? {
        val result = runSu("cat $path")
        if (!result.success) return null
        return result.output.trim().toIntOrNull()
    }

    /** 写整数节点，返回是否**真的写入成功**（`echo` 到只读节点会以非 0 退出）。 */
    suspend fun writeInt(path: String, value: Int): Boolean =
        runSu("echo $value > $path").success

    /**
     * PWM 占空比。原始值可能是 0–255 占空比（>100 时按比例折算成百分比），
     * 与 mi-fan-control 的 `readPwmDuty` 保持一致。
     */
    suspend fun readPwmPercent(): Int? =
        readInt(pwmDutyPath)?.let { if (it > 100) (it * 100 / 255.0).roundToInt() else it }

    /** 实时转速（rpm）。哨兵值 2 表示守护进程未更新节点，按停转处理。 */
    suspend fun readRealSpeed(): Int? {
        val raw = readInt(realSpeedPath) ?: return null
        return if (raw == 2) 0 else raw
    }

    /** 风扇硬件是否可用（`fan_support` 节点）。 */
    suspend fun isFanSupported(): Boolean = readInt(fanSupportPath) == 1

    /** 系统风扇模式；读不到返回 -1（智能）。 */
    suspend fun readFanMode(): Int =
        runSu(CMD_GET_FAN_MODE).output.trim().toIntOrNull() ?: -1

    suspend fun isSystemTakingOver(): Boolean =
        runSu(CMD_GET_COOLING_FAN_ENABLE).output.trim() == "1"

    // ───────────────────── 温度节点 ─────────────────────

    /**
     * 可选温度源，与 mi-fan-control 的 `TEMP_NODE_NAMES/CMDS` 一一对应。
     * 顺序保持一致，便于与参考实现对照。
     */
    val temperatureSources: List<TemperatureSource> = listOf(
        TemperatureSource("核心温度", "thermal_zone23", 23),
        TemperatureSource("外壳温度", "thermal_zone68", 68),
        TemperatureSource("电池温度", "dumpsys battery", null),
        TemperatureSource("充电IC温度", "thermal_zone71", 71),
        TemperatureSource("闪存温度", "thermal_zone62", 62),
        TemperatureSource("运存温度", "thermal_zone46", 46),
        TemperatureSource("大核温度", "thermal_zone13", 13),
        TemperatureSource("中核温度", "thermal_zone20", 20),
    )

    /** 默认取「核心温度」，与 mi-fan-control 默认下标 0 相同。 */
    const val DEFAULT_TEMPERATURE_SOURCE = 0

    data class TemperatureSource(
        val label: String,
        val nodeName: String,
        val zoneIndex: Int?,
    )

    /** 温度读取命令片段（供批量读取拼接；不包含 `su` 前缀）。 */
    fun temperatureReadCommand(sourceIndex: Int): String {
        val source = temperatureSources.getOrElse(sourceIndex) { temperatureSources[0] }
        return if (source.zoneIndex != null) {
            "cat /sys/class/thermal/thermal_zone${source.zoneIndex}/temp"
        } else {
            "/system/bin/dumpsys battery | grep temperature | awk '{printf \"%d\", \$2/10}'"
        }
    }

    /**
     * 读取温度（°C 整数）。
     * **失败返回 null**（旧实现返回 0，会让智能温控按 0°C 把转速压到最低）。
     */
    suspend fun readTemperatureC(sourceIndex: Int = DEFAULT_TEMPERATURE_SOURCE): Int? {
        val source = temperatureSources.getOrElse(sourceIndex) { temperatureSources[0] }
        val result = runSu(temperatureReadCommand(sourceIndex))
        if (!result.success) return null
        val value = result.output.trim().toIntOrNull() ?: return null
        // thermal_zone 的 temp 单位是毫摄氏度；dumpsys 已在 awk 里除过 10。
        return if (source.zoneIndex != null) value / 1000 else value
    }

    /**
     * 一次批量读取遥测。整个批次只起 **一个** `su` 进程 —— `su` 唤起是主要开销。
     * 只读：命令里没有 `echo > 节点` 之类的写入。
     */
    suspend fun readSnapshot(sourceIndex: Int = DEFAULT_TEMPERATURE_SOURCE): FanSnapshotRead {
        val result = runSu(
            "echo T:\$(${temperatureReadCommand(sourceIndex)} 2>/dev/null);" +
                "echo R:\$(cat $realSpeedPath 2>/dev/null);" +
                "echo P:\$(cat $pwmDutyPath 2>/dev/null);" +
                "echo L:\$(cat $targetLevelPath 2>/dev/null)",
        )
        return FanSnapshotRead(parseFanSnapshot(result.output), result.success)
    }
}
