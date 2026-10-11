package io.github.wxmyyds.coldfront.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.wxmyyds.coldfront.fan.CurveLoad
import io.github.wxmyyds.coldfront.fan.FanCurve
import io.github.wxmyyds.coldfront.fan.FanCurveJson
import io.github.wxmyyds.coldfront.fan.FanNodes
import io.github.wxmyyds.coldfront.fan.FanTuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "cooler_settings")

class SettingsRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    /** All fields are decoded together, never combined from independently collected setting flows. */
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            dynamicColor = preferences[KEY_DYNAMIC_COLOR] ?: false,
            darkMode = darkModeOrDefault(preferences[KEY_DARK_MODE]),
            palette = paletteOrDefault(preferences[KEY_PALETTE]),
            predictiveBack = preferences[KEY_PREDICTIVE_BACK] ?: true,
            appLanguage = languageOrDefault(preferences[KEY_APP_LANGUAGE]),
            onboardingCompleted = preferences[KEY_ONBOARDING_COMPLETED] ?: false,
            // 缺失 → 默认（首次运行的正常状态）；损坏 → FellBack，界面必须告知用户。
            smartCurveLoad = FanCurveJson.decode(preferences[KEY_SMART_CURVE]),
            temperatureSource = temperatureSourceOrDefault(preferences[KEY_TEMPERATURE_SOURCE]),
            screenOffStopFan = preferences[KEY_SCREEN_OFF_STOP_FAN] ?: false,
            // 轮询参数与温度源同理：落盘值可能来自旧版本或被人为改写，读取时必须兜住，
            // 否则一个越界值会直接进到轮询循环里（例如 0 毫秒间隔 → 疯狂起 su 进程）。
            pollIntervalMs = FanTuning.pollIntervalOrDefault(preferences[KEY_POLL_INTERVAL_MS]),
            fanTransitionMs = FanTuning.transitionMsOrDefault(preferences[KEY_FAN_TRANSITION_MS]),
        )
    }.distinctUntilChanged()

    // The service and tile only need the notification language, not the full UI snapshot.
    val appLanguage: Flow<String> = settings.map { it.appLanguage }.distinctUntilChanged()

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setDarkMode(mode: String) {
        dataStore.edit { it[KEY_DARK_MODE] = darkModeOrDefault(mode) }
    }

    suspend fun setPalette(palette: String) {
        dataStore.edit { it[KEY_PALETTE] = paletteOrDefault(palette) }
    }

    suspend fun setPredictiveBack(enabled: Boolean) {
        dataStore.edit { it[KEY_PREDICTIVE_BACK] = enabled }
    }

    suspend fun setAppLanguage(language: String) {
        dataStore.edit { it[KEY_APP_LANGUAGE] = languageOrDefault(language) }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    /**
     * 写入曲线。曲线类型本身保证良构（[FanCurve] 无法被构造成不良构状态），
     * 所以这里不会把一个编辑器 bug 落盘成"以后每次启动都报损坏"。
     */
    suspend fun setSmartCurve(curve: FanCurve) {
        dataStore.edit { it[KEY_SMART_CURVE] = FanCurveJson.encode(curve) }
    }

    /**
     * 清掉无法解析的曲线，让 `smartCurveLoad` 回到 [CurveLoad.Loaded]。
     *
     * 刻意用「删除」而不是「回写默认曲线」：删除后该 key 回归"从未设置"，
     * 语义上是**恢复出厂**而不是"用户恰好存了一条等于默认的曲线"。
     */
    suspend fun repairSmartCurve() {
        dataStore.edit { it.remove(KEY_SMART_CURVE) }
    }

    suspend fun setTemperatureSource(index: Int) {
        dataStore.edit { it[KEY_TEMPERATURE_SOURCE] = temperatureSourceOrDefault(index) }
    }

    suspend fun setScreenOffStopFan(enabled: Boolean) {
        dataStore.edit { it[KEY_SCREEN_OFF_STOP_FAN] = enabled }
    }

    /**
     * 写入轮询间隔。落盘前先**规范化**：界面只会给出区间内的值，但写入路径不能假设调用方
     * 一定来自界面，一个越界值会直接变成轮询循环的 `delay` 参数。
     */
    suspend fun setPollInterval(ms: Int) {
        dataStore.edit { it[KEY_POLL_INTERVAL_MS] = FanTuning.pollIntervalOrDefault(ms) }
    }

    /** 写入升降过渡时间（毫秒），同样先规范化：0 = 一步到位。 */
    suspend fun setFanTransitionMs(ms: Int) {
        dataStore.edit { it[KEY_FAN_TRANSITION_MS] = FanTuning.transitionMsOrDefault(ms) }
    }


    private fun darkModeOrDefault(value: String?): String = when (value) {
        "system", "light", "dark" -> value
        else -> "system"
    }

    private fun paletteOrDefault(value: String?): String =
        if (PaletteStyles.isValid(value)) requireNotNull(value) else PaletteStyles.DEFAULT

    private fun languageOrDefault(value: String?): String = when (value) {
        "system", "zh", "en" -> value
        else -> "system"
    }

    /**
     * 温度源下标的**唯一权威**是 [FanNodes.temperatureSources]：机型不同、将来增删源时，
     * 这里会跟着一起变，而不是各自维护一份数量常量。
     */
    private fun temperatureSourceOrDefault(value: Int?): Int =
        value?.takeIf { it in FanNodes.temperatureSources.indices }
            ?: FanNodes.DEFAULT_TEMPERATURE_SOURCE

    companion object {
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_PALETTE = stringPreferencesKey("palette")
        private val KEY_PREDICTIVE_BACK = booleanPreferencesKey("predictive_back")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_SMART_CURVE = stringPreferencesKey("smart_curve_json")
        private val KEY_TEMPERATURE_SOURCE = intPreferencesKey("temperature_source")
        private val KEY_SCREEN_OFF_STOP_FAN = booleanPreferencesKey("screen_off_stop_fan")
        private val KEY_POLL_INTERVAL_MS = intPreferencesKey("poll_interval_ms")
        /** 前身是旧版的 `smooth_step`（每轮步进百分点）。语义已换成"过渡时间"，旧键不再读取。 */
        private val KEY_FAN_TRANSITION_MS = intPreferencesKey("fan_transition_ms")
        // Former thermal-threshold preferences are intentionally left untouched on disk.
    }
}
