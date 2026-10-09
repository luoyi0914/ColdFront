package io.github.wxmyyds.coldfront.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.wxmyyds.coldfront.fan.CurveLoad
import io.github.wxmyyds.coldfront.fan.CurvePreset
import io.github.wxmyyds.coldfront.fan.FanCurve
import io.github.wxmyyds.coldfront.fan.FanCurveJson
import io.github.wxmyyds.coldfront.fan.FanNodes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线 / 温度源 / 息屏停转三个新键的持久化行为。
 *
 * 最要紧的一条是**「没有曲线」与「曲线坏了」必须可区分**：前者是首次运行的正常状态，
 * 静默取默认即可；后者意味着数据出了问题，界面必须告知用户。把两者混成一句
 * "没有曲线"，用户就永远不知道自己画的线被丢掉了。
 */
class SmartCurveSettingsTest {

    private val curveKey = stringPreferencesKey("smart_curve_json")
    private val sourceKey = intPreferencesKey("temperature_source")
    private val screenOffKey = booleanPreferencesKey("screen_off_stop_fan")

    private fun repositoryWith(storedCurve: String? = null, source: Int? = null, screenOff: Boolean? = null) =
        SettingsRepository(
            TestPreferencesStore(
                mutablePreferencesOf().apply {
                    storedCurve?.let { this[curveKey] = it }
                    source?.let { this[sourceKey] = it }
                    screenOff?.let { this[screenOffKey] = it }
                }.toPreferences(),
            ),
        )

    // ───────────────────── 曲线：缺失 vs 损坏 ─────────────────────

    @Test
    fun `an absent curve is the default and is not reported as corrupt`() = runTest {
        val settings = repositoryWith().settings.first()
        assertEquals(FanCurve.DEFAULT, settings.smartCurve)
        assertTrue(settings.smartCurveLoad is CurveLoad.Loaded)
        assertFalse(settings.smartCurveCorrupt)
    }

    @Test
    fun `a corrupt stored curve falls back to default and is reported`() = runTest {
        val settings = repositoryWith(storedCurve = "{not json").settings.first()
        assertEquals(FanCurve.DEFAULT, settings.smartCurve)
        assertTrue(settings.smartCurveLoad is CurveLoad.FellBack)
        assertTrue(settings.smartCurveCorrupt)
    }

    @Test
    fun `a stored curve that parses but violates the constraints is also reported`() = runTest {
        // 温度回退 —— 上游正是允许这种曲线落盘，然后静默算出 0 让风扇停转。
        val unordered = """{"version":1,"points":[{"tempC":50,"pwmPercent":10},{"tempC":40,"pwmPercent":20}]}"""
        val settings = repositoryWith(storedCurve = unordered).settings.first()
        assertTrue(settings.smartCurveCorrupt)
        assertEquals(FanCurve.DEFAULT, settings.smartCurve)
    }

    @Test
    fun `a healthy stored curve is used verbatim`() = runTest {
        val quiet = FanCurve.presetCurve(CurvePreset.QUIET)
        val settings = repositoryWith(storedCurve = FanCurveJson.encode(quiet)).settings.first()
        assertEquals(quiet, settings.smartCurve)
        assertFalse(settings.smartCurveCorrupt)
    }

    // ───────────────────── 曲线：写入与修复 ─────────────────────

    @Test
    fun `saving a curve round-trips and clears the corrupt flag`() = runTest {
        val repository = repositoryWith(storedCurve = "garbage")
        assertTrue(repository.settings.first().smartCurveCorrupt)

        val performance = FanCurve.presetCurve(CurvePreset.PERFORMANCE)
        repository.setSmartCurve(performance)

        val settings = repository.settings.first()
        assertEquals(performance, settings.smartCurve)
        assertFalse(settings.smartCurveCorrupt)
    }

    @Test
    fun `repair removes the key instead of writing a curve equal to the default`() = runTest {
        val store = TestPreferencesStore(
            mutablePreferencesOf(curveKey to "garbage").toPreferences(),
        )
        val repository = SettingsRepository(store)
        assertTrue(repository.settings.first().smartCurveCorrupt)

        repository.repairSmartCurve()

        // 删除而不是回写默认：语义是「恢复出厂」，不是「用户恰好存了一条等于默认的曲线」。
        assertNull(store.snapshot[curveKey])
        assertFalse(repository.settings.first().smartCurveCorrupt)
        assertEquals(FanCurve.DEFAULT, repository.settings.first().smartCurve)
    }

    @Test
    fun `unrelated setting writes leave the stored curve intact`() = runTest {
        val quiet = FanCurve.presetCurve(CurvePreset.QUIET)
        val store = TestPreferencesStore(
            mutablePreferencesOf(curveKey to FanCurveJson.encode(quiet)).toPreferences(),
        )
        val repository = SettingsRepository(store)
        repository.setDarkMode("dark")
        repository.setScreenOffStopFan(true)
        assertEquals(quiet, repository.settings.first().smartCurve)
    }

    // ───────────────────── 温度源 ─────────────────────

    @Test
    fun `temperature source defaults to the fan node default`() = runTest {
        assertEquals(
            FanNodes.DEFAULT_TEMPERATURE_SOURCE,
            repositoryWith().settings.first().temperatureSource,
        )
    }

    @Test
    fun `every real temperature source index round-trips`() = runTest {
        val repository = repositoryWith()
        FanNodes.temperatureSources.indices.forEach { index ->
            repository.setTemperatureSource(index)
            assertEquals(index, repository.settings.first().temperatureSource)
        }
    }

    @Test
    fun `an out-of-range temperature source falls back instead of being persisted`() = runTest {
        val store = TestPreferencesStore()
        val repository = SettingsRepository(store)
        listOf(-1, FanNodes.temperatureSources.size, 999).forEach { invalid ->
            repository.setTemperatureSource(invalid)
            assertEquals(
                "非法下标 $invalid 必须回落到默认温度源",
                FanNodes.DEFAULT_TEMPERATURE_SOURCE,
                repository.settings.first().temperatureSource,
            )
            assertEquals(FanNodes.DEFAULT_TEMPERATURE_SOURCE, store.snapshot[sourceKey])
        }
    }

    @Test
    fun `a stored out-of-range temperature source is corrected on read`() = runTest {
        // 机型变化或将来增删温度源都可能让旧下标失效，读取时必须兜住。
        val settings = repositoryWith(source = 99).settings.first()
        assertEquals(FanNodes.DEFAULT_TEMPERATURE_SOURCE, settings.temperatureSource)
    }

    // ───────────────────── 息屏停转 ─────────────────────

    @Test
    fun `screen off stop defaults to off`() = runTest {
        // 后台改变硬件行为必须来自显式授权，所以默认关闭。
        assertFalse(repositoryWith().settings.first().screenOffStopFan)
    }

    @Test
    fun `screen off stop round-trips both ways`() = runTest {
        val repository = repositoryWith()
        repository.setScreenOffStopFan(true)
        assertTrue(repository.settings.first().screenOffStopFan)
        repository.setScreenOffStopFan(false)
        assertFalse(repository.settings.first().screenOffStopFan)
    }
}
