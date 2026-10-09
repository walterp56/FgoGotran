package com.fgogotran.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wiring checks for Android-only coordinators; style and parsing behavior have pure JVM tests. */
class VoiceHintWorkflowTest {
    @Test
    fun `each hint prompt retains its own outer response keys`() {
        val translator = source("translation/Translator.kt")
        assertTrue(function(translator, "buildVoiceHintPrompt")
            .contains("Return JSON only with exactly this key: voice_hint."))
        assertTrue(function(translator, "buildDialogueWithVoiceHintUserPrompt")
            .contains("Return JSON only with exactly these keys: dialogue, voice_hint."))
        val scene = function(translator, "buildSceneUserPrompt")
        assertTrue(scene.contains("\"name, dialogue, choices, voice_hint\""))
        assertTrue(scene.contains("\"name, dialogue, choices\""))
        assertTrue(translator.contains("VOICE_HINT_TEST_MAX_TOKENS = 96"))
    }

    @Test
    fun `all three hint prompts and the cache path use the same request context`() {
        val translator = source("translation/Translator.kt")
        assertEquals(3, Regex("buildVoiceHintInstructions\\(").findAll(translator).count())
        assertFalse(translator.contains("VOICE_HINT_NORMAL_STYLES"))
        assertFalse(translator.contains("VOICE_HINT_DRAGON_STYLES"))
        val cached = translator.substringAfter(
            "if (requestVoiceHint && !needsName && !needsDialogue && neededChoiceIndices.isEmpty())"
        ).substringBefore("val uncachedName")
        assertTrue(cached.contains("requestVoiceHint("))
        assertTrue(cached.contains("voiceHintContext = voiceHintContext"))
        val parser = function(translator, "parseSceneResult")
        assertTrue(parser.contains("parseVoiceLineHint("))
        assertTrue(parser.contains("voiceHintContext"))
        val request = function(translator, "requestVoiceHint")
        assertTrue(request.indexOf("if (voiceHintContext == null) return null") < request.indexOf("getRuntimeConfig()"))
    }

