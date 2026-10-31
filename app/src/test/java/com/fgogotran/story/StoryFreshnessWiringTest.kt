package com.fgogotran.story

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source-wiring guards for the Android coordinator; capture/visual behaviour needs device validation. */
class StoryFreshnessWiringTest {
    private val service = listOf(
        File("src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt"),
        File("app/src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(service).drop(1)
            .first { it.startsWith("$name(") }

    private fun call(body: String, name: String): String {
        val start = body.indexOf("$name(")
        check(start >= 0) { "Missing call to $name" }
        var depth = 1
        var end = start + name.length + 1
        while (depth > 0 && end < body.length) {
            when (body[end++]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        check(depth == 0) { "Unclosed call to $name" }
        return body.substring(start, end)
    }

    @Test
    fun `capture time is request local and starts after a successful screenshot before OCR`() {
        val screen = function("processScreen")
        val capture = screen.indexOf("screenshot = takeScreenshotCompat(timingLabel = mode.name)")
        val success = screen.indexOf("val source = screenshot")
        val timestamp = screen.indexOf("val sourceCapturedAtElapsedMs = SystemClock.elapsedRealtime()")
        val frame = screen.indexOf("val frame = StoryFramePixels(source.framePixels())")
        assertTrue(capture >= 0 && capture < success)
        assertTrue(screen.substring(capture, success).contains("if (screenshot == null)"))
        assertTrue(success < timestamp && timestamp < frame)
        assertFalse(Regex("(?m)^    private (?:val|var) sourceCapturedAtElapsedMs").containsMatchIn(service))
        assertTrue(screen.indexOf("val processStartedAt = SystemClock.elapsedRealtime()") < capture)
    }

    @Test
    fun `all translated story modes carry their screenshot time into the shared renderer`() {
        val screen = function("processScreen")
        for (method in listOf(
            "processManualScreen", "processSemiAutoChoiceScreen", "processSemiAutoStoryScreen", "processAutoScreen"
        )) {
            assertTrue(call(screen, method).contains("sourceCapturedAtElapsedMs = sourceCapturedAtElapsedMs"), method)
            val body = function(method)
            assertTrue(body.substringBefore(") {").contains("sourceCapturedAtElapsedMs: Long"), method)
            val expected = if (method == "processSemiAutoChoiceScreen") {
                "recognitionCapturedAtElapsedMs"
            } else {
                "sourceCapturedAtElapsedMs"
            }
            assertTrue(call(body, "translateAndRenderScene").contains("sourceCapturedAtElapsedMs = $expected"), method)
        }
        assertTrue(function("translateAndRenderScene").substringBefore("): Boolean").contains("sourceCapturedAtElapsedMs: Long"))
    }

    @Test
    fun `successful choice retry replaces both the source frame and its capture time`() {
        val choices = function("processSemiAutoChoiceScreen")
        assertTrue(choices.contains("var recognitionCapturedAtElapsedMs = sourceCapturedAtElapsedMs"))
        val retry = choices.substringAfter("if (retryScreenshot != null) {")
            .substringBefore("val retryScreenRegions")
        val timestamp = retry.indexOf("recognitionCapturedAtElapsedMs = SystemClock.elapsedRealtime()")
        val adopt = retry.indexOf("frames.useRetry(retryScreenshot)")
        val pixels = retry.indexOf("recognitionFrame = StoryFramePixels(retryScreenshot.framePixels())")
        assertTrue(timestamp >= 0 && timestamp < adopt && adopt < pixels)
        assertEquals(2, Regex("recognitionCapturedAtElapsedMs = ").findAll(choices).count())
        val render = call(choices, "translateAndRenderScene")
        assertTrue(render.contains("source = recognitionSource"))
        assertTrue(render.contains("sourceCapturedAtElapsedMs = recognitionCapturedAtElapsedMs"))
        assertTrue(render.contains("frame = recognitionFrame"))
        assertTrue(choices.contains("frames.close()"))
    }

    @Test
    fun `freshness uses source age at the existing 800ms boundary and preserves forced AUTO checks`() {
        val render = function("translateAndRenderScene")
        assertTrue(service.contains("private const val FRESHNESS_CHECK_SOURCE_AGE_MS = 800L"))
        assertTrue(render.contains("val sourceAgeMs = SystemClock.elapsedRealtime() - sourceCapturedAtElapsedMs"))
        assertTrue(render.contains("val forceVisualFreshness = mode == ProcessingMode.AUTO_BACKGROUND && isAutoTapHandoffActive()"))
        assertTrue(render.contains("val shouldCheckFreshness = sourceAgeMs >= FRESHNESS_CHECK_SOURCE_AGE_MS || forceVisualFreshness"))
        assertTrue(render.indexOf("val sourceAgeMs") > render.indexOf("val layoutDuration"))
        assertTrue(render.contains("if (shouldCheckFreshness) {"))
        assertTrue(render.contains("if (!shouldCheckFreshness) {"))
        assertFalse(service.contains("FRESHNESS_CHECK_TRANSLATION_DELAY"))
        assertFalse(render.contains("resultBuildDuration"))
    }

    @Test
    fun `existing visual comparison rejects stale sources before overlay display`() {
        val render = function("translateAndRenderScene")
        val check = render.substringAfter("if (shouldCheckFreshness) {")
            .substringBefore("if (!shouldCheckFreshness)")
        assertTrue(check.contains("visualFingerprintFor(source, sceneSource.regions.map { it.region }, frame.pixels)"))
        assertTrue(check.contains("val isCurrent = isSourceVisuallyCurrent(sourceVisualFingerprint, expectedAutoStage = autoStage)"))
        assertTrue(check.substringAfter("if (!isCurrent) {").contains("return false"))
        assertTrue(render.indexOf("isSourceVisuallyCurrent(") < render.indexOf("translationOverlay.updateImage(rendered)"))
        val compare = function("isSourceVisuallyCurrent")
        assertTrue(compare.contains("takeScreenshotCompat(timingLabel = \"FRESHNESS\") ?: return false"))
        assertTrue(compare.contains("masksAreSimilar(sample.mask, currentMask)"))
        assertTrue(compare.contains("currentScreenshot.recycle()"))
        for (forbidden in listOf("ocrEngine.recognize(", "translateSceneSource(", "translator.", "while (")) {
            assertFalse(compare.contains(forbidden), forbidden)
        }
    }

    @Test
    fun `decision logging explains source age without changing existing pipeline timing fields`() {
        val render = function("translateAndRenderScene")
        for (field in listOf("Freshness decision (", "sourceAge=", "threshold=", "forced=", "check=")) {
            assertTrue(render.contains(field), field)
        }
        for (field in listOf("ocr=", "translate=", "layout=", "freshness=", "choiceMask=", "render=", "overlay=", "total=")) {
            assertTrue(render.substringAfter("Pipeline ready (").contains(field), field)
        }
        assertFalse(render.contains("Fast translation"))
        assertFalse(render.contains("without OCR recheck"))
    }

    @Test
    fun `read hold delay manual refresh and non story pipelines keep their existing policy`() {
        assertTrue(service.contains("private const val TAP_TRANSLATION_READ_HOLD_DELAY = 120L"))
        assertTrue(function("translateAndRenderScene").contains("forceFreshTranslation = mode == ProcessingMode.MANUAL_TAP"))
        for (method in listOf("processCropTranslation", "processVoiceOnlyScreen", "monitorBattleIfReady", "takeScreenshotCompat")) {
            assertFalse(function(method).contains("sourceCapturedAtElapsedMs"), method)
            assertFalse(function(method).contains("shouldCheckFreshness"), method)
        }
    }
}
