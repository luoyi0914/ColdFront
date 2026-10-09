package io.github.wxmyyds.coldfront.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.wxmyyds.coldfront.ble.BackgroundResume
import io.github.wxmyyds.coldfront.ble.BleManagerHolder
import io.github.wxmyyds.coldfront.data.AppSettings
import io.github.wxmyyds.coldfront.data.ProfileRepository
import io.github.wxmyyds.coldfront.data.SettingsRepository
import io.github.wxmyyds.coldfront.fan.FanCheckOutcome
import io.github.wxmyyds.coldfront.fan.FanCheckReport
import io.github.wxmyyds.coldfront.fan.FanCurve
import io.github.wxmyyds.coldfront.fan.RootFanProbe
import io.github.wxmyyds.coldfront.fan.runFanChecks
import io.github.wxmyyds.coldfront.service.CoolerService
import io.github.wxmyyds.coldfront.service.ManualControlTarget
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state bridge. Transport lifetime and successful-connection bookkeeping have explicit owners. */
class CoolerViewModel(app: Application) : AndroidViewModel(app) {
    private val ble = BleManagerHolder.acquire(app, this)
    private val profileRepo = ProfileRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val storageErrors = Channel<Unit>(Channel.CONFLATED)
    private val profileMutations = ProfileMutationCoordinator(
        scope = viewModelScope,
        record = { profileRepo.recordConnection(it) },
        delete = profileRepo::delete,
    )
    val errors: Flow<Unit> = storageErrors.receiveAsFlow()

    // A transient read failure keeps the last good value and retries, rather than replacing
    // persisted data with defaults or permanently killing an eagerly collected StateFlow.
    private fun <T> Flow<T>.uiState(initial: T): StateFlow<T> =
        storageStateIn(viewModelScope, initial, ::reportStorageError)

    val liveState = ble.state

    private val backgroundResume = BackgroundResume(
        lifecycle = ProcessLifecycleOwner.get().lifecycle,
        scope = viewModelScope,
        store = ble,
        hasRunningSession = ble::hasRunningSession,
        connect = ble::connectByAddress,
    )
    // null means no successful disk read yet. Readiness and all settings move atomically.
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.uiState<AppSettings?>(null)

    // ───────────────────── 运行检查（只读） ─────────────────────

    private val _checks = MutableStateFlow<FanCheckReport?>(null)
    val checks: StateFlow<FanCheckReport?> = _checks.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var checkJob: Job? = null

    init {
        backgroundResume.attach()
        // 只读挂载：探测 Root 与风扇节点并开始读遥测。**不写任何节点**，
        // 所以打开应用本身不会改变系统风扇策略（接管发生在用户第一次操作风扇时）。
        ble.activate(requestPrompt = false)
        viewModelScope.launch {
            liveState.filter { it.isConnected }
                .distinctUntilChangedBy { it.connectionSessionId }
                .collect { state ->
                    // All connection entry points share this commit-on-success path.
                    try {
                        profileMutations.recordConnection(state)
                    } catch (e: IOException) {
                        reportStorageError(e)
                    }
                }
        }
    }

