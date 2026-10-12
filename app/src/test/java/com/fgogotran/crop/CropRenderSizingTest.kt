package com.fgogotran.crop

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure sizing tests plus coordinator wiring checks; native Canvas output still needs device QA. */
class CropRenderSizingTest {
    @Test
    fun `1080p source size retains the existing limits and height ratios`() {
        val sizing = CropRenderSizing(1f)
        assertEquals(10f, sizing.sourceTextSize(1f))
        assertEquals(44f, sizing.sourceTextSize(40f), 0.0001f)
        assertEquals(72f, sizing.sourceTextSize(100f))
        assertEquals(32.8f, sizing.minimumSourceTextSize(40f, 44f), 0.0001f)
        assertEquals(44f, sizing.minimumSourceTextSize(100f, 44f))
    }

    @Test
    fun `four K raises the old 72 pixel cap without doubling OCR heights again`() {
        val sizing = CropRenderSizing(FgoViewportGeometry.referenceScale(3840, 2400))
        assertEquals(20f, sizing.minimumRowTextSize)
        assertEquals(66f, sizing.sourceTextSize(60f), 0.0001f)
        assertEquals(132f, sizing.sourceTextSize(120f), 0.0001f)
        assertEquals(144f, sizing.sourceTextSize(200f))
    }

    @Test
    fun `row font limits remain proportional from 720p through eight K`() {
        listOf(0.5f, 2f / 3f, 1f, 4f / 3f, 2f, 4f).forEach { scale ->
            val sizing = CropRenderSizing(scale)
            for (referenceHeight in listOf(1f, 40f, 60f, 100f)) {
                val size = sizing.sourceTextSize(referenceHeight * scale)
                val expected = CropRenderSizing(1f).sourceTextSize(referenceHeight) * scale
                assertEquals(expected, size, 0.0001f)
                assertEquals(
                    CropRenderSizing(1f).minimumSourceTextSize(referenceHeight, expected / scale) * scale,
                    sizing.minimumSourceTextSize(referenceHeight * scale, size),
                    0.0001f
                )
            }
        }
    }

    @Test
    fun `1080p row layout keeps the existing source alignment and padding`() {
        val sizing = CropRenderSizing(1f)
        val source = CropRenderRect(100f, 50f, 300f, 90f)
        val area = sizing.textArea(source, 600, 200)
        assertRect(CropRenderRect(90.4f, 44.4f, 309.6f, 95.6f), area)
        assertEquals(491.6f, sizing.maximumTextWidth(area, 600), 0.0001f)
        assertRect(
            CropRenderRect(83.36f, 40.88f, 347.44f, 95.6f),
            sizing.clearBox(source, area, 250f, 44f, 600, 200)
        )
    }

    @Test
    fun `source aligned row and clear panel scale together without moving the source`() {
        val source = CropRenderRect(100f, 60f, 300f, 90f)
        val sizing = CropRenderSizing(1f)
        val area = sizing.textArea(source, 600, 300)
        val clear = sizing.clearBox(source, area, 250f, 33f, 600, 300)
        listOf(0.5f, 2f / 3f, 4f / 3f, 2f, 4f).forEach { scale ->
            val scaled = CropRenderSizing(scale)
            val scaledSource = source.scaled(scale)
            val width = (600 * scale).roundToInt()
            val height = (300 * scale).roundToInt()
            val scaledArea = scaled.textArea(scaledSource, width, height)
            assertRect(area.scaled(scale), scaledArea)
            assertRect(clear.scaled(scale), scaled.clearBox(scaledSource, scaledArea, 250f * scale, 33f * scale, width, height))
            assertEquals(sizing.maximumTextWidth(area, 600) * scale, scaled.maximumTextWidth(scaledArea, width), 0.0002f)
            assertEquals(source.scaled(scale), scaledSource)
        }
    }

