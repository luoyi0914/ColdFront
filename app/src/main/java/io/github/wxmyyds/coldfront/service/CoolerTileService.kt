package io.github.wxmyyds.coldfront.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import io.github.wxmyyds.coldfront.ble.BleManagerHolder
import io.github.wxmyyds.coldfront.data.ProfileRepository
import io.github.wxmyyds.coldfront.data.SettingsRepository
import io.github.wxmyyds.coldfront.ui.i18n.stringsFor
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Quick Settings auto-mode toggle; state follows the service, not an optimistic local flag. */
class CoolerTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Tile callbacks and this scope both run on Main, matching the holder's checked contract.
    private val ble by lazy(LazyThreadSafetyMode.NONE) { BleManagerHolder.get(applicationContext) }
    private var listening: Job? = null
    private var click: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { ble.state.collect { refresh() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (click?.isActive == true) return
        click = scope.launch {
            try {
                val current = ble.state.value
                if (current.isConnected && current.smartOn) {
                    // Unlike notification Close, this is advertised as auto-mode OFF.
                    val target = ManualControlTarget.from(current) ?: return@launch
                    CoolerService.switchToManual(this@CoolerTileService, target)
                } else {
                    val active = ProfileRepository(applicationContext).loadActiveProfile()
                    // Reading storage suspends: both the selected device and the connection
                    // session must still belong to this click when the result arrives.
                    val profile = tileStartProfile(current, ble.state.value, active)
                    if (profile == null) {
                        showUnavailable()
                        return@launch
                    }
                    CoolerService.startForProfile(this@CoolerTileService, profile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CoolerTile", "Tile action failed", e)
                showUnavailable()
            } finally {
                refresh()
            }
        }
    }

    private suspend fun showUnavailable() {
        val language = try {
            SettingsRepository(applicationContext).appLanguage.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "system"
        }
        Toast.makeText(this, stringsFor(Locale.getDefault(), language).serviceUnavailable, Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        qsTile?.apply {
            state = when {
                ble.state.value.isConnected && ble.state.value.smartOn -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = "ColdFront"
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
