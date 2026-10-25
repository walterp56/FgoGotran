package com.fgogotran.dialoguesubtitle

import com.fgogotran.subtitle.ScreenOcrWorkflow
import com.fgogotran.subtitle.ScreenOcrWorkflowState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DialogueSubtitleWorkflowTest {
    private fun source(path: String) = listOf(File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")).first(File::isFile).readText()

    @Test fun `selection is mutually exclusive and session reset returns to story`() {
        val state = ScreenOcrWorkflowState()
        assertFalse(state.isSubtitle)
        state.select(ScreenOcrWorkflow.BATTLE_SUBTITLE)
        assertTrue(state.isBattle); assertFalse(state.isDialogue)
        state.select(ScreenOcrWorkflow.DIALOGUE_SUBTITLE)
        assertTrue(state.isDialogue); assertFalse(state.isBattle)
        state.select(ScreenOcrWorkflow.STORY)
        assertFalse(state.isSubtitle)
    }

    @Test fun `crop waits for new OCR owner and mode admission shares the busy guard`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val crop = service.substringAfter("fun requestCropTranslation(").substringBefore("fun clearCropTranslationOverlay")
        assertTrue(crop.indexOf("interruptedDialogueScan?.join()") < crop.indexOf("processCropTranslation("))
        val monitor = service.substringAfter("private fun monitorDialogueIfReady()").substringBefore("private fun monitorBattleIfReady()")
        assertTrue(monitor.contains("isCropBlockingOcr()"))
        assertTrue(monitor.contains("subtitleFrameBusy"))
        assertTrue(monitor.contains("takeScreenshotCompat(timingLabel = \"DIALOGUE_SUBTITLE\")"))
        assertTrue(monitor.contains("frame?.recycle()"))
    }

    @Test fun `captured dialogue requests have no battle timeout or scene freshness rejection`() {
        val controller = source("dialoguesubtitle/DialogueSubtitleController.kt")
        assertTrue(controller.contains("TranslationPromptProfile.DIALOGUE_SUBTITLE"))
        assertTrue(controller.contains("version != generation"))
        assertTrue(controller.contains("requests.values.toList().forEach { it.cancel() }"))
        assertFalse(controller.contains("withTimeoutOrNull(8_000"))
        assertFalse(controller.contains("isTranslationSourceStillCurrent"))
        assertFalse(controller.contains("aiVoiceService"))
        assertTrue(controller.contains("presentation.markDrawn"))
        assertTrue(controller.contains("reportTranslationDisplayed"))
        assertTrue(controller.contains("TranslationRubyPolicy.prepare"))
        assertTrue(controller.contains("recordFailure(input)"))
        assertTrue(controller.contains("countsAsUsage = false"))
        assertTrue(controller.contains("event.source, targetLocale)"))
        val failure = controller.substringAfter("private fun recordFailure(input: Input)").substringBefore("fun pause()")
        assertTrue(failure.contains("input.targetLocale, isFailure = true"))
        assertFalse(failure.contains("currentTargetLocale"))
        val success = controller.substringAfter("val translatedRows = result.dialogueSubtitleRows.ifEmpty")
            .substringBefore("catch (error: CancellationException)")
        assertTrue(success.contains("completeDialogueSubtitleEntry(input.history, result.translatedText, result.targetLocale)"))
        assertTrue(success.contains("presentation.complete(event, translatedRows, Delivered(result, event), SystemClock.elapsedRealtime())"))
        assertFalse(success.contains("if (SessionTranslationHistory.completeDialogueSubtitleEntry"))
    }

    @Test fun `render follows source occurrences with snug backgrounds and complete single row fitting`() {
        val controller = source("dialoguesubtitle/DialogueSubtitleController.kt")
        val overlay = source("dialoguesubtitle/DialogueSubtitleOverlay.kt")
        assertTrue(controller.contains("presentation.observe(assembler.visibleSnapshot"))
        assertTrue(controller.contains("DialogueSubtitleRenderRow(it.id, it.slot, it.text, it.value.result.targetLocale)"))
        assertTrue(overlay.contains("lastRows == normalizedRows"))
        assertTrue(overlay.contains("version == drawVersion"))
        assertTrue(overlay.contains("geometry.baseline(row.slot) - caption.layout.getLineBaseline(0)"))
        assertTrue(overlay.contains("layout.lineCount == 1 && layout.getLineEnd(0) == text.length"))
        assertTrue(overlay.contains("canvas.drawRoundRect"))
        assertFalse(overlay.contains("background = GradientDrawable"))
        assertFalse(overlay.contains(".setMaxLines("))
        assertFalse(overlay.contains(".setEllipsize("))
    }
}
