package com.fgogotran.accessibility

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source ownership guards only; actual Android hardware-buffer behavior needs device validation. */
class ScreenshotResourceWiringTest {
    private val service by lazy {
        listOf(
            File("src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt"),
            File("app/src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt")
        ).first(File::isFile).readText().replace("\r\n", "\n")
    }

    @Test fun `hardware wrapper is recycled after software copy and buffer always closes`() {
        val success = service.substringAfter("override fun onSuccess(result: ScreenshotResult)")
            .substringBefore("override fun onFailure(errorCode: Int)")
        val wrap = success.indexOf("hardwareBitmap = Bitmap.wrapHardwareBuffer(")
        val copy = success.indexOf("hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)")
        val cleanup = success.indexOf("hardwareBitmap?.recycle()")
        val close = success.indexOf("result.hardwareBuffer.close()")
        assertTrue(wrap >= 0 && wrap < copy && copy < cleanup && cleanup < close)
        assertTrue(success.contains("try {\n                                    hardwareBitmap?.recycle()\n                                } finally {\n                                    result.hardwareBuffer.close()"))
        assertEquals(1, Regex("hardwareBitmap\\?\\.recycle\\(\\)").findAll(success).count())
        assertFalse(success.contains("cont.resume(hardwareBitmap"))
    }

    @Test fun `software copy retains cancellation ownership and screenshot request is unchanged`() {
        val capture = service.substringAfter("private suspend fun captureAccessibilityScreenshot():")
            .substringBefore("private fun refreshAccessibilityConnectionState")
        assertTrue(capture.contains("ensureRunnerWorkCurrent()"))
        assertTrue(capture.contains("withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS)"))
        assertTrue(capture.contains("Display.DEFAULT_DISPLAY,\n                    mainExecutor,"))
        assertTrue(capture.contains("cont.isActive && admitted === activeRunnerContext()"))
        assertTrue(capture.contains("cont.resume(converted, onCancellation = { _, value, _ -> value?.recycle() })"))
        assertTrue(capture.contains("converted?.recycle()\n                                cont.cancel("))
        assertEquals(1, Regex("takeScreenshot\\(").findAll(capture).count())
    }

    @Test fun `dark frame remains a qualified diagnostic and does not reject or retry`() {
        val report = service.substringAfter("private fun reportBlankAccessibilityFrameIfNeeded(")
            .substringBefore("private fun hasVisiblePixels(")
        assertTrue(report.contains("if (hasVisiblePixels(frame)) return"))
        assertTrue(report.contains("title = \"截图疑似全黑\""))
        assertTrue(report.contains("可能是游戏黑场"))
        assertTrue(report.contains("diagnosticEventStore.record("))
        for (operation in listOf("frame.recycle(", "takeScreenshot(", "delay(", "throw ")) {
            assertFalse(report.contains(operation), operation)
        }
        val secure = service.substringAfter("AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->")
            .substringBefore("SCREENSHOT_ERROR_TIMEOUT ->")
        assertTrue(secure.contains("系统标记了安全画面，应用无法读取截图。请避开受保护页面后再试。"))
    }
}
