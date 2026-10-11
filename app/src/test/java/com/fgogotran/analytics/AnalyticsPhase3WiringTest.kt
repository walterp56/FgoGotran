package com.fgogotran.analytics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wiring assertions complement reducer tests; not a claim of device-level rendering verification. */
class AnalyticsPhase3WiringTest {
    @Test fun `old service selection and early pipeline reporting is gone`() {
        val paths = listOf("analytics/AppAnalytics.kt", "data/SettingsRepository.kt",
            "accessibility/FgoAccessibilityService.kt", "ui/screen/ApiSettingsScreen.kt", "voice/AiVoiceService.kt")
        for (path in paths) {
            val text = source(path)
            for (old in listOf("reportAppUsed(", "reportCurrentBackendType(", "reportBackendType(",
                "reportTranslationMode(", "reportCropModeUsed(", "reportGameServerUsed(",
                "reportVoiceServerUsed(", "shouldSendAnalytics", "markAnalytics")) {
                assertFalse(text.contains(old), "$path: $old")
            }
        }
    }

    @Test fun `story and crop report after accepted display not OCR completion`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val story = service.substringAfter("private suspend fun translateAndRenderScene(")
            .substringBefore("private fun reportRenderedSceneUsage(")
        assertTrue(story.indexOf("reportRenderedSceneUsage(") > story.indexOf("if (!translationOverlay.isShowing())"))
        assertTrue(story.indexOf("reportRenderedSceneUsage(") > story.indexOf("discarding rendered bitmap"))
        assertTrue(service.contains("if (cropResultOverlay.show(cropBounds, rendered)) {\n                appAnalytics.reportTranslationDisplayed"))
        val status = service.substringAfter("private fun showCropStatus(").substringBefore("private fun ")
        assertFalse(status.contains("reportTranslationDisplayed"))
        assertTrue(source("crop/CropResultOverlay.kt").contains("fun show(bounds: Rect, bitmap: Bitmap): Boolean"))
    }

    @Test fun `battle reports on first draw once per retained result not translation completion`() {
        val battle = source("battle/BattleSubtitleController.kt")
        val pump = battle.substringAfter("private fun pumpTranslations()").substringBefore("fun refreshCaption()")
        assertFalse(pump.contains("reportTranslationDisplayed"))
        val draw = battle.substringAfter("fun refreshCaption()")
        assertTrue(draw.indexOf("reportTranslationDisplayed") > draw.indexOf("delivery.markVisible("))
        assertTrue(draw.contains("if (!delivered.usageReported)"))
        assertTrue(draw.contains("AnalyticsUsageOrigin(SettingsRepository.GAME_SERVER_JP)"))
    }

    @Test fun `all normal voice calls carry origin and only successful playback counts`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        assertEquals(4, Regex("usageOrigin = usageOrigin").findAll(service).count())
        assertTrue(service.contains("AnalyticsUsageOrigin(server, mode.analyticsMode)"))
        assertTrue(service.contains("AnalyticsUsageOrigin(SettingsRepository.GAME_SERVER_JP, mode.analyticsMode)"))
        val voice = source("voice/AiVoiceService.kt")
        val production = voice.substringBefore("suspend fun prepareAzureVoiceTest")
        assertTrue(production.contains("if (started && usageOrigin != null)"))
        assertTrue(production.indexOf("reportVoicePlaybackStarted") > production.indexOf("playbackEngine.playTogether"))
        assertFalse(voice.substringAfter("suspend fun prepareAzureVoiceTest").contains("reportVoicePlaybackStarted"))
        assertFalse(source("speech/RealtimeVoiceTranslationController.kt").contains("appAnalytics"))
    }

    @Test fun `actual provider metadata survives composites and never infers current selected backend`() {
        val translator = source("translation/Translator.kt")
        assertTrue(translator.contains("if (!cached && backend == config.backend) TranslationApiUsage.completed(backend) else null"))
        assertTrue(translator.contains("apiUsage = stateTranslation.apiUsage"))
        assertTrue(translator.contains("apiUsage = TranslationApiUsage.latest(resolvedParts)"))
        assertTrue(translator.contains("apiUsage = result.apiUsage"))
        assertTrue(translator.contains("copy(countsAsUsage = text.isNotBlank())"))
        val analytics = source("analytics/AppAnalytics.kt")
        assertFalse(analytics.contains("translationBackend"))
        assertFalse(analytics.contains("targetLanguage.first"))
        assertTrue(analytics.contains("AnalyticsSuccess.translation(currentUtcDate(), origin, displayed)"))
        assertTrue(analytics.contains("AnalyticsSuccess.voice(currentUtcDate(), origin)"))
        assertTrue(analytics.contains("Channel<Recording>(Channel.UNLIMITED)"))
        assertTrue(analytics.substringAfter("private fun recordSuccess(").contains("catch (_: Exception)"))
    }

    private fun source(path: String) = listOf(File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")).first(File::isFile).readText().replace("\r\n", "\n")
}
