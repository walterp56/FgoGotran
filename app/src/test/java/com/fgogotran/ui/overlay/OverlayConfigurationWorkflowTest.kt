package com.fgogotran.ui.overlay

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Configuration/lifecycle wiring; viewport and menu measurements have separate JVM tests.
 * Native WindowManager and Android resource delivery still require device verification.
 */
class OverlayConfigurationWorkflowTest {
    @Test
    fun `runner refresh key covers language density and font but not game scaling`() {
        val runner = source("runner/FgoRunnerOverlay.kt")
        val key = runner.substringAfter("private data class OverlayUiConfiguration(")
            .substringBefore("\n    )")
        for (field in listOf("languageTag: String", "densityDpi: Int", "fontScale: Float")) {
            assertTrue(key.contains(field), field)
        }
        assertFalse(key.contains("viewport"))
        val snapshot = function(runner, "currentUiConfiguration")
        assertTrue(snapshot.contains("AppLanguageManager.effectiveLanguageTag(context)"))
        assertTrue(snapshot.contains("configuration.densityDpi"))
        assertTrue(snapshot.contains("configuration.fontScale"))
        assertTrue(runner.contains("previous == configuration || !shown"))
    }

    @Test
    fun `configuration and app language picker use the same refresh path`() {
        val runner = source("runner/FgoRunnerOverlay.kt")
        val callback = runner.substringAfter("override fun onConfigurationChanged")
            .substringBefore("override fun onLowMemory")
        assertTrue(callback.contains("handleScreenBoundsChanged()"))
        assertTrue(callback.contains("refreshHostsForConfigurationChange()"))
        assertTrue(function(runner, "refreshUiLanguage").contains("refreshHostsForConfigurationChange()"))
        assertTrue(function(runner, "show").contains("appliedUiConfiguration = currentUiConfiguration()"))
        assertTrue(function(runner, "destroy").contains("appliedUiConfiguration = null"))
    }

    @Test
    fun `host refresh retains workflow preferences and session history`() {
        val runner = source("runner/FgoRunnerOverlay.kt")
        val rebuild = function(runner, "rebuildHostsForConfiguration")
        assertTrue(rebuild.contains("dismissMenu()"))
        assertTrue(rebuild.contains("dismissHistoryPanel()"))
        assertTrue(rebuild.contains("previousHost.close()"))
        assertTrue(rebuild.contains("FakeComposeHost(context) { FloatingButtonContent() }"))
        assertTrue(rebuild.indexOf("clampButtonPositionToScreen()") < rebuild.indexOf("wm.addView("))
        assertTrue(rebuild.contains("observeControlLayout(host.view)"))
        for (reset in listOf("restoreLastTranslationMode(", "setTranslationMode(", "cancelCropMode(",
            "settingsRepository.", "SessionTranslationHistory.clear(", "buttonSizeDp =", "btnX =", "btnY =")) {
            assertFalse(rebuild.contains(reset), reset)
        }
        assertFalse(function(runner, "dismissHistoryPanel").contains("SessionTranslationHistory.clear("))
    }

    @Test
    fun `translation callback is registered once and released with pending work`() {
        val overlay = source("overlay/TranslationOverlay.kt")
        assertTrue(function(overlay, "init").contains("if (!callbacksRegistered)"))
        assertTrue(function(overlay, "init").contains("context.registerComponentCallbacks(componentCallbacks)"))
        val callback = overlay.substringAfter("override fun onConfigurationChanged")
            .substringBefore("override fun onLowMemory")
        assertTrue(callback.contains("mainHandler.post { refreshScreenBounds() }"))
        val destroy = function(overlay, "destroy")
        assertTrue(destroy.contains("context.unregisterComponentCallbacks(componentCallbacks)"))
        assertTrue(destroy.contains("mainHandler.removeCallbacksAndMessages(null)"))
    }

    @Test
    fun `pixel resize clears both visible and capture-retained images`() {
        val overlay = source("overlay/TranslationOverlay.kt")
        val resize = function(overlay, "refreshScreenBounds")
        assertTrue(resize.contains("windowManager?.currentWindowMetrics?.bounds"))
        assertTrue(resize.contains("width == screenWidth && height == screenHeight"))
        assertTrue(resize.contains("screenWidth = width"))
        assertTrue(resize.contains("screenHeight = height"))
        assertTrue(resize.contains("hideOverlayView(clearBitmap = true)"))
        assertFalse(resize.contains("density"))
        assertFalse(resize.contains("fontScale"))
        val hide = function(overlay, "hideOverlayView")
        assertTrue(hide.contains("it.setImageBitmap(null)"))
        assertTrue(hide.contains("latestTranslatedBitmap = null"))
        assertTrue(hide.contains("setTranslatedOverlayTouchable(false)"))
    }

