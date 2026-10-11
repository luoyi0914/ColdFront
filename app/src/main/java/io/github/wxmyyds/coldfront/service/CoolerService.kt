package io.github.wxmyyds.coldfront.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.wxmyyds.coldfront.ble.BleManagerHolder
import io.github.wxmyyds.coldfront.ble.CoolerTransport
import io.github.wxmyyds.coldfront.data.ProfileRepository
import io.github.wxmyyds.coldfront.data.SettingsRepository
import io.github.wxmyyds.coldfront.data.retryStorageReads
import io.github.wxmyyds.coldfront.domain.CoolerProfile
import io.github.wxmyyds.coldfront.ui.i18n.AppStrings
import io.github.wxmyyds.coldfront.ui.i18n.stringsFor
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "cooler_service_channel"
private const val NOTIFICATION_ID = 1001
private const val TAG = "CoolerService"

/** Owns background connection intent, not the Activity or the BLE protocol implementation. */
class CoolerService : Service() {
    companion object {
        const val ACTION_START_AUTO = "io.github.wxmyyds.coldfront.START_AUTO"
        const val ACTION_STOP = "io.github.wxmyyds.coldfront.STOP"
        const val ACTION_SWITCH_TO_MANUAL = "io.github.wxmyyds.coldfront.SWITCH_TO_MANUAL"
        const val ACTION_RECONNECT = "io.github.wxmyyds.coldfront.RECONNECT"
        const val ACTION_RELEASE_TO_SYSTEM = "io.github.wxmyyds.coldfront.RELEASE_TO_SYSTEM"
        private const val EXTRA_CONTROL_ADDRESS = "control_address"
        private const val EXTRA_CONTROL_SESSION = "control_session"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val ACTION_VALIDATE_TARGET = "io.github.wxmyyds.coldfront.VALIDATE_TARGET"

        fun startForProfile(context: Context, profile: CoolerProfile) {
            start(context, Intent(context, CoolerService::class.java).apply {
                action = ACTION_START_AUTO
                putExtra(EXTRA_PROFILE_ID, profile.id)
            })
        }

        internal fun switchToManual(context: Context, target: ManualControlTarget) =
            sendControl(context, target, ACTION_SWITCH_TO_MANUAL)

        /** Stop background recovery only; the BLE mode coordinator owns the actual Boost transition. */
        internal fun stopForTarget(context: Context, target: ManualControlTarget) =
            sendControl(context, target, ACTION_STOP)

        private fun sendControl(context: Context, target: ManualControlTarget, action: String) {
            start(context, Intent(context, CoolerService::class.java).apply {
                this.action = action
                putExtra(EXTRA_CONTROL_ADDRESS, target.address)
                putExtra(EXTRA_CONTROL_SESSION, target.sessionId)
            })
        }

        fun start(context: Context, intent: Intent) {
            // 内置风扇走 root sysfs，不需要蓝牙权限，这里不再用 BLE 权限做门禁。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /** 服务实例只用于同进程投递，不导出、不跨进程共享。 */
        @Volatile private var running: CoolerService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class ServiceCommand(val intent: Intent?, val startId: Int)
    private val commands = Channel<ServiceCommand>(Channel.UNLIMITED)
    private var latestStartId = 0
    private var processingStartId = 0
    private lateinit var ble: CoolerTransport
    private lateinit var profiles: ProfileRepository
    private var target: CoolerProfile? = null
    private var activation: Job? = null
    private var acceptedStartup = false
    private var strings: AppStrings = stringsFor(Locale.getDefault())
    private var lastNotification: ServiceNotification? = null

    /**
     * 屏幕状态广播：**息屏停转**靠它决定何时挂起曲线、何时放慢轮询。
     *
     * 广播由服务持有，而不是让 `FanManager` 自己去注册：生命周期归属要清晰，
     * 也避免控制器在没有任何宿主时偷偷活着。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> ble.setScreenInteractive(true)
                Intent.ACTION_SCREEN_OFF -> ble.setScreenInteractive(false)
            }
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // 服务可能是息屏时被拉起来的（START_STICKY / 开机恢复），初始状态必须问系统，
        // 不能默认亮屏 —— 否则一开机就会把曲线当成"屏幕正亮着"跑起来。
        ble.setScreenInteractive(isInteractive())
    }

    private fun unregisterScreenReceiver() {
        runCatching { unregisterReceiver(screenReceiver) }
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    override fun onCreate() {
        super.onCreate()
        // Permission checks also belong here: exceptions from onCreate are NOT caught by
        // the receiver/tile that merely scheduled startForegroundService().
        ble = BleManagerHolder.acquire(applicationContext, this)
        profiles = ProfileRepository(applicationContext)
        createNotificationChannel()
        try {
            // Android 14+ 会校验前台服务类型对应的权限：connectedDevice 要求实际持有
            // BLUETOOTH_CONNECT/SCAN 等权限，而本应用改造为内置风扇后已不再申请蓝牙权限，
            // 于是 startForeground 抛 RemoteException（validateForegroundServiceType）。
            // 内置风扇控制改用 specialUse（API 34+）；旧版本没有这项校验，沿用 connectedDevice。
            val type = when {
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else -> 0
            }
            val waiting = ServiceNotification(strings.serviceWaitingConfig, 0, showReconnect = true)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(waiting), type)
            acceptedStartup = true
            running = this
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot enter foreground", e)
            stopSelf()
            return
        }
        observeState()
        // 注册放在 try/catch **之后**：上面提前 stopSelf() 的分支若也注册了，
        // 就会留下一个没人注销的接收器。
        registerScreenReceiver()
        scope.launch {
            for (command in commands) {
                processingStartId = command.startId
                try {
                    handleCommand(command.intent)
                } catch (e: IOException) {
                    Log.e(TAG, "Cannot read/save service intent", e)
                    // Do not proceed with an unpersisted user request or write defaults over
                    // unread data. In particular a failed stop persistence is logged explicitly.
                    stopSelfSafely()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!acceptedStartup) return START_NOT_STICKY
        latestStartId = startId
        commands.trySend(ServiceCommand(intent, startId))
        return START_STICKY
    }

    private suspend fun handleCommand(intent: Intent?) {
        when (intent?.action) {
            ACTION_START_AUTO -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run { stopSelfSafely(); return }
                profiles.setServiceProfile(id)
                val profile = profiles.loadServiceProfile() ?: run { stopSelfSafely(); return }
                if (!profile.deviceType.supportsAutoMode) {
                    profiles.setServiceProfile(null)
                    stopSelfSafely()
                    return
                }
                activate(profile)
            }
            ACTION_STOP, ACTION_SWITCH_TO_MANUAL -> {
                val controlTarget = if (intent.hasExtra(EXTRA_CONTROL_SESSION)) {
                    ManualControlTarget(
                        intent.getStringExtra(EXTRA_CONTROL_ADDRESS).orEmpty(),
                        intent.getLongExtra(EXTRA_CONTROL_SESSION, -1L),
                    )
                } else null // Notification actions intentionally address the service's current target.
                if (controlTarget != null && !controlTarget.matches(ble.state.value)) {
                    // A delayed UI/tile action must not clear a newer service target or control
                    // a replacement connection, including a reconnect to the very same address.
                    if (target == null) stopSelfSafely()
                    return
                }
                // Persist explicit opt-out before shutdown, so boot/sticky restart cannot
                // silently re-enable the device. Notification Close keeps device mode intact.
                val intendedAddress = intent.getStringExtra(EXTRA_CONTROL_ADDRESS)
                    ?: target?.macAddress ?: profiles.loadServiceProfile()?.macAddress
                stopping = true
                activation?.cancel()
                ble.clearLinkLoss()
                profiles.setServiceProfile(null)
                if (intent.action == ACTION_SWITCH_TO_MANUAL) {
                    val state = ble.state.value
                    val sent = intendedAddress != null && state.isConnected &&
                        intendedAddress.equals(state.deviceAddress, ignoreCase = true) &&
                        (controlTarget == null || controlTarget.matches(state)) &&
                        ble.setSmartAndAwait(false)
                    if (!sent) showControlFailure()
                }
                stopSelfSafely()
            }
            ACTION_RELEASE_TO_SYSTEM -> {
                val released = ble.releaseToSystem()
                if (!released) Log.e(TAG, "开机安全网：交还系统失败")
                ble.release()
                stopSelfSafely()
            }
            ACTION_RECONNECT, null -> {
                // START_STICKY delivers a null Intent: recover only an explicit saved target.
                val profile = profiles.loadServiceProfile() ?: run { stopSelfSafely(); return }
                activate(profile)
            }
            ACTION_VALIDATE_TARGET -> {
                // The flow emission may precede a newer command; re-read before acting.
                if (profiles.loadServiceProfile() == null) stopSelfSafely()
            }
            else -> stopSelfSafely()
        }
    }

    private fun activate(profile: CoolerProfile) {
        // The service owns this connection intent; a stale UI link-loss record must not
        // outlive it and resume a second session after the service already connected.
        ble.clearLinkLoss()
        stopping = false
        activation?.cancel()
        target = profile
        activationFailed = false
        val current = ble.state.value
        if (serviceNeedsConnection(profile, current)) {
            ble.connectByAddress(profile.macAddress, profile.deviceType)
        }
        activation = scope.launch {
            ble.state.filter { it.isConnected && profile.matches(it) }
                .distinctUntilChangedBy { it.connectionSessionId }
                .collect {
                    // CONNECTED means initialization is done. No optimistic smart=true before
                    // characteristics exist; the manager acknowledges the actual write.
                    activationFailed = !ble.setSmartAndAwait(true)
                    if (activationFailed) showControlFailure()
                    // A successful retry need not change BLE StateFlow (smart may already
                    // be true), so refresh text and actions explicitly on both outcomes.
                    updateNotification()
                }
        }
    }

    private fun observeState() {
        scope.launch {
            combine(
                ble.state,
                SettingsRepository(applicationContext).appLanguage.recoverNotificationLanguage { cause ->
                    Log.e(TAG, "Cannot read notification language", cause)
                },
            ) { state, language -> state to stringsFor(Locale.getDefault(), language) }
                .collect { (state, localized) ->
                    if (strings !== localized) {
                        strings = localized
                        lastNotification = null
                        createNotificationChannel()
                    }
                    updateNotification(serviceNotification(state, strings, activationFailed))
                }
        }
        scope.launch {
            profiles.observeServiceProfile { cause ->
                Log.e(TAG, "Cannot decode service target", cause)
            }.retryStorageReads { cause ->
                Log.e(TAG, "Cannot observe service target", cause)
            }.collectTargetInvalidations(latestStartId = { latestStartId }) { startId ->
                // Never discard deletion while target is still being loaded. The queued
                // validator runs after startup/manual commands and re-reads persisted intent.
                commands.trySend(ServiceCommand(Intent().setAction(ACTION_VALIDATE_TARGET), startId))
            }
        }
    }

    private var stopping = false
    private var activationFailed = false

    private fun showControlFailure() {
        android.widget.Toast.makeText(this, strings.serviceControlFailed, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun stopSelfSafely() {
        stopping = true
        activation?.cancel()
        target = null
        if (processingStartId != latestStartId) {
            // A newer START is queued; removing foreground state or stopping here would
            // discard it. The consumer stays alive and processes the newer start next.
            stopping = false
            return
        }
        if (stopSelfResult(processingStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (running === this) running = null
        unregisterScreenReceiver()
        commands.close()
        scope.cancel()
        BleManagerHolder.release(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, strings.serviceChannelName, NotificationManager.IMPORTANCE_LOW)
                .apply { description = strings.serviceChannelDesc }
        )
    }

    private fun buildNotification(value: ServiceNotification): Notification {
        val main = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(strings.appName)
            .setContentText("${value.text} · ${value.fanPercent}%")
            .setContentIntent(main)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, strings.close, actionIntent(ACTION_STOP))
            .addAction(android.R.drawable.ic_menu_edit, strings.serviceSwitchManual, actionIntent(ACTION_SWITCH_TO_MANUAL))
            .apply {
                if (value.showReconnect) {
                    addAction(android.R.drawable.ic_menu_rotate, strings.serviceReconnect, actionIntent(ACTION_RECONNECT))
                }
            }
            .build()
    }

    private fun updateNotification(
        value: ServiceNotification = serviceNotification(ble.state.value, strings, activationFailed),
    ) {
        if (stopping || !acceptedStartup) return
        if (value == lastNotification) return
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(value))
            lastNotification = value
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission changed", e)
        }
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, CoolerService::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

}
