package io.github.wxmyyds.coldfront.service

import io.github.wxmyyds.coldfront.domain.ConnectionState
import io.github.wxmyyds.coldfront.domain.CoolerLiveState
import io.github.wxmyyds.coldfront.domain.CoolerProfile
import io.github.wxmyyds.coldfront.ui.i18n.AppStrings
import kotlinx.coroutines.flow.Flow

/**
 * Queue invalidations even while a start command is still loading its target. Validation is
 * serialized behind that command, so it sees the latest persisted intent rather than a stale
 * flow emission. The initial empty snapshot before the first start must not stop the service.
 */
internal suspend fun Flow<CoolerProfile?>.collectTargetInvalidations(
    latestStartId: () -> Int,
    enqueueValidation: (Int) -> Unit,
) {
    collect { saved ->
        val startId = latestStartId()
        if (saved == null && startId > 0) enqueueValidation(startId)
    }
}

/** Identity captured by UI/tile actions before storage or Android's service queue can suspend them. */
internal data class ManualControlTarget(val address: String, val sessionId: Long) {
    fun matches(state: CoolerLiveState): Boolean = state.isConnected &&
        sessionId == state.connectionSessionId && address.equals(state.deviceAddress, ignoreCase = true)

    companion object {
        fun from(state: CoolerLiveState): ManualControlTarget? =
            if (state.isConnected) state.deviceAddress?.let { ManualControlTarget(it, state.connectionSessionId) }
            else null
    }
}

/** A tile click must not replace a newer connection or fall back to a different saved device. */
internal fun tileStartProfile(
    requested: CoolerLiveState,
    current: CoolerLiveState,
    active: CoolerProfile?,
): CoolerProfile? {
    if (active == null || !active.deviceType.supportsAutoMode) return null
    if (requested.connectionSessionId != current.connectionSessionId ||
        requested.connection != current.connection ||
        requested.deviceAddress != current.deviceAddress ||
        requested.deviceType != current.deviceType ||
        requested.smartOn != current.smartOn
    ) return null
    val running = current.connection in setOf(
        ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.DISCOVERING,
    )
    if (running && (!active.macAddress.equals(current.deviceAddress, ignoreCase = true) ||
            active.deviceType != current.deviceType)
    ) return null
    return active
}

/** Explicit activation/reconnect must replace a quarantined GATT, even if still CONNECTED. */
internal fun serviceNeedsConnection(profile: CoolerProfile, state: CoolerLiveState): Boolean =
    state.telemetryDegraded || !profile.matches(state) || state.connection !in setOf(
        ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.DISCOVERING,
    )

internal fun CoolerProfile.matches(state: CoolerLiveState): Boolean =
    macAddress.equals(state.deviceAddress, ignoreCase = true) && deviceType == state.deviceType

/** Every visible notification field, including actions, participates in deduplication. */
internal data class ServiceNotification(
    val text: String,
    val fanPercent: Int,
    val showReconnect: Boolean,
)

internal fun serviceNotification(
    state: CoolerLiveState,
    strings: AppStrings,
    activationFailed: Boolean,
): ServiceNotification = ServiceNotification(
    text = when {
        state.isConnected && activationFailed -> strings.serviceControlFailed
        state.isConnected && state.controlError -> strings.homeControlFailedTitle
        state.isConnected && state.telemetryDegraded -> strings.telemetryDegradedTitle
        state.isConnected && state.smartOn -> strings.serviceAutoOn
        state.isConnected -> strings.serviceManual
        state.connection == ConnectionState.CONNECTING ||
            state.connection == ConnectionState.DISCOVERING -> strings.homeConnecting
        state.connection == ConnectionState.FAILED -> strings.homeFailed
        else -> strings.serviceReconnect
    },
    fanPercent = state.fanPercent,
    showReconnect = !state.isConnected || activationFailed || state.telemetryDegraded || state.controlError,
)
