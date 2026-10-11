package io.github.wxmyyds.coldfront.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import io.github.wxmyyds.coldfront.ble.BleManagerHolder
import io.github.wxmyyds.coldfront.ble.CoolerTransport
import io.github.wxmyyds.coldfront.data.SettingsRepository
import io.github.wxmyyds.coldfront.ui.i18n.stringsFor
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴：**风扇总开关**（与首页「风扇开关」是同一个开关、同一份语义）。
 *
 * 全应用只保留这一个磁贴。它管的是"风扇转不转"，而不是"谁在管风扇"：
 * - 关：风扇完全停转（`target_level 0` + `pwm_duty 0`），并记住当前模式；
 * - 开：**恢复到关闭前的模式**（智能温控 / 满速 / 手动）。
 *
 * 开关语义与记忆逻辑的唯一真源是 [io.github.wxmyyds.coldfront.fan.FanManager.setCooling]，
 * 磁贴刻意**不自己写节点**：重复实现一份调速决策，迟早会和首页那条路径不一致
 * （上一次的 bug 就是这么来的——磁贴和首页各写各的，关的时候步调一致、开的时候不一致）。
 * 这里只负责转发点击 + 展示状态。
 *
 * ## 状态从哪来
 *
 * [FanManager] 的控制状态是内存态、且属于应用进程；磁贴只在**应用进程内**被系统绑定，
 * 所以可以直接问同一个 [BleManagerHolder] 单例，不需要读硬件、也不需要前台服务在跑。
 *
 * 磁贴**不主动拉起控制会话**：`BleManagerHolder.get()` 只是取（或惰性构造）后端实例，
 * 不会挂载、不会写节点、不会弹 Root 授权框。真正的挂载/接管发生在用户点击之后，
 * 由 [io.github.wxmyyds.coldfront.fan.FanManager] 按正常路径完成。
 */
class SystemFanTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var click: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        // 状态来自应用内单例的 StateFlow：订阅 + 兜底轮询，
        // 保证即使没有 Flow 更新（例如服务被系统杀掉）也会回落到真实状态。
        listening = scope.launch {
            while (true) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        scope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        // 连点会并发起多次控制写入，既慢又可能让两次切换乱序。
        if (click?.isActive == true) return
        click = scope.launch {
            try {
                val transport = BleManagerHolder.get(applicationContext)
                val target = !transport.state.value.coolingOn
                transport.setCooling(target)
                // setCooling 是异步的（Fire-and-forget）；等状态真正翻转再刷新磁贴，
                // 否则磁贴会先显示旧值、下一次轮询才纠正，看起来像"点了没反应"。
                awaitCoolingState(transport, target)
                if (transport.state.value.coolingOn != target) showFailure()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tile action failed", e)
                showFailure()
            } finally {
                refresh()
            }
        }
    }

    /**
     * 等状态落到 [target]。
     *
     * 不设无限等待：挂载要起 su 进程，失败了就永远等不到翻转，磁贴必须能退出并报错。
     * 超时后由调用方比对实际状态决定是否提示失败。
     */
    private suspend fun awaitCoolingState(
        transport: CoolerTransport,
        target: Boolean,
    ) {
        repeat(CLICK_TIMEOUT_STEPS) {
            if (transport.state.value.coolingOn == target) return
            delay(CLICK_POLL_MS)
        }
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val state = BleManagerHolder.get(applicationContext).state.value
        tile.state = when {
            // 未连接/未挂载时不要谎报"开着"：那会诱导用户点一个无效的磁贴。
            !state.isConnected -> Tile.STATE_UNAVAILABLE
            state.coolingOn -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = labelFor()
        tile.updateTile()
    }

    private suspend fun labelFor(): String =
        stringsFor(Locale.getDefault(), language()).tileFanMaster

    /** 语言与前台服务用同一份偏好，读失败时回落到跟随系统。 */
    private suspend fun language(): String = try {
        SettingsRepository(applicationContext).appLanguage.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        "system"
    }

    private suspend fun showFailure() {
        Toast.makeText(this, stringsFor(Locale.getDefault(), language()).tileFanFailed, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FanTile"

        /**
         * 应用内状态没有系统级变更通知，且切换要等挂载（su 进程），只能轮询。
         * 磁贴只在下拉展开时监听，2 秒一次足以跟手，又不会在后台空转。
         */
        const val REFRESH_INTERVAL_MS = 2_000L

        /** 点击后等待状态翻转：最多 3 秒（挂载最坏情况要起几个 su 进程）。 */
        const val CLICK_POLL_MS = 120L
        const val CLICK_TIMEOUT_STEPS = 25
    }
}
