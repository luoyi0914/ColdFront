package io.github.wxmyyds.coldfront.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.wxmyyds.coldfront.data.ProfileRepository
import io.github.wxmyyds.coldfront.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "BootReceiver"

/**
 * 开机自启：若存在激活的自动模式档案，恢复前台服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // If the screen-off stop feature was explicitly enabled, reboot may have
                // preserved a stopped fan while the app process was killed. Restore system
                // control before attempting any optional automatic-mode recovery.
                val screenOffStopEnabled = withTimeout(8_000) {
                    SettingsRepository(context).settings.first().screenOffStopFan
                }
                if (screenOffStopEnabled) {
                    CoolerService.start(context, Intent(context, CoolerService::class.java).apply {
                        action = CoolerService.ACTION_RELEASE_TO_SYSTEM
                    })
                    Log.i(TAG, "开机安全网：请求交还风扇控制权")
                }

                // An active device alone is not consent to start background auto mode.
                val profile = withTimeout(8_000) { ProfileRepository(context).loadServiceProfile() }
                if (profile != null) {
                    // Recovery is not a fresh opt-in: the service rechecks current persisted intent.
                    CoolerService.start(context, Intent(context, CoolerService::class.java).apply {
                        action = CoolerService.ACTION_RECONNECT
                    })
                    Log.i(TAG, "开机恢复自动模式：${profile.displayName}")
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "开机恢复取消或超时", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "开机恢复失败: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
