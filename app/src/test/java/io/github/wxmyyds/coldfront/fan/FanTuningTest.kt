package io.github.wxmyyds.coldfront.fan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanTuningTest {
    @Test
    fun `defaults are valid and direct by default`() {
        assertTrue(FanTuning.DEFAULT_POLL_INTERVAL_MS in FanTuning.POLL_INTERVAL_RANGE)
        assertTrue(FanTuning.DEFAULT_TRANSITION_MS in FanTuning.TRANSITION_RANGE)
        assertEquals(1000, FanTuning.DEFAULT_POLL_INTERVAL_MS)
        assertEquals(0, FanTuning.DEFAULT_TRANSITION_MS)
    }

    @Test
    fun `custom intervals normalize safely`() {
        assertEquals(FanTuning.DEFAULT_POLL_INTERVAL_MS, FanTuning.pollIntervalOrDefault(null))
        assertEquals(FanTuning.DEFAULT_POLL_INTERVAL_MS, FanTuning.pollIntervalOrDefault(FanTuning.MIN_POLL_INTERVAL_MS - 1))
        assertEquals(FanTuning.DEFAULT_POLL_INTERVAL_MS, FanTuning.pollIntervalOrDefault(FanTuning.MAX_POLL_INTERVAL_MS + 1))
        assertEquals(1234, FanTuning.pollIntervalOrDefault(1234))
    }

    @Test
    fun `custom transition normalizes safely`() {
        assertEquals(FanTuning.DEFAULT_TRANSITION_MS, FanTuning.transitionMsOrDefault(null))
        assertEquals(0, FanTuning.transitionMsOrDefault(0))
        assertEquals(2500, FanTuning.transitionMsOrDefault(2500))
        assertEquals(FanTuning.DEFAULT_TRANSITION_MS, FanTuning.transitionMsOrDefault(-1))
        assertEquals(FanTuning.DEFAULT_TRANSITION_MS, FanTuning.transitionMsOrDefault(FanTuning.MAX_TRANSITION_MS + 1))
    }

    @Test
    fun `zero transition jumps directly to target`() {
        assertEquals(99, FanTuning.rampPwm(40, 99, 0, 0))
        assertEquals(99, FanTuning.rampPwm(40, 99, 500, 0))
    }

    @Test
    fun `positive transition is linear and supports falling`() {
        assertEquals(40, FanTuning.rampPwm(40, 98, 0, 1000))
        assertEquals(69, FanTuning.rampPwm(40, 98, 500, 1000))
        assertEquals(98, FanTuning.rampPwm(40, 98, 1000, 1000))
        assertEquals(69, FanTuning.rampPwm(98, 40, 500, 1000))
    }

    @Test
    fun `ramp boundaries and monotonicity hold in both directions`() {
        assertEquals(30, FanTuning.rampPwm(30, 99, -1, 2500))
        assertEquals(99, FanTuning.rampPwm(30, 99, 3000, 2500))
        assertEquals(30, FanTuning.rampPwm(99, 30, 0, 0))
        assertEquals(55, FanTuning.rampPwm(55, 55, 1234, 2500))
        val rising = (0L..2500L step 10).map { FanTuning.rampPwm(30, 99, it, 2500) }
        val falling = (0L..2500L step 10).map { FanTuning.rampPwm(99, 30, it, 2500) }
        assertTrue(rising.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(falling.zipWithNext().all { (a, b) -> a >= b })
    }

    @Test
    fun `screen off interval is never shorter than active interval`() {
        listOf(1, 200, 1000, 5000, 30_000, 120_000).forEach { active ->
            assertTrue(FanTuning.screenOffPollInterval(active) >= active)
        }
        assertEquals(FanTuning.SCREEN_OFF_POLL_INTERVAL_MS, FanTuning.screenOffPollInterval(1000))
    }
}
