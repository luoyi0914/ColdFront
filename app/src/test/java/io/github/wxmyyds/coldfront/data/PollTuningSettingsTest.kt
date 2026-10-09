package io.github.wxmyyds.coldfront.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.wxmyyds.coldfront.fan.FanTuning
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 自定义轮询间隔与升降过渡时间的持久化行为。 */
class PollTuningSettingsTest {
    private val intervalKey = intPreferencesKey("poll_interval_ms")
    private val transitionKey = intPreferencesKey("fan_transition_ms")

    private fun repositoryWith(interval: Int? = null, transition: Int? = null) = SettingsRepository(
        TestPreferencesStore(
            mutablePreferencesOf().apply {
                interval?.let { this[intervalKey] = it }
                transition?.let { this[transitionKey] = it }
            }.toPreferences(),
        ),
    )

    @Test
    fun `defaults are one second and direct`() = runTest {
        val settings = repositoryWith().settings.first()
        assertEquals(FanTuning.DEFAULT_POLL_INTERVAL_MS, settings.pollIntervalMs)
        assertEquals(FanTuning.DEFAULT_TRANSITION_MS, settings.fanTransitionMs)
    }

    @Test
    fun `custom values round trip`() = runTest {
        val repository = repositoryWith()
        repository.setPollInterval(1234)
        repository.setFanTransitionMs(2500)
        val settings = repository.settings.first()
        assertEquals(1234, settings.pollIntervalMs)
        assertEquals(2500, settings.fanTransitionMs)
    }

    @Test
    fun `invalid values are normalized before reaching the store`() = runTest {
        val store = TestPreferencesStore()
        val repository = SettingsRepository(store)
        repository.setPollInterval(FanTuning.MIN_POLL_INTERVAL_MS - 1)
        repository.setFanTransitionMs(FanTuning.MAX_TRANSITION_MS + 1)
        assertEquals(FanTuning.DEFAULT_POLL_INTERVAL_MS, store.snapshot[intervalKey])
        assertEquals(FanTuning.DEFAULT_TRANSITION_MS, store.snapshot[transitionKey])
    }

    @Test
    fun `out of range stored values fall back on read`() = runTest {
        val settings = repositoryWith(interval = 0, transition = -1).settings.first()
        assertEquals(FanTuning.DEFAULT_POLL_INTERVAL_MS, settings.pollIntervalMs)
        assertEquals(FanTuning.DEFAULT_TRANSITION_MS, settings.fanTransitionMs)
    }

    @Test
    fun `tuning changes no other stored preference`() = runTest {
        val repository = repositoryWith()
        repository.setOnboardingCompleted(true)
        repository.setScreenOffStopFan(true)
        repository.setPollInterval(1000)
        repository.setFanTransitionMs(0)
        val settings = repository.settings.first()
        assertEquals(true, settings.onboardingCompleted)
        assertEquals(true, settings.screenOffStopFan)
        assertEquals(1000, settings.pollIntervalMs)
        assertEquals(0, settings.fanTransitionMs)
    }
}
