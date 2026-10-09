package com.fgogotran.ui.overlay

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wiring checks for the Android window and touch paths; geometry has pure JVM tests. */
class FloatingButtonWorkflowTest {
    @Test
    fun `docking and safe drag bounds are independent of the selected mode`() {
        val docking = source("ui/overlay/FloatingButtonDocking.kt")
        assertFalse(docking.contains("FloatingButtonMode"))
        val runner = source("runner/FgoRunnerOverlay.kt")
        val start = function(runner, "beginButtonDrag")
        assertTrue(start.contains("WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()"))
        assertTrue(start.contains("dockedSide != null, dockViewport)"))
        assertFalse(start.contains("buttonMode"))
        assertFalse(function(runner, "finishButtonDrag").contains("buttonMode"))
        assertFalse(function(runner, "updateButtonMode").contains("restoreDockedButton"))
    }

    @Test
    fun `docked tap restores before crop battle or translation actions`() {
        val click = function(source("runner/FgoRunnerOverlay.kt"), "onButtonClick")
        val reveal = click.substringAfter("if (dockedSide != null) {")
            .substringBefore("if (cropModeState")
        assertTrue(reveal.contains("restoreDockedButton()"))
        assertTrue(reveal.contains("return"))
        assertFalse(reveal.contains("requestOneShotCropTranslation"))
        assertFalse(reveal.contains("requestManualTranslation"))
        assertFalse(reveal.contains("TranslationTrigger.requestTranslation"))
        assertTrue(click.indexOf("restoreDockedButton()") < click.indexOf("battleModeState.active.value"))
        assertTrue(click.indexOf("restoreDockedButton()") < click.indexOf("TranslationTrigger.canUserTapTranslate()"))
    }

    @Test
    fun `native and intercepted taps use the same shared action`() {
        val button = source("ui/overlay/FloatingButton.kt")
        val tap = button.substringAfter("tapReleased -> {").substringBefore("cancelled ->")
        assertTrue(tap.contains("currentOnClick()"))
        assertFalse(tap.contains("gestureDockedSide"))
        val routed = function(source("runner/FgoRunnerOverlay.kt"), "handleInterceptedButtonTap")
        assertTrue(routed.contains("if (!isPointInsideButton(rawX, rawY)) return false"))
        assertTrue(routed.contains("onButtonClick()"))
        assertTrue(routed.contains("return true"))
        assertFalse(routed.contains("dockedSide"))
    }

    @Test
    fun `edge reveal retains the stable raw coordinate detector and cancellation guards`() {
        val button = source("ui/overlay/FloatingButton.kt")
        assertTrue(button.contains(".motionEventSpy(observeRawTouch)"))
        assertTrue(button.contains(".pointerInput(view)"))
        assertFalse(button.contains("pointerInput(dockedSide"))
        assertTrue(button.contains("rememberUpdatedState(dockedSide)"))
        assertTrue(button.contains("val dragSlop = max(viewConfiguration.touchSlop, 18.dp.toPx())"))
        assertTrue(button.contains("if (gestureDockedSide == null) {\n                            withTimeoutOrNull"))
        assertTrue(button.contains("event.changes.count { it.pressed } > 1"))
        assertTrue(button.contains("if (started) currentOnDragEnd(!released)"))
        assertTrue(button.contains("change.position.x < size.width"))
        assertTrue(button.contains("change.position.y < size.height"))
        assertTrue(button.contains("cancelled = !tapReleased"))
    }

