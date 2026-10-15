package com.fgogotran.crop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android lifecycle wiring, supplementary to production-policy tests and device acceptance. */
class CropWorkflowWiringTest {
    private fun source(path: String) = listOf(
        File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")
    private val service = source("accessibility/FgoAccessibilityService.kt")
    private val runner = source("runner/FgoRunnerOverlay.kt")
    private fun section(text: String, start: String, end: String) = text.substringAfter(start).substringBefore(end)

    @Test fun `crop pauses selected workflow instead of replacing or restoring it`() {
        val prepare = section(service, "fun prepareForCropSelection()", "fun requestCropTranslation(")
        assertTrue(prepare.contains("activeRunnerContext() == null"))
        assertTrue(prepare.contains("cancelCurrentTranslation()"))
        assertTrue(prepare.contains("battleScanJob?.cancel()"))
        assertTrue(prepare.contains("battleSubtitles.pause()"))
        assertFalse(prepare.contains("setTranslationMode"))
        assertFalse(prepare.contains("setEnabled"))
        val arm = section(runner, "private fun armOneShotCropMode()", "private fun requestOneShotCropTranslation()")
        assertTrue(arm.indexOf("prepareForCropSelection()") < arm.indexOf("cropSelectionOverlay.show()"))
        assertTrue(arm.contains("cropSelectionOverlay.isShowing()"))
        assertFalse(arm.contains("setBattleModeEnabled"))
        assertFalse(arm.contains("setTranslationMode"))
        assertFalse(runner.contains("modeBeforeCrop"))
        assertFalse(runner.contains("restoreModeAfterCropSelection"))
        assertFalse(service.contains("restoreModeAfterCrop"))
    }

    @Test fun `confirmation installs ownership before removing selector and waits for all old owners`() {
        val confirm = section(runner, "private fun requestOneShotCropTranslation()", "private fun cancelCropMode()")
        assertTrue(confirm.indexOf("requestCropTranslation(Rect(bounds))") < confirm.indexOf("cropSelectionOverlay.hide()"))
        val request = section(service, "fun requestCropTranslation(", "fun clearCropTranslationOverlay()")
        assertTrue(request.indexOf("val interruptedTranslation = translationJob") < request.indexOf("cancelCurrentTranslation("))
        assertTrue(request.contains("preserveCropSelection = true"))
        assertTrue(request.contains("cropTranslationJob = launchRunnerWork"))
        for (owner in listOf("interruptedTranslation", "interruptedCropTranslation", "interruptedBattleScan")) {
            assertTrue(request.indexOf("$owner?.join()") < request.indexOf("processCropTranslation("), owner)
        }
        assertTrue(request.contains("cropVersion != stopVersion"))
    }

    @Test fun `cancel retains resource owners and admission waits for completion not only activity`() {
        val cancel = section(service, "private fun cancelCurrentTranslation(", "private fun resetVoiceOnlyStoryState()")
        assertTrue(cancel.contains("translationJob?.cancel()"))
        assertTrue(cancel.contains("cropTranslationJob?.cancel()"))
        assertFalse(cancel.contains("translationJob = null"))
        assertFalse(cancel.contains("cropTranslationJob = null"))
        assertFalse(cancel.contains("isProcessing = false"))
        val admission = section(service, "private fun canStartScreenTranslationNow()", "private fun onDialogueAdvanceObserved()")
        assertTrue(admission.contains("translationJob?.isCompleted != false"))
        assertTrue(admission.contains("!isCropBlockingOcr()"))
        assertTrue(admission.contains("cropJob = cropTranslationJob"))
        val battle = section(service, "private fun monitorBattleIfReady()", "private fun startDetectionLoop()")
        assertTrue(battle.contains("isCropBlockingOcr()"))
        val screen = section(service, "private suspend fun processScreen(", "private suspend fun processCropTranslation(")
        assertTrue(screen.contains("if (isCropBlockingOcr()) return"))
        assertFalse(screen.contains("canStartScreenTranslationNow()")) // The current job must not reject itself.
    }

    @Test fun `auto tap polling and crop replay have cancelable story owners`() {
        val storyTap = section(service, "private fun handleTranslatedOverlayTap(", "private fun isOverlayTapContextCurrent(")
        assertTrue(storyTap.contains("translationJob = launchRunnerWork"))
        assertTrue(storyTap.contains("pollNextSceneAfterTap("))
        val cropTap = section(service, "private fun handleCropResultTap(", "private fun handleTranslatedOverlayTap(")
        assertTrue(cropTap.contains("translationJob = launchRunnerWork"))
        assertTrue(cropTap.indexOf("isForwardingOverlayTap = true") < cropTap.indexOf("cropResultOverlay.hide()"))
        assertTrue(cropTap.indexOf("if (!replayContextCurrent())") < cropTap.indexOf("dispatchTapToFgo("))
        assertTrue(cropTap.contains("if (tapVersion == stopVersion) isForwardingOverlayTap = false"))
    }

    @Test fun `loading taps cannot queue a hidden manual retry`() {
        val manual = section(service, "fun requestManualTranslation(", "private fun canStartScreenTranslationNow()")
        assertTrue(manual.indexOf("cropTranslationJob?.isCompleted == false") < manual.indexOf("TranslationTrigger.requestTranslation("))
        assertTrue(manual.contains("Manual translation ignored while Crop owns OCR"))
        val click = section(runner, "private fun onButtonClick()", "private fun reportAccessibilityServiceUnavailable(")
        assertTrue(click.contains("if (cropLoadingJob?.isCompleted == false) return"))
    }

    @Test fun `mode stop and foreground cleanup cannot restore or refill old crop`() {
        val change = section(runner, "onTranslationModeChange = {", "onLiveVoiceTranslationToggle = {")
        assertTrue(change.indexOf("cancelCropMode()") < change.indexOf("accessibility.setTranslationMode(mode)"))
        assertTrue(change.substringAfter("onBattleModeSelect = {").contains("cancelCropMode()"))
        val foreground = section(service, "private fun markFgoForegroundLost(", "private fun restoreFgoForegroundAfterCapture(")
        assertTrue(foreground.contains("isCropBlockingOcr()"))
        assertTrue(foreground.contains("cancelCurrentTranslation()"))
        val status = section(service, "private fun showCropStatus(", "private fun clippedCropBounds(")
        assertTrue(status.contains("processingVersion != stopVersion"))
        assertTrue(status.contains("!isEffectiveFgoForeground"))
        val process = section(service, "private suspend fun processCropTranslation(", "/** Crop status text")
        assertTrue(process.indexOf("processingVersion != stopVersion", process.indexOf("val rendered =")) < process.indexOf("cropResultOverlay.show("))
    }

    @Test fun `ui cleanup is non recursive and hiding a window never restores a mode`() {
        val cleanup = section(runner, "fun clearCropSelectionUi()", "private fun isJapaneseServer()")
        assertTrue(cleanup.contains("cropSelectionOverlay.hide()"))
        assertFalse(cleanup.contains("FgoAccessibilityService"))
        assertFalse(cleanup.contains("setTranslationMode"))
        val overlay = source("crop/CropResultOverlay.kt")
        val hide = section(overlay, "fun hide()", "fun destroy()")
        assertFalse(hide.contains("resume"))
        assertFalse(hide.contains("setTranslationMode"))
        val failedShow = section(overlay, "wm.addView(newRoot, layoutParams())", "rootView = newRoot")
        assertTrue(failedShow.contains("bitmap.recycle()"))
    }
}