    @Test
    fun `capture restore rechecks current screen before reusing a retained bitmap`() {
        val restore = function(source("overlay/TranslationOverlay.kt"), "restoreAfterCapture")
        assertTrue(restore.indexOf("refreshScreenBounds()") < restore.indexOf("if (isOverlayShowing)"))
        assertTrue(restore.indexOf("refreshScreenBounds()") < restore.indexOf("latestTranslatedBitmap?.let"))
        assertTrue(restore.contains("showOrUpdateTranslatedImage(it)"))
    }

    @Test
    fun `old sized in-flight output cannot be stretched or committed as showing`() {
        val overlay = source("overlay/TranslationOverlay.kt")
        val show = function(overlay, "showOrUpdateTranslatedImage")
        val reject = show.substringAfter("if (bitmap.width != screenWidth || bitmap.height != screenHeight)")
            .substringBefore("overlayView?.let")
        assertTrue(reject.contains("hideOverlayView(clearBitmap = true)"))
        assertTrue(reject.contains("return"))
        assertTrue(show.indexOf("refreshScreenBounds()") < show.indexOf("if (bitmap.width"))
        assertTrue(show.indexOf("if (bitmap.width") < show.indexOf("latestTranslatedBitmap = bitmap"))
        assertFalse(show.contains("screenWidth = bitmap.width"))
        assertFalse(show.contains("screenHeight = bitmap.height"))
        assertFalse(function(overlay, "updateImage").contains("latestTranslatedBitmap = bitmap"))
        assertFalse(function(overlay, "showTranslatedImage").contains("latestTranslatedBitmap = bitmap"))
    }

    @Test
    fun `existing window updates geometry even when touchable flag is unchanged`() {
        val overlay = source("overlay/TranslationOverlay.kt")
        val update = function(overlay, "setTranslatedOverlayTouchable")
        assertTrue(update.contains("val currentParams = view.layoutParams"))
        assertTrue(update.contains("currentParams?.width == screenWidth"))
        assertTrue(update.contains("currentParams.height == screenHeight"))
        assertTrue(update.contains("wm.updateViewLayout(view, params)"))
        assertTrue(update.contains("updateFloatingHandleGestureExclusion()"))
        val show = function(overlay, "showOrUpdateTranslatedImage").substringAfter("overlayView?.let")
        assertTrue(show.indexOf("if (!setTranslatedOverlayTouchable(true)) return") < show.indexOf("it.setImageBitmap(bitmap)"))
    }

    @Test
    fun `subtitle refresh reuses text history font preference and orientation placement`() {
        val voice = source("speech/VoiceSubtitleOverlay.kt")
        val refresh = function(voice, "onDisplayChanged")
        for (step in listOf("applyViewSizing(view)", "updateMaximumWidth(view)", "updateDisplayText(view)",
            "view.requestLayout()", "if (textView === view)", "placeView(view, useConfiguredPosition = true)")) {
            assertTrue(refresh.contains(step), step)
        }
        for (reset in listOf("clearDisplayText(", "subtitleHistory.clear(", "fontSizeSp =", "portraitPosition =",
            "landscapePosition =", "active.set(", "cancelScheduledHide(")) {
            assertFalse(refresh.contains(reset), reset)
        }
        val sizing = function(voice, "applyViewSizing")
        assertTrue(sizing.contains("setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())"))
        assertTrue(sizing.contains("setPadding(dp(10), dp(6), dp(10), dp(6))"))
        assertTrue(sizing.contains("cornerRadius = dp(10).toFloat()"))
        assertTrue(sizing.contains("ViewConfiguration.get(context).scaledTouchSlop"))
        assertTrue(function(voice, "ensureView").contains("applyViewSizing(this)"))
    }

    @Test
    fun `subtitle configuration refresh neither reconnects voice nor adds OCR or API work`() {
        val voice = source("speech/VoiceSubtitleOverlay.kt")
        assertTrue(voice.contains("override fun onConfigurationChanged(newConfig: Configuration) = onDisplayChanged()"))
        assertTrue(function(voice, "ensureView").contains("context.registerComponentCallbacks(componentCallbacks)"))
        assertTrue(function(voice, "destroy").contains("context.unregisterComponentCallbacks(componentCallbacks)"))
        val controller = function(source("speech/RealtimeVoiceTranslationController.kt"), "onDisplayChanged")
        assertTrue(controller.contains("subtitleOverlay.onDisplayChanged()"))
        assertFalse(controller.contains("start("))
        assertFalse(controller.contains("stop("))
        for (path in listOf("runner/FgoRunnerOverlay.kt", "overlay/TranslationOverlay.kt", "speech/VoiceSubtitleOverlay.kt")) {
            val text = source(path)
            val callback = text.substringAfter("override fun onConfigurationChanged")
                .substringBefore("override fun onLowMemory")
            for (work in listOf("requestTranslation(", "requestManualTranslation(", "recognize(", "speak(", "start(")) {
                assertFalse(callback.contains(work), "$path: $work")
            }
        }
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(source: String, name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(source).drop(1)
            .first { it.startsWith("$name(") }
}