    @Test
    fun `handle touch is consumed before story advance or crop result dismissal`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val translatedTouch = function(service, "handleTranslatedOverlayTouch")
        assertTrue(translatedTouch.contains("handleRenderedOverlayButtonTouch(event)"))
        assertTrue(translatedTouch.contains("if (consumed) storyTapHandoff.cancelTouch()"))
        assertTrue(function(service, "handleCropResultOverlayTouch")
            .contains("return handleRenderedOverlayButtonTouch(event)"))
        val touch = function(service, "handleRenderedOverlayButtonTouch")
        assertTrue(touch.contains("if (!runnerOverlay.isButtonDocked())"))
        val up = touch.substringAfter("MotionEvent.ACTION_UP ->")
            .substringBefore("MotionEvent.ACTION_POINTER_DOWN ->")
        assertTrue(up.contains("runnerOverlay.handleInterceptedButtonTap(x, y)"))
        assertTrue(up.contains("true"))
        assertFalse(up.contains("hideInterceptingOverlay()"))
        assertFalse(up.contains("dispatchTapToFgo"))
    }

    @Test
    fun `edge hiding uses the dock state without cancelling crop or changing pipeline state`() {
        val runner = source("runner/FgoRunnerOverlay.kt")
        for (name in listOf("finishButtonDrag", "restoreDockedButton")) {
            val body = function(runner, name)
            assertFalse(body.contains("cancelCropMode("))
            assertFalse(body.contains("TranslationTrigger."))
            assertFalse(body.contains("cropSelectionOverlay.hide("))
        }
        assertTrue(function(runner, "show").contains("dockedSide = null"))
        assertTrue(function(runner, "handleScreenBoundsChanged").contains("restoreDockedButton(relayout = false)"))
        assertTrue(function(runner, "startButtonSizeObserver").contains("restoreDockedButton()"))
        assertTrue(function(runner, "restoreDockedButton").contains("publishDockHandleBounds()"))
        assertTrue(function(runner, "restoreDockedButton").contains("updateButtonLayout()"))
    }

    @Test
    fun `blue grey button keeps the existing menu and failure feedback`() {
        val button = source("ui/overlay/FloatingButton.kt")
        assertTrue(button.contains("val baseColor = Color(0xFF182335)"))
        assertTrue(button.contains("val idleAlpha = 0.80f"))
        assertTrue(button.contains("val pressedAlpha = 0.95f"))
        assertTrue(button.contains("val glyphColor = Color(0xFFF1F4F8).copy(alpha = if (pressed) 1f else 0.95f)"))
        assertEquals(2, Regex("(?:contentColor|color) = glyphColor").findAll(button).count())
        assertTrue(button.contains("BorderStroke(1.dp, Color(0xFFA8B8CE).copy(alpha = 0.45f))"))
        assertTrue(button.contains("if (showFailureRing) BorderStroke(3.dp, failureColor) else"))
        assertTrue(button.contains("baseColor.copy(alpha = 0.65f)"))
        assertTrue(button.contains("if (showFailureRing) failureColor else Color(0xFFCBD8E8).copy(alpha = 0.90f)"))
        assertTrue(button.contains("FloatingButtonMode.CROP -> FloatingActionIcon.CROP"))
        val runner = source("runner/FgoRunnerOverlay.kt")
        assertTrue(runner.contains("FAILURE_FEEDBACK_MS = 1800L"))
        val failure = function(runner, "showTranslationFailureFeedback")
        assertTrue(failure.contains("!fromUserTap || !TranslationTrigger.canUserTapTranslate()"))
        assertFalse(failure.contains("restoreDockedButton"))
        val menu = source("ui/overlay/FloatingArcMenu.kt")
        assertTrue(menu.contains("private val MENU_ACCENT_COLOR = Color(0xFF76518F)"))
        assertEquals(3, Regex("MENU_ACCENT_COLOR").findAll(menu).count())
        assertFalse(menu.contains("Color(0xFF075F66)"))
        assertTrue(menu.contains("SCRIM_ALPHA = 0.18f"))
    }

    private fun source(relativePath: String): String = listOf(
        File("src/main/java/com/fgogotran/$relativePath"),
        File("app/src/main/java/com/fgogotran/$relativePath")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(source: String, name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(source).drop(1)
            .first { it.startsWith("$name(") }
}