    /**
     * 运行一次只读检查并逐项发布结果。
     *
     * @param notificationGranted 通知权限是可选项，由 UI 层查询后传入。
     */
    fun runChecks(notificationGranted: Boolean) {
        checkJob?.cancel()
        _checks.value = null
        _checking.value = true
        checkJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runFanChecks(
                        probe = RootFanProbe(),
                        notificationGranted = notificationGranted,
                        requestRootPrompt = true,
                    ) { partial: List<FanCheckOutcome> ->
                        _checks.value = FanCheckReport(partial)
                    }
                }
            } finally {
                _checking.value = false
            }
        }
    }

    /** 用户显式启用控制：允许弹出 Root 授权。仍然只做挂载，接管等第一次控制写入。 */
    fun activateControl() = ble.activate(requestPrompt = true)

    fun completeOnboarding() = saveSetting { settingsRepo.setOnboardingCompleted(true) }

    private fun reportStorageError(error: IOException) {
        Log.e("CoolerViewModel", "Storage operation failed", error)
        storageErrors.trySend(Unit)
    }

    private fun saveSetting(action: suspend () -> Unit) = viewModelScope.launch {
        try {
            action()
        } catch (e: IOException) {
            reportStorageError(e)
        }
    }

    fun setDynamicColor(enabled: Boolean) = saveSetting { settingsRepo.setDynamicColor(enabled) }
    fun setDarkMode(mode: String) = saveSetting { settingsRepo.setDarkMode(mode) }
    fun setPalette(palette: String) = saveSetting { settingsRepo.setPalette(palette) }
    fun setPredictiveBack(enabled: Boolean) = saveSetting { settingsRepo.setPredictiveBack(enabled) }
    fun setAppLanguage(language: String) = saveSetting { settingsRepo.setAppLanguage(language) }

    // ───────────────────── 温控曲线 / 温度源 / 息屏停转 ─────────────────────

    /**
     * 保存曲线。**只写持久化，不碰任何硬件节点** —— FanManager 订阅设置后会自行生效，
     * 因此"编辑曲线"这件事本身永远不会改风扇状态。
     */
    fun setSmartCurve(curve: FanCurve) = saveSetting { settingsRepo.setSmartCurve(curve) }

    /** 存储里的曲线无法解析时，清掉它让读取回到"从未设置"，从而消除损坏提示。 */
    fun repairSmartCurve() = saveSetting { settingsRepo.repairSmartCurve() }

    fun setTemperatureSource(index: Int) = saveSetting { settingsRepo.setTemperatureSource(index) }

    fun setScreenOffStopFan(enabled: Boolean) = saveSetting { settingsRepo.setScreenOffStopFan(enabled) }

    /**
     * 轮询间隔。与 [setFanTransitionMs] 一样**只写持久化**：`FanManager` 订阅设置后，
     * 轮询循环下一轮就会用新值，不需要重启服务，也不会立刻改风扇状态。
     */
    fun setPollInterval(ms: Int) = saveSetting { settingsRepo.setPollInterval(ms) }

    /** 升降过渡时间（毫秒）。0 = 目标一变就写到位；调大则在设定时间内线性走完。 */
    fun setFanTransitionMs(ms: Int) = saveSetting { settingsRepo.setFanTransitionMs(ms) }

    fun setFanSpeed(percent: Int) = ble.setFanSpeed(percent)
    fun setCooling(on: Boolean) = ble.setCooling(on)

    fun setSmart(on: Boolean) {
        if (on) {
            ble.setSmart(true)
            return
        }
        stopAuto(switchToManual = true)
    }

    /** Cancel same-device background recovery; the transport owns the mutually exclusive mode writes. */
    private fun stopAuto(switchToManual: Boolean) {
        val requested = ManualControlTarget.from(liveState.value) ?: return
        saveSetting {
            val target = profileRepo.loadServiceProfile()
            if (!requested.matches(liveState.value)) return@saveSetting
            if (target != null && target.macAddress.equals(requested.address, ignoreCase = true)) {
                // Service owns background opt-out and validates this session again on delivery.
                if (switchToManual) CoolerService.switchToManual(getApplication(), requested)
                else CoolerService.stopForTarget(getApplication(), requested)
            } else if (switchToManual) {
                ble.setSmart(false)
            }
        }
    }

    fun setBoost(on: Boolean) {
        if (on && !liveState.value.powerLimited) stopAuto(switchToManual = false)
        // Do not enqueue a delayed Smart OFF here: it could supersede the Boost transition.
        ble.setBoost(on)
    }

    override fun onCleared() {
        checkJob?.cancel()
        profileMutations.close()
        backgroundResume.detach()
        BleManagerHolder.release(this)
        storageErrors.close()
    }
}
