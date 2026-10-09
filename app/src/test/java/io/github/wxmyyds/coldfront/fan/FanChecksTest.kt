package io.github.wxmyyds.coldfront.fan

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行检查的判定逻辑（纯 JVM，不需要设备）。
 *
 * 覆盖的是「引导页会不会撒谎」：Root 被拒、节点缺失、温度读不到时必须报失败，
 * 而不是显示成一切正常。
 */
class FanChecksTest {

    private class FakeProbe(
        var root: Boolean = true,
        var model: String = "REDMI K90 Ultra",
        var supported: Boolean = true,
        var temperature: Int? = 42,
        var speed: Int? = 1800,
    ) : FanProbe {
        val rootRequests = mutableListOf<Boolean>()

        override suspend fun rootAvailable(requestPrompt: Boolean): Boolean {
            rootRequests += requestPrompt
            return root
        }

        override suspend fun marketName(): String = model
        override suspend fun fanSupported(): Boolean = supported
        override suspend fun temperatureC(): Int? = temperature
        override suspend fun speedRpm(): Int? = speed
    }

    @Test
    fun `every check passes when root node and sensors are readable`() = runTest {
        val report = runFanChecks(FakeProbe(), notificationGranted = true, requestRootPrompt = true)

        assertTrue(report.requiredPassed)
        assertEquals(FanCheckState.PASS, report.stateOf(FanCheckId.MODEL))
        assertEquals(FanCheckState.PASS, report.stateOf(FanCheckId.ROOT))
        assertEquals(FanCheckState.PASS, report.stateOf(FanCheckId.FAN_NODE))
        assertEquals("42 °C", report.outcome(FanCheckId.TEMPERATURE)?.detail)
        assertEquals("1800 RPM", report.outcome(FanCheckId.SPEED)?.detail)
        assertEquals(6, report.totalCount)
        assertEquals(6, report.passedCount)
        assertEquals(4, report.requiredPassedCount)
    }

    @Test
    fun `missing notification is optional and never blocks required checks`() = runTest {
        val report = runFanChecks(FakeProbe(), notificationGranted = false, requestRootPrompt = true)

        // 可选且未开启必须报告为 SKIP（不是 FAIL）：UI 靠这个区分「不必修」和「必须修」。
        assertEquals(FanCheckState.SKIP, report.stateOf(FanCheckId.NOTIFICATION))
        assertTrue(report.requiredPassed)
        assertEquals(4, report.requiredPassedCount)
        assertEquals(5, report.passedCount)
    }

    @Test
    fun `denied root skips node probes instead of reporting them as failures`() = runTest {
        val report = runFanChecks(FakeProbe(root = false), notificationGranted = true, requestRootPrompt = true)

        assertFalse(report.requiredPassed)
        assertEquals(FanCheckState.FAIL, report.stateOf(FanCheckId.ROOT))
        assertEquals(FanCheckState.SKIP, report.stateOf(FanCheckId.FAN_NODE))
        assertEquals(FanCheckState.SKIP, report.stateOf(FanCheckId.TEMPERATURE))
        assertEquals(FanCheckState.SKIP, report.stateOf(FanCheckId.SPEED))
    }

    @Test
    fun `unreadable temperature fails rather than silently reading zero`() = runTest {
        val report = runFanChecks(FakeProbe(temperature = null), notificationGranted = true, requestRootPrompt = true)

        assertEquals(FanCheckState.FAIL, report.stateOf(FanCheckId.TEMPERATURE))
        assertEquals("", report.outcome(FanCheckId.TEMPERATURE)?.detail)
        assertFalse(report.requiredPassed)
    }

    @Test
    fun `an unsupported fan node fails both sensor readings`() = runTest {
        val report = runFanChecks(FakeProbe(supported = false), notificationGranted = true, requestRootPrompt = true)

        assertEquals(FanCheckState.FAIL, report.stateOf(FanCheckId.FAN_NODE))
        assertEquals(FanCheckState.FAIL, report.stateOf(FanCheckId.TEMPERATURE))
        assertEquals(FanCheckState.FAIL, report.stateOf(FanCheckId.SPEED))
        assertFalse(report.requiredPassed)
    }

    @Test
    fun `a stopped fan still counts as a successful speed read`() = runTest {
        // 转速 0 是「风扇停着」，不是「读不到」；节点可读就必须算通过。
        val report = runFanChecks(FakeProbe(speed = 0), notificationGranted = true, requestRootPrompt = true)

        assertEquals(FanCheckState.PASS, report.stateOf(FanCheckId.SPEED))
        assertEquals("0 RPM", report.outcome(FanCheckId.SPEED)?.detail)
        assertTrue(report.requiredPassed)
    }

    @Test
    fun `progress callbacks deliver every partial result in order`() = runTest {
        val snapshots = mutableListOf<List<FanCheckOutcome>>()
        val report = runFanChecks(
            probe = FakeProbe(),
            notificationGranted = true,
            requestRootPrompt = true,
        ) { snapshots += it }

        assertEquals(6, snapshots.size)
        assertTrue(snapshots.all { it.isNotEmpty() })
        assertEquals(
            listOf(
                FanCheckId.MODEL,
                FanCheckId.ROOT,
                FanCheckId.FAN_NODE,
                FanCheckId.TEMPERATURE,
                FanCheckId.SPEED,
                FanCheckId.NOTIFICATION,
            ),
            snapshots.last().map { it.id },
        )
        assertEquals(report.outcomes, snapshots.last())
    }

    @Test
    fun `the root prompt flag reaches the probe`() = runTest {
        val silent = FakeProbe()
        runFanChecks(silent, notificationGranted = true, requestRootPrompt = false)
        assertEquals(listOf(false), silent.rootRequests)

        val prompted = FakeProbe()
        runFanChecks(prompted, notificationGranted = true, requestRootPrompt = true)
        assertEquals(listOf(true), prompted.rootRequests)
    }
}

/** 批量遥测解析：区分「读到 0」和「没读到」。 */
class FanSnapshotParserTest {

    @Test
    fun `parses every marker from a complete batch`() {
        val snapshot = parseFanSnapshot("T:42000\nR:1500\nP:77\nL:4")
        assertEquals(42000, snapshot.temperatureRaw)
        assertEquals(1500, snapshot.rpmRaw)
        assertEquals(77, snapshot.pwmRaw)
        assertEquals(4, snapshot.levelRaw)
    }

    @Test
    fun `keeps unreadable fields null instead of defaulting to zero`() {
        val snapshot = parseFanSnapshot("T:\nR:1500\nP:\nL:2")
        assertEquals(null, snapshot.temperatureRaw)
        assertEquals(1500, snapshot.rpmRaw)
        assertEquals(null, snapshot.pwmRaw)
        assertEquals(2, snapshot.levelRaw)
    }

    @Test
    fun `ignores empty output and non numeric values`() {
        assertEquals(EMPTY_SNAPSHOT, parseFanSnapshot(""))
        assertEquals(EMPTY_SNAPSHOT, parseFanSnapshot("cat: /sys/...: No such file or directory"))
        assertEquals(EMPTY_SNAPSHOT, parseFanSnapshot("T:abc\nR:--"))
    }

    @Test
    fun `reads a genuine zero as zero`() {
        val snapshot = parseFanSnapshot("T:0\nR:0\nP:0\nL:0")
        assertEquals(0, snapshot.temperatureRaw)
        assertEquals(0, snapshot.rpmRaw)
        assertEquals(0, snapshot.pwmRaw)
        assertEquals(0, snapshot.levelRaw)
    }
}
