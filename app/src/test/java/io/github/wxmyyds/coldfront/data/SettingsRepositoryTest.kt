package io.github.wxmyyds.coldfront.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SettingsRepositoryTest {
    private val darkModeKey = stringPreferencesKey("dark_mode")
    private val paletteKey = stringPreferencesKey("palette")
    private val languageKey = stringPreferencesKey("app_language")

    @Test
    fun `empty preferences expose existing defaults`() = runTest {
        val repository = SettingsRepository(TestPreferencesStore())
        assertEquals(AppSettings(false, "system", "tonal_spot", true, "system"), repository.settings.first())
        assertEquals("system", repository.appLanguage.first())
    }

    @Test
    fun `invalid stored enum-like settings expose fallback without rewriting preferences`() = runTest {
        val initial = mutablePreferencesOf(
            darkModeKey to "DARK",
            paletteKey to "future_palette",
            languageKey to "fr",
        ).toPreferences()
        val store = TestPreferencesStore(initial)
        val repository = SettingsRepository(store)
        assertEquals("system", repository.settings.first().darkMode)
        assertEquals("tonal_spot", repository.settings.first().palette)
        assertEquals("system", repository.appLanguage.first())
        assertEquals(initial, store.snapshot)
        assertTrue(store.commits.isEmpty())
    }

    @Test
    fun `supported settings roundtrip and invalid setters persist fallback defaults`() = runTest {
        val store = TestPreferencesStore()
        val repository = SettingsRepository(store)
        listOf("system", "light", "dark").forEach { mode ->
            repository.setDarkMode(mode)
            assertEquals(mode, repository.settings.first().darkMode)
        }
        listOf("tonal_spot", "neutral", "vibrant", "expressive", "rainbow",
            "fruit_salad", "monochrome", "fidelity", "content").forEach { palette ->
            repository.setPalette(palette)
            assertEquals(palette, repository.settings.first().palette)
        }
        listOf("system", "zh", "en").forEach { language ->
            repository.setAppLanguage(language)
            assertEquals(language, repository.appLanguage.first())
        }
        listOf("", "unsupported", " SYSTEM ").forEach { invalid ->
            repository.setDarkMode(invalid)
            repository.setPalette(invalid)
            repository.setAppLanguage(invalid)
            assertEquals("system", store.snapshot[darkModeKey])
            assertEquals("tonal_spot", store.snapshot[paletteKey])
            assertEquals("system", store.snapshot[languageKey])
        }
        repository.setDynamicColor(true)
        repository.setPredictiveBack(false)
        assertTrue(repository.settings.first().dynamicColor)
        assertFalse(repository.settings.first().predictiveBack)
        assertEquals(true, store.snapshot[booleanPreferencesKey("dynamic_color")])
        assertEquals(false, store.snapshot[booleanPreferencesKey("predictive_back")])
    }

    @Test
    fun `settings writes leave orphan thermal values and unknown preferences intact`() = runTest {
        val orphanKeys = listOf(
            "low_temp", "mid_temp", "high_temp", "condensation_temp",
            "low_speed", "mid_speed", "high_speed",
        ).map(::intPreferencesKey)
        val initial = mutablePreferencesOf(stringPreferencesKey("future_setting") to "retain")
        orphanKeys.forEachIndexed { index, key -> initial[key] = index + 10 }
        val store = TestPreferencesStore(initial)
        val repository = SettingsRepository(store)
        repository.setDarkMode("dark")
        repository.setPalette("vibrant")
        repository.setAppLanguage("en")
        repository.setDynamicColor(true)
        repository.setPredictiveBack(false)
        orphanKeys.forEachIndexed { index, key -> assertEquals(index + 10, store.snapshot[key]) }
        assertEquals("retain", store.snapshot[stringPreferencesKey("future_setting")])
    }

    @Test
    fun `unrelated snapshots do not emit duplicate validated settings`() = runTest {
        // A cold scripted flow ensures distinctness is tested without StateFlow conflation.
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                emit(mutablePreferencesOf(languageKey to "en"))
                emit(mutablePreferencesOf(languageKey to "en", paletteKey to "neutral"))
                emit(mutablePreferencesOf(languageKey to "invalid"))
                emit(mutablePreferencesOf(languageKey to "also_invalid"))
                emit(mutablePreferencesOf(languageKey to "zh"))
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("No write expected")
        }
        val observed = SettingsRepository(store).appLanguage.toList()
        assertEquals(listOf("en", "system", "zh"), observed)
    }

    @Test
    fun `first snapshot contains every persisted setting without defaults or a correction pass`() = runTest {
        // Readiness is the full object itself, not a separate signal or independent fields.
        val initial = mutablePreferencesOf(
            booleanPreferencesKey("dynamic_color") to true,
            booleanPreferencesKey("predictive_back") to false,
            darkModeKey to "dark",
            paletteKey to "vibrant",
            languageKey to "en",
        ).toPreferences()
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                emit(initial)
                error("settings must not need a correction pass")
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("No write expected")
        }
        val repository = SettingsRepository(store)
        assertEquals(AppSettings(true, "dark", "vibrant", false, "en"), repository.settings.first())
    }

    @Test
    fun `snapshot has no value until the store emits`() = runTest {
        // Loading must not be represented by a synthetic default AppSettings.
        val gate = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                gate.await()
                emit(mutablePreferencesOf().toPreferences())
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("No write expected")
        }
        val snapshots = SettingsRepository(store).settings
        val received = async(start = CoroutineStart.UNDISPATCHED) { snapshots.first() }
        // Nothing may be emitted while the store is still loading; a default would land here.
        assertFalse(received.isCompleted)
        gate.complete(Unit)
        assertEquals(AppSettings(false, "system", "tonal_spot", true, "system"), received.await())
    }

    @Test
    fun `onboarding completion is off until it is explicitly persisted`() = runTest {
        val store = TestPreferencesStore()
        val repository = SettingsRepository(store)

        // 默认必须是「未完成」：否则首次启动会直接跳过引导。
        assertFalse(repository.settings.first().onboardingCompleted)
        assertNull(store.snapshot[booleanPreferencesKey("onboarding_completed")])

        repository.setOnboardingCompleted(true)
        assertTrue(repository.settings.first().onboardingCompleted)
        assertEquals(true, store.snapshot[booleanPreferencesKey("onboarding_completed")])

        // 运行检查（复用引导页）不应把完成状态改回去。
        repository.setDarkMode("dark")
        assertTrue(repository.settings.first().onboardingCompleted)
    }

    @Test
    fun `settings read write errors and cancellation propagate unchanged`() = runTest {
        listOf(IOException("disk failure"), CancellationException("cancelled")).forEach { failure ->
            val store = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { throw failure }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    throw failure
            }
            val repository = SettingsRepository(store)
            assertSame(failure, expectFailure<Exception> { repository.settings.first() })
            assertSame(failure, expectFailure<Exception> { repository.appLanguage.first() })
            assertSame(failure, expectFailure<Exception> { repository.setDynamicColor(true) })
            assertSame(failure, expectFailure<Exception> { repository.setDarkMode("dark") })
            assertSame(failure, expectFailure<Exception> { repository.setPalette("neutral") })
            assertSame(failure, expectFailure<Exception> { repository.setPredictiveBack(false) })
            assertSame(failure, expectFailure<Exception> { repository.setAppLanguage("en") })
            assertSame(failure, expectFailure<Exception> { repository.setOnboardingCompleted(true) })
        }
    }
}
