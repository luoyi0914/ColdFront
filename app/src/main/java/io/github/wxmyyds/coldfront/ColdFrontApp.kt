package io.github.wxmyyds.coldfront

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.wxmyyds.coldfront.ble.BleManagerHolder

/**
 * 进程级宿主：只负责**屏幕亮灭监听**。
 *
 * 为什么不能把这套监听继续留在 `CoolerService` 里：
 * 息屏停转真正需要的只是「屏幕灭了 → 告诉 [FanManager] 挂起曲线」，
 * 而 `CoolerService` 只在两条路径上启动 —— 开机自启（[service.BootReceiver]，
 * 且要求存在激活的自动档案）和手动切换（[ui.CoolerViewModel] 的
 * `switchToManual`/`stopForTarget`）。用户从界面打开「智能温控」走的是
 * `ble.setSmart(true)`，**根本不启动服务**，于是服务里注册的接收器不存在，
 * 息屏停转静默失效、亮屏也不会恢复（实测 dumpsys 里 CoolerService 为 nothing）。
 *
 * 放在 Application 上则与「应用进程还活着」等价：界面开着、或服务在跑，
 * 监听都在；两者都不在时进程已死，本就没有曲线需要挂起。
 *
 * 接收器仍**不导出**（RECEIVER_NOT_EXPORTED），屏幕亮灭属于受保护广播，
 * 只有系统能发。
 */
class ColdFrontApp : Application() {

    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON ->
                        BleManagerHolder.get(this@ColdFrontApp).setScreenInteractive(true)
                    Intent.ACTION_SCREEN_OFF ->
                        BleManagerHolder.get(this@ColdFrontApp).setScreenInteractive(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 初始状态必须问系统：进程可能是息屏时被拉起来的（服务被 START_STICKY 重启、
        // 或用户在息屏状态下点快捷磁贴），默认成「亮屏」会让曲线以为屏幕正开着。
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        BleManagerHolder.get(this).setScreenInteractive(interactive)
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiver = receiver
        }.onFailure {
            // 注册失败不该拖垮整个进程（本应用的其他功能仍可用），但必须留痕。
            Log.e(TAG, "Cannot register screen receiver", it)
        }
        Log.i(TAG, "screen receiver registered, interactive=$interactive")
    }

    private companion object {
        const val TAG = "ColdFrontApp"
    }
}
