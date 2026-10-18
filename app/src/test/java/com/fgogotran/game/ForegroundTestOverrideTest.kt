package com.fgogotran.game

import com.fgogotran.data.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Policy and source-wiring checks; preference persistence still needs device acceptance. */
class ForegroundTestOverrideTest {
    private fun source(path: String): String = listOf(
        File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun section(text: String, start: String, end: String): String {
        assertTrue(text.contains(start), "Missing section: $start")
        val remaining = text.substringAfter(start)
        assertTrue(remaining.contains(end), "Missing section end: $end")
        return remaining.substringBefore(end)
    }

    @Test fun `default is on and saved choices remain authoritative`() {
        assertTrue(SettingsRepository.DEFAULT_FOREGROUND_TEST_OVERRIDE_ENABLED)
        val settings = source("data/SettingsRepository.kt")
        val preference = section(settings, "val foregroundTestOverrideEnabled:",
            "suspend fun setForegroundTestOverrideEnabled(")
        assertTrue(preference.contains(
            "prefs[KEY_FOREGROUND_TEST_OVERRIDE_ENABLED] ?: DEFAULT_FOREGROUND_TEST_OVERRIDE_ENABLED"))
        val setter = section(settings, "suspend fun setForegroundTestOverrideEnabled(",
            "/** Whether story dialogue")
        assertTrue(setter.contains(
            "context.dataStore.edit { it[KEY_FOREGROUND_TEST_OVERRIDE_ENABLED] = enabled }"))
        assertTrue(settings.contains(
            "booleanPreferencesKey(\"foreground_test_override_enabled\")"))
    }

    @Test fun `settings uses the same default and preserves the ten tap backup toggle`() {
        val settings = source("ui/screen/SettingsScreen.kt")
        val preference = section(settings,
            "val foregroundTestOverrideEnabled by settingsRepository.foregroundTestOverrideEnabled.collectAsState(",
            "val liveVoiceTranslationEnabled")
        assertTrue(preference.contains(
            "initial = SettingsRepository.DEFAULT_FOREGROUND_TEST_OVERRIDE_ENABLED"))
        assertTrue(settings.contains("private const val HIDDEN_TOGGLE_TAP_THRESHOLD = 10"))
        assertTrue(settings.contains("private const val HIDDEN_TOGGLE_TAP_WINDOW_MS = 5_000L"))
        val toggle = section(settings, "fun handleStatusRowTap()", "fun openDownloadPage()")
        assertTrue(toggle.contains("now - foregroundTestTapWindowStartedAt > HIDDEN_TOGGLE_TAP_WINDOW_MS"))
        assertTrue(toggle.contains("if (nextCount >= HIDDEN_TOGGLE_TAP_THRESHOLD)"))
        assertTrue(toggle.contains("val enabled = !foregroundTestOverrideEnabled"))
        assertTrue(toggle.contains("settingsRepository.setForegroundTestOverrideEnabled(enabled)"))
        assertTrue(toggle.contains("R.string.settings_auto_15"))
        assertTrue(toggle.contains("R.string.settings_auto_10"))
    }

    @Test fun `first enabled preference still enters service initialization`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        assertTrue(service.contains("private var foregroundTestOverrideEnabled = false"))
        val collector = section(service,
            "settingsRepository.foregroundTestOverrideEnabled.collect { enabled ->",
            "sessionGate.captureActiveToken()?.let(::startRunnerSession)")
        val unchangedGuard = collector.indexOf(
            "if (foregroundTestOverrideEnabled == enabled) return@collect")
        val assignment = collector.indexOf("foregroundTestOverrideEnabled = enabled")
        val targetSetup = collector.indexOf("foregroundTestOverride.observeExternalPackage(")
        assertTrue(unchangedGuard >= 0)
        assertTrue(assignment > unchangedGuard)
        assertTrue(targetSetup > assignment)
        assertTrue(collector.contains("cancelCurrentTranslation()"))
    }

    @Test fun `default on still needs an external target when FGO is not foreground`() {
        val override = ForegroundTestOverride()
        assertNull(override.externalPackageName)
        assertFalse(override.isEffective(SettingsRepository.DEFAULT_FOREGROUND_TEST_OVERRIDE_ENABLED, false))
        assertTrue(override.isEffective(SettingsRepository.DEFAULT_FOREGROUND_TEST_OVERRIDE_ENABLED, true))
        assertTrue(override.observeExternalPackage("example.fgo.channel"))
        assertTrue(override.isEffective(SettingsRepository.DEFAULT_FOREGROUND_TEST_OVERRIDE_ENABLED, false))
    }

    @Test fun `saved off restores package based eligibility without losing the target`() {
        val override = ForegroundTestOverride()
        override.observeExternalPackage("example.fgo.channel")
        assertTrue(override.isEffective(enabled = true, isFgoForeground = false))
        assertFalse(override.isEffective(enabled = false, isFgoForeground = false))
        assertTrue(override.isEffective(enabled = false, isFgoForeground = true))
        assertEquals("example.fgo.channel", override.externalPackageName)
        assertTrue(override.isEffective(enabled = true, isFgoForeground = false))
    }

    @Test fun `target changes and clearing keep their existing semantics`() {
        val override = ForegroundTestOverride()
        assertTrue(override.observeExternalPackage("example.first"))
        assertFalse(override.observeExternalPackage("example.first"))
        assertTrue(override.observeExternalPackage("example.second"))
        assertEquals("example.second", override.externalPackageName)
        assertTrue(override.observeExternalPackage(null))
        assertNull(override.externalPackageName)
        assertFalse(override.isEffective(enabled = true, isFgoForeground = false))
        assertFalse(override.observeExternalPackage("  "))
    }
}