    @Test
    fun `non JP resolves before hint reuses profile and keeps choices free of API hints`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val request = function(service, "requestVoiceOnlyScene")
        assertTrue(request.indexOf("prepareKnownVoiceTarget(") < request.indexOf("requestVoiceOnlyVoiceHint("))
        assertTrue(request.contains("preparedTarget = voiceTarget"))
        val choices = request.substringAfter("if (choiceText != null)")
        assertTrue(choices.contains("voiceHint = null"))
        assertFalse(choices.contains("requestVoiceOnlyVoiceHint("))
        val hint = function(service, "requestVoiceOnlyVoiceHint")
        assertTrue(hint.contains("withTimeoutOrNull(VOICE_HINT_REQUEST_TIMEOUT_MS)"))
        assertTrue(hint.contains("translator.requestVoiceHint(speakerName, dialogue, voiceHintContext)"))
        assertTrue(service.contains("VOICE_HINT_REQUEST_TIMEOUT_MS = 2_500L"))
    }

    @Test
    fun `JP passes the same voice target into translation and later playback`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val render = function(service, "translateAndRenderScene")
        assertTrue(render.indexOf("prepareKnownVoiceTarget(") < render.indexOf("translateSceneSource("))
        assertTrue(render.contains("translateSceneSource(sceneSource, voiceTarget)"))
        assertTrue(render.contains("maybeSpeakRenderedDialogue(sceneSource, sceneTranslation, instructions, voiceTarget)"))
        assertTrue(function(service, "translateSceneSource")
            .contains("translator.translateScene(input, voiceTarget?.hintContext)"))
        assertTrue(function(service, "shouldRequestVoiceHint").contains("!aiVoiceEnabled || !aiVoiceApiHintsEnabled"))
        val speak = function(service, "maybeSpeakRenderedDialogue")
        assertTrue(speak.contains("preparedTarget = voiceTarget"))
        assertTrue(speak.substringAfter("if (choiceText != null)").contains("voiceHint = null"))
    }

    @Test
    fun `known target lookup is local and does not generate temporary voices`() {
        val service = source("voice/AiVoiceService.kt")
        val prepare = function(service, "prepareKnownVoiceTarget")
        assertTrue(prepare.contains("findKnownVoiceProfile("))
        assertFalse(prepare.contains("resolveVoiceProfile("))
        assertFalse(prepare.contains("updateIfNeeded("))
        assertFalse(prepare.contains("translator."))
        val playback = function(service, "speakDialogue")
        assertTrue(playback.contains("it.matches(normalizedServer, speakers)"))
        assertTrue(function(service, "prepareVoiceLines").contains("preparedTarget?.profilesBySpeaker?.get(speaker)"))
    }

    @Test
    fun `voice settings test prepares before hint and reuses its refreshed profile`() {
        val ui = source("ui/screen/VoiceSettingsScreen.kt")
        val test = ui.substringAfter("fun testAzureVoice()").substringBefore("fun ")
        assertTrue(test.indexOf("prepareAzureVoiceTest(") < test.indexOf("translator.testVoiceHint("))
        assertTrue(test.contains("voiceTarget.hintContext"))
        assertTrue(test.contains("preparedTarget = voiceTarget"))
        val voice = source("voice/AiVoiceService.kt")
        assertTrue(function(voice, "prepareAzureVoiceTest").contains("updateIfNeeded(force = true)"))
        val play = function(voice, "playAzureVoiceTest")
        assertTrue(play.contains("preparedTarget?.takeIf"))
        assertFalse(play.contains("updateIfNeeded("))
    }

    @Test
    fun `playback and voice test read the saved switch and discard earlier hints when OFF`() {
        val service = source("voice/AiVoiceService.kt")
        val playback = function(service, "speakDialogue")
        assertTrue(playback.contains("settingsRepository.aiVoiceApiHintsEnabled.first()"))
        assertTrue(playback.contains("voiceHint = voiceHint.takeIf { emotionEnhancementEnabled }"))
        assertTrue(playback.contains("emotionEnhancementEnabled = emotionEnhancementEnabled"))
        val test = function(service, "playAzureVoiceTest")
        assertTrue(test.contains("settingsRepository.aiVoiceApiHintsEnabled.first()"))
        assertTrue(test.contains("val effectiveVoiceHint = voiceHint.takeIf { emotionEnhancementEnabled }"))
        assertTrue(test.contains("emotionEnhancementEnabled = emotionEnhancementEnabled"))
        val expression = function(service, "voiceExpressionFor")
        assertTrue(expression.contains("emotionEnhancementEnabled = emotionEnhancementEnabled"))
    }

    @Test
    fun `OFF returns saved profiles but exits before temporary profile generation`() {
        val service = source("voice/AiVoiceService.kt")
        val resolve = function(service, "resolveVoiceProfile")
        val lookup = resolve.indexOf("findKnownVoiceProfile(normalizedServer, speaker)")
        val off = resolve.indexOf("if (!allowTemporaryProfile)")
        val generation = resolve.indexOf("tempProfileMutex.withLock")
        assertTrue(lookup >= 0 && off > lookup && generation > off)
        assertTrue(resolve.substring(off, generation).contains("return null"))
        assertTrue(resolve.substring(generation).contains("tempVoiceProfileBuilder.build("))
        val prepare = function(service, "prepareVoiceLines")
        assertTrue(prepare.contains("allowTemporaryProfile = emotionEnhancementEnabled"))
        assertTrue(prepare.contains("emotionEnhancementEnabled = emotionEnhancementEnabled"))
    }

    @Test
    fun `OFF remains the saved default and is logged distinctly from ON fallback`() {
        val settings = source("data/SettingsRepository.kt")
        assertTrue(settings.contains("DEFAULT_AI_VOICE_API_HINTS_ENABLED = false"))
        val logger = function(source("voice/AiVoiceService.kt"), "logVoiceExpression")
        assertTrue(logger.contains("if (emotionEnhancementEnabled) \"enhanced_or_local\" else \"presets_only\""))
        assertTrue(logger.contains("expressionMode="))
    }

    @Test
    fun `explicit OFF is not described as Sakura local fallback`() {
        val settings = source("ui/screen/SettingsScreen.kt")
        assertTrue(settings.contains("!aiVoiceApiHintsEnabled -> stringResource(R.string.settings_auto_54)"))
        val voice = source("ui/screen/VoiceSettingsScreen.kt")
        assertTrue(voice.contains("!apiVoiceHintsSupported && aiVoiceApiHintsEnabled -> stringResource(R.string.voice_auto_4)"))
    }

    @Test
    fun `voice card shows inactive before saved hint preference or model support`() {
        val settings = source("ui/screen/SettingsScreen.kt")
        assertTrue(settings.contains(
            "val aiVoiceActive = aiVoiceEnabled && SettingsRepository.readTextAvailableFor(targetLanguage)"
        ))
        val row = settings.substringAfter("label = stringResource(R.string.settings_auto_33),")
            .substringBefore("Button(")
        val value = row.substringAfter("value = when {").substringBefore("valueColor =")
        val inactive = value.indexOf("!aiVoiceActive -> stringResource(R.string.voice_ai_expression_inactive)")
        val off = value.indexOf("!aiVoiceApiHintsEnabled ->")
        val unsupported = value.indexOf("!apiVoiceHintsSupported ->")
        val on = value.indexOf("else -> stringResource(R.string.settings_auto_53)")
        assertTrue(inactive >= 0 && off > inactive && unsupported > off && on > unsupported)
        val color = row.substringAfter("valueColor = when {")
        assertTrue(color.indexOf("!aiVoiceActive -> MaterialTheme.colorScheme.onSurfaceVariant") >= 0)
        assertTrue(color.indexOf("apiVoiceHintsSupported && aiVoiceApiHintsEnabled -> FgoUiColors.success") >
            color.indexOf("!aiVoiceActive ->"))
        assertFalse(row.contains("setAiVoiceApiHintsEnabled"))
    }

    @Test
    fun `voice settings explains inactive playback before Sakura fallback without resetting preference`() {
        val voice = source("ui/screen/VoiceSettingsScreen.kt")
        val row = voice.substringAfter("title = stringResource(R.string.voice_auto_32),")
            .substringBefore("VoiceSettingsCard(")
        val inactive = row.indexOf("!aiVoiceActive -> stringResource(R.string.voice_ai_expression_requires_playback)")
        val unsupported = row.indexOf("!apiVoiceHintsSupported && aiVoiceApiHintsEnabled ->")
        assertTrue(inactive >= 0 && unsupported > inactive)
        assertTrue(row.contains("checked = effectiveApiVoiceHintsEnabled"))
        assertTrue(row.contains("enabled = apiVoiceHintsSupported && aiVoiceActive"))
        val beforeUserChange = row.substringBefore("onCheckedChange =")
        assertFalse(beforeUserChange.contains("setAiVoiceApiHintsEnabled"))
    }

    private fun source(relativePath: String): String = listOf(
        File("src/main/java/com/fgogotran/$relativePath"),
        File("app/src/main/java/com/fgogotran/$relativePath")
    ).first(File::isFile).readText()

    private fun function(source: String, name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(source).drop(1)
            .first { it.startsWith("$name(") }
}
