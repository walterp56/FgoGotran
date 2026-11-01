package com.fgogotran.story

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android coordinator wiring, alongside executable content-policy and handoff tests. */
class StoryTranslationContentWorkflowTest {
    private val service = source("accessibility/FgoAccessibilityService.kt")

    @Test
    fun `deliberate skip clears stale display and handoff before any API voice layout or usage work`() {
        val render = function("translateAndRenderScene")
        val skipAt = render.indexOf("if (!sceneHasRequiredTranslation(sceneSource)) {")
        assertTrue(skipAt > render.indexOf("processingVersion != stopVersion"))
        assertTrue(skipAt < render.indexOf("prepareKnownVoiceTarget("))
        assertTrue(skipAt < render.indexOf("translateSceneSource("))
        val skip = render.substring(skipAt, render.indexOf("val translationStartedAt"))
        for (required in listOf("translationOverlay.hide()", "storyTapHandoff.clear()",
            "rememberRenderedSourceText(mode, sourceFingerprint, sceneSource.stabilityKey)",
            "clearFailedRenderAttempt(sourceFingerprint)", "return true")) {
            assertTrue(skip.contains(required), required)
        }
        for (forbidden in listOf("hideForCapture(", "showTranslationFailureFeedback(", "addHistoryEntry(",
            "onSceneRendered(", "reportRenderedSceneUsage(", "maybeSpeakRenderedDialogue(", "overlayRenderer.render(")) {
            assertFalse(skip.contains(forbidden), forbidden)
        }
    }

    @Test
    fun `request copy is filtered before ruby and API while accepted OCR and choice completeness stay intact`() {
        assertTrue(service.contains("val translationContent = StoryTranslationContentPolicy.evaluate(input)"))
        val request = function("translateSceneSource")
        assertTrue(request.indexOf("sceneSource.translationContent.prepare(sceneSource.input)") <
            request.indexOf("TranslationRubyPolicy.prepare(fullInput, includeRuby)"))
        assertTrue(request.indexOf("TranslationRubyPolicy.prepare(fullInput, includeRuby)") <
            request.indexOf("translator.translateScene("))
        assertFalse(function("sceneSourceFor").contains("StoryTranslationContentPolicy"))
        assertFalse(function("regionSourceTextFor").contains("StoryTranslationContentPolicy"))
        val complete = function("completeChoiceSceneSourceFor")
        assertTrue(complete.contains("if (!choices.isComplete) return null"))
        assertTrue(complete.contains("ChoiceRecognitionPolicy.isComplete("))
    }

    @Test
    fun `render eligibility comes from original dialogue and original choice indices before name fallback`() {
        val instructions = function("buildRenderInstructions")
        assertTrue(instructions.contains("val content = sceneSource.translationContent"))
        assertTrue(instructions.contains("if (content.shouldTranslateChoice(index)) regionAndText.region to result else null"))
        assertTrue(instructions.contains("TextRegion.NAME_LABEL -> if (!content.translateDialogue) null else renderableNameTranslation("))
        assertTrue(instructions.contains("sceneTranslation.dialogue.takeIf { content.translateDialogue }"))
        assertTrue(instructions.contains("sourceText = regionAndText.text"))
        assertTrue(instructions.contains("showOriginalText = showOriginalForRegion"))
    }

    @Test
    fun `missing word translations still fail and only results in their matching choice regions satisfy completeness`() {
        val missing = function("missingRequiredRenderReason")
        assertTrue(missing.contains("if (content.translateDialogue && !renderedHasDialogue)"))
        assertTrue(missing.contains("return \"dialogue translation missing\""))
        assertTrue(missing.contains("content.shouldTranslateChoice(index)"))
        assertTrue(missing.contains("requiredChoices.count { it.region in renderedChoices }"))
        assertTrue(missing.contains("if (renderedChoiceCount < requiredChoices.size)"))
        assertFalse(missing.contains("hasDialogueContent("))
        assertTrue(function("sceneHasRequiredTranslation").contains("sceneSource.translationContent.hasWork"))
    }

    @Test
    fun `unchanged symbol-only choices cannot take semi-auto restoration shortcut and hide clears saved bitmap`() {
        val choices = function("processSemiAutoChoiceScreen")
        val restore = choices.substringBefore("Semi-auto choice source unchanged; restoring previous overlay")
            .substringAfterLast("if (")
        assertTrue(restore.contains("sceneHasRequiredTranslation(sceneSource)"))
        assertTrue(restore.contains("isAlreadyRenderedSource("))
        val overlay = source("overlay/TranslationOverlay.kt")
        assertTrue(overlay.substringAfter("fun hide() {").substringBefore("private fun hideOverlayView")
            .contains("hideOverlayView(clearBitmap = true)"))
        assertTrue(overlay.contains("latestTranslatedBitmap = null"))
        assertTrue(overlay.contains("setTranslatedOverlayTouchable(false)"))
    }

    @Test
    fun `mixed choice history cannot restore skipped dialogue speaker through raw-name fallback`() {
        val history = function("addHistoryEntry")
        assertTrue(history.contains("(renderedName ?: rawName).takeIf { sceneSource.translationContent.translateDialogue }"))
        assertTrue(history.contains("it.isNotBlank() && sceneSource.translationContent.translateDialogue"))
        assertTrue(history.contains("if (sceneSource.translationContent.translateDialogue &&"))
        assertTrue(history.contains("val choiceEntries = choiceInstructions.mapNotNull"))
    }

    @Test
    fun `all story translation modes share the boundary and other modes retain their own policies`() {
        for (method in listOf("processManualScreen", "processSemiAutoChoiceScreen", "processSemiAutoStoryScreen", "processAutoScreen")) {
            assertTrue(function(method).contains("translateAndRenderScene("), method)
        }
        for (method in listOf("processCropTranslation", "requestVoiceOnlyScene")) {
            assertFalse(function(method).contains("translationContent"), method)
            assertFalse(function(method).contains("sceneHasRequiredTranslation("), method)
        }
        // Preserve the distinction between recognised symbols and actual blank/incomplete OCR.
        assertTrue(function("processManualScreen").contains("if (scan.regions.isEmpty())"))
        assertTrue(function("processManualScreen").contains("showTranslationFailureFeedback("))
        assertTrue(function("processAutoScreen").contains("accepted = accepted"))
        assertTrue(function("processSemiAutoStoryScreen").contains("accepted = accepted"))
    }

    private fun function(name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(service).drop(1)
            .first { it.startsWith("$name(") }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/fgogotran/$path"), File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