    @Test
    fun `fallback panel scales its lower and upper bounds and decorations`() {
        val baseline = CropRenderSizing(1f)
        val doubled = CropRenderSizing(2f)
        assertEquals(6f, baseline.panelPadding(100, 50))
        assertEquals(12f, doubled.panelPadding(200, 100))
        assertEquals(24f, baseline.panelPadding(1000, 1000))
        assertEquals(48f, doubled.panelPadding(2000, 2000))
        assertEquals(180f, baseline.maximumPanelTextSize(1000f, 1000f))
        assertEquals(360f, doubled.maximumPanelTextSize(2000f, 2000f))
        assertEquals(4f, baseline.cornerRadius(10f, 10f))
        assertEquals(8f, doubled.cornerRadius(20f, 20f))
        assertEquals(9f, baseline.cornerRadius(100f, 100f))
        assertEquals(18f, doubled.cornerRadius(200f, 200f))
        assertEquals(4f, doubled.px(2f))
    }

    @Test
    fun `source size does not depend on the selected crop dimensions`() {
        val sizing = CropRenderSizing(2f)
        val source = CropRenderRect(20f, 20f, 200f, 80f)
        val firstSize = sizing.sourceTextSize(source.height)
        sizing.textArea(source, 600, 300)
        sizing.textArea(source, 1800, 900)
        assertEquals(firstSize, sizing.sourceTextSize(source.height))
        assertEquals(66f, firstSize, 0.0001f)
    }

    @Test
    fun `tiny crops still have positive bounded row and clear rectangles`() {
        for (scale in listOf(0.5f, 1f, 2f, 4f)) {
            val sizing = CropRenderSizing(scale)
            for ((width, height) in listOf(1 to 1, 2 to 3, 10 to 5, 32 to 32, 600 to 200)) {
                for (source in listOf(
                    CropRenderRect(0f, 0f, 1f, 1f),
                    CropRenderRect(0f, 0f, width.toFloat(), height.toFloat()),
                    CropRenderRect(width - 1f, height - 1f, width.toFloat(), height.toFloat())
                )) {
                    val area = sizing.textArea(source, width, height)
                    assertInside(area, width, height)
                    assertInside(sizing.clearBox(source, area, width * 2f, height * 2f, width, height), width, height)
                    assertTrue(sizing.panelPadding(width, height) * 2f < minOf(width, height))
                }
            }
        }
    }

