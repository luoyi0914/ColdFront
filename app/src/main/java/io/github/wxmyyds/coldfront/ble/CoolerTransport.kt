package io.github.wxmyyds.coldfront.ble

import io.github.wxmyyds.coldfront.domain.CoolerDevice
import io.github.wxmyyds.coldfront.domain.CoolerDeviceType
import io.github.wxmyyds.coldfront.domain.CoolerLiveState
import io.github.wxmyyds.coldfront.domain.RGBConfig
import io.github.wxmyyds.coldfront.domain.RgbWriteState
import kotlinx.coroutines.flow.StateFlow

/**
 * 控制后端契约 —— UI / Service 层只依赖这一层，不关心底下走 BLE 还是 sysfs。
 *
 * 上游实现是 [CoolerBleManager]（红魔散热器 GATT 协议）。本 fork 改为驱动手机内置风扇，
 * 实现在 [io.github.wxmyyds.coldfront.fan.FanManager]：通过 `su` 读写
 * `/sys/devices/platform/soc/soc:xiaomi_fan/{pwm_duty,target_level,real_speed}`
 * 与 `settings get/put system {cooling_fan_enable,fan_mode}`。
 *
 * 方法签名与 [CoolerBleManager] 的公开成员逐一对应，因此 UI 层保持原样。
 */
interface CoolerTransport : BackgroundLinkLossStore {
    val state: StateFlow<CoolerLiveState>
    val rgbWriteState: StateFlow<RgbWriteState?>
    val discoveredDevices: StateFlow<List<CoolerDevice>>
    val rawDevices: StateFlow<List<BleScanDiagnostic>>
    val scanState: StateFlow<ScanState>
    val isBluetoothEnabled: Boolean

    fun refreshScanConditions()
    fun startScan()
    fun stopScan()
    fun connect(device: CoolerDevice)
    fun connectRaw(entry: BleScanDiagnostic, type: CoolerDeviceType)
    fun connectByAddress(macAddress: String, type: CoolerDeviceType)

    /**
     * 只读挂载控制后端（不做任何节点写入）。
     *
     * 内置风扇实现里这是「探测 Root 并确认风扇节点可读」；上游 BLE 实现没有这一概念。
     * @param requestPrompt 是否允许等待用户确认 Root 授权弹窗。
     */
    fun activate(requestPrompt: Boolean)
    fun hasRunningSession(): Boolean
    fun disconnect()

    fun setFanSpeed(percent: Int)
    fun setCooling(on: Boolean)
    fun setSmart(on: Boolean)
    suspend fun setSmartAndAwait(on: Boolean): Boolean
    fun setBoost(on: Boolean)
    fun setOvercoldProtection(on: Boolean)
    fun setRGB(config: RGBConfig)

    /**
     * 告知控制后端屏幕是否点亮。
     *
     * 目前只有内置风扇用得到：「息屏停转」需要在息屏时挂起曲线并放慢轮询。
     * 屏幕广播由**前台服务**持有（生命周期归属清晰），再转告后端，
     * 而不是让后端自己去注册广播。
     *
     * 默认空实现：上游 BLE 散热器没有这条策略。
     */
    fun setScreenInteractive(interactive: Boolean) = Unit

    /** Best-effort safety cleanup after boot when screen-off fan stopping was enabled. */
    suspend fun releaseToSystem(): Boolean = false

    fun release()
}
