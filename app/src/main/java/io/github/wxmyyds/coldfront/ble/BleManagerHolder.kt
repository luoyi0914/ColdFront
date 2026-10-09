package io.github.wxmyyds.coldfront.ble

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import io.github.wxmyyds.coldfront.fan.FanManager

/**
 * One transport shared by explicit Activity/ViewModel and foreground-service owners.
 *
 * 上游这里构造 [CoolerBleManager]（红魔散热器 BLE）。本 fork 的控制对象是
 * K90 Ultra/Max 的内置风扇，改为构造 [FanManager]（root sysfs），
 * 但对外仍暴露同一个 [CoolerTransport] 契约，UI 层无需改动。
 */
object BleManagerHolder {
    // The singleton receives applicationContext only; never an Activity or Service context.
    private var instance: CoolerTransport? = null
    private val owners = mutableSetOf<Any>()

    @MainThread
    fun get(context: Context): CoolerTransport {
        checkMainThread()
        return instance ?: FanManager(context.applicationContext).also { instance = it }
    }

    @MainThread
    fun acquire(context: Context, owner: Any): CoolerTransport {
        checkMainThread()
        owners.add(owner)
        return get(context)
    }

    @MainThread
    fun release(owner: Any) {
        checkMainThread()
        if (owners.remove(owner) && owners.isEmpty()) instance?.release()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "BleManagerHolder must be accessed on the main thread"
        }
    }
}