    @Test
    fun `invalid reference scales are rejected rather than silently using device density`() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { CropRenderSizing(scale) }
        }
    }

    @Test
    fun `small status near bottom right is moved fully on screen`() {
        assertEquals(
            FgoReferenceRect(1740, 984, 1920, 1080),
            cropStatusPanelBounds(FgoReferenceRect(1900, 1070, 1920, 1080), 1920, 1080, 180, 96)
        )
    }

    @Test
    fun `wrapped status can grow without changing bitmap proportions or leaving screen`() {
        val initial = cropStatusPanelBounds(FgoReferenceRect(1900, 1070, 1920, 1080), 1920, 1080, 180, 96)
        val grown = cropStatusPanelBounds(initial, 1920, 1080, 180, 360)
        assertEquals(FgoReferenceRect(1740, 720, 1920, 1080), grown)
        assertEquals(180, grown.width)
        assertEquals(360, grown.height)
    }

    @Test
    fun `invalid and oversized status bounds are clipped to screen`() {
        assertEquals(
            FgoReferenceRect(0, 0, 100, 50),
            cropStatusPanelBounds(FgoReferenceRect(-100, -50, 1000, 800), 100, 50, 180, 96)
        )
        assertEquals(
            FgoReferenceRect(0, 0, 180, 96),
            cropStatusPanelBounds(FgoReferenceRect(-100, -50, -110, -70), 1920, 1080, 180, 96)
        )
    }

    @Test
    fun `coordinator passes full screenshot scale while keeping crop OCR and request unchanged`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
            .substringAfter("private suspend fun processCropTranslation(")
            .substringBefore("/** Crop status text")
        assertTrue(service.contains("FgoViewportLayout.viewportScaleForScreen(screenshot.width, screenshot.height)"))
        assertTrue(service.contains("referenceScale = cropReferenceScale"))
        assertTrue(service.contains("boundingBox = Rect(it.boundingBox)"))
        assertTrue(service.contains("cropBitmap.width * CROP_OCR_SCALE"))
        assertTrue(service.contains("inputScale = if (scaledForOcr != null) OcrInputScale.X2 else OcrInputScale.X1"))
        assertTrue(service.contains("coordinateScale = cropOcrScale"))
        assertEquals(1, Regex("ocrEngine\\.recognize\\(").findAll(service).count())
        assertEquals(1, Regex("translator\\.translate\\(").findAll(service).count())
        assertTrue(service.contains("preserveRubyMeaning = false"))
        assertTrue(service.contains("useTranslationCache = false"))
    }

    @Test
    fun `no bounds fallback retains full screenshot scale and source bounds are not rescaled`() {
        val renderer = source("crop/CropResultRenderer.kt")
        assertTrue(renderer.contains("render(bitmapWidth, bitmapHeight, text, referenceScale, textColor, targetLocale)"))
        assertTrue(renderer.contains("val sourceHeight = row.sourceBounds.height().coerceAtLeast(1).toFloat()"))
        assertTrue(renderer.contains("val targetSize = sizing.sourceTextSize(sourceHeight)"))
        val gameRender = renderer.substringBefore("fun renderStatus(")
        assertFalse(gameRender.contains("COMPLEX_UNIT_SP"))
        assertFalse(gameRender.contains("displayMetrics"))
        assertFalse(source("crop/CropRenderSizing.kt").contains("displayMetrics"))
    }

    @Test
    fun `status uses Android sp and on screen bounds rather than story fit to fill`() {
        val renderer = source("crop/CropResultRenderer.kt")
        val status = renderer.substringAfter("fun renderStatus(").substringBefore("private fun drawTranslatedLine(")
        assertTrue(status.contains("TypedValue.COMPLEX_UNIT_SP, 16f, metrics"))
        assertTrue(status.contains("Typeface.DEFAULT"))
        assertTrue(status.contains("wrapText(text, maxWidth, wordWrap = true)"))
        assertFalse(status.contains("fitLines("))
        val coordinator = source("accessibility/FgoAccessibilityService.kt")
            .substringAfter("private fun showCropStatus(").substringBefore("private fun clippedCropBounds(")
        assertTrue(coordinator.contains("currentWindowMetrics.bounds"))
        assertTrue(coordinator.contains("cropResultRenderer.renderStatus("))
        assertTrue(coordinator.contains("minimumWidth = bitmap.width"))
        assertTrue(coordinator.contains("minimumHeight = bitmap.height"))
        assertFalse(coordinator.contains("cropResultRenderer.render("))
    }

    @Test
    fun `selector uses calibrated dp controls and keeps the default FGO crop fractions`() {
        val selector = source("crop/CropSelectionOverlay.kt")
        assertTrue(selector.contains("TypedValue.COMPLEX_UNIT_DIP"))
        assertTrue(selector.contains("strokeWidth = dp(4f / 3f)"))
        assertTrue(selector.contains("strokeWidth = dp(8f / 3f)"))
        assertTrue(selector.contains("val radius = dp(44f / 3f)"))
        assertTrue(selector.contains(".coerceIn(dp(28f / 3f), dp(56f / 3f))"))
        assertTrue(selector.contains("val defaultWidth = viewportWidth * 0.58f"))
        assertTrue(selector.contains("val defaultHeight = viewportHeight * 0.20f"))
        assertTrue(selector.contains("val top = viewport.top + viewportHeight * 0.36f"))
    }

    private fun assertInside(rect: CropRenderRect, width: Int, height: Int) {
        assertTrue(rect.left >= 0f && rect.top >= 0f, rect.toString())
        assertTrue(rect.right <= width && rect.bottom <= height, rect.toString())
        assertTrue(rect.width > 0f && rect.height > 0f, rect.toString())
    }

    private fun assertRect(expected: CropRenderRect, actual: CropRenderRect) {
        assertEquals(expected.left, actual.left, 0.0002f)
        assertEquals(expected.top, actual.top, 0.0002f)
        assertEquals(expected.right, actual.right, 0.0002f)
        assertEquals(expected.bottom, actual.bottom, 0.0002f)
    }

    private fun CropRenderRect.scaled(scale: Float) =
        CropRenderRect(left * scale, top * scale, right * scale, bottom * scale)

    private fun source(relativePath: String): String = listOf(
        File("src/main/java/com/fgogotran/$relativePath"),
        File("app/src/main/java/com/fgogotran/$relativePath")
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
