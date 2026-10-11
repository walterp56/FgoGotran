package com.fgogotran.analytics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Narrow source checks for Android lifecycle/DataStore wiring; reducers and HTTP have behavioral tests. */
class AnalyticsPhase2WiringTest {
    @Test
    fun `foreground and effective language are reported without selected backend attribution`() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("appAnalytics.reportAppForeground(AppLanguageManager.effectiveLanguageTag(this))"))
        assertTrue(activity.contains("appAnalytics.reportAppProfile(AppLanguageManager.effectiveLanguageTag(this))"))
        assertFalse(activity.contains("appAnalytics.reportAppUsed()"))
        assertFalse(activity.contains("appAnalytics.reportCurrentBackendType()"))
        val v2 = source("analytics/AppAnalytics.kt")
        assertTrue(v2.contains("Channel<Recording>(Channel.UNLIMITED)"))
        assertTrue(v2.contains("for (recording in recordings)"))
        assertTrue(v2.contains("settingsRepository.updateAnalyticsOutbox"))
        assertTrue(v2.contains("v2Uploader.requestFlush()"))
        assertFalse(v2.contains("translationBackend.first"))
        assertFalse(v2.contains("translationTargetLanguage.first"))
        assertFalse(v2.contains("translationMode.first"))
    }

    @Test
    fun `outbox updates use existing atomic DataStore and keep installation identity`() {
        val settings = source("data/SettingsRepository.kt")
        val update = settings.substringAfter("override suspend fun updateAnalyticsOutbox")
            .substringBefore("suspend fun getIgnoredAppUpdateVersionCode")
        assertTrue(update.contains("context.dataStore.edit { prefs ->"))
        assertTrue(update.contains("prefs[KEY_ANALYTICS_INSTALL_ID]?.takeIf"))
        assertTrue(update.contains("check(updated.installId == current.installId)"))
        assertTrue(update.contains("AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(raw)"))
        assertFalse(update.contains("runCatching")) // corrupt state cannot silently reset its revision
        assertFalse(update.contains("httpClient"))
    }

    @Test
    fun `foreground v2 reporting is not connected to accessibility service or settings selections`() {
        for (path in listOf("accessibility/FgoAccessibilityService.kt", "ui/screen/ApiSettingsScreen.kt", "voice/AiVoiceService.kt")) {
            val text = source(path)
            assertFalse(text.contains("reportAppForeground("), path)
            assertFalse(text.contains("reportAppProfile("), path)
            assertFalse(text.contains("updateAnalyticsOutbox"), path)
        }
    }

    private fun source(path: String) = listOf(
        File("src/main/java/com/fgogotran/$path"), File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
