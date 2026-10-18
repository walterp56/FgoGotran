package com.fgogotran.crop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM source/wiring checks; actual Canvas colours and readability still need device QA. */
class CropRenderColorPolicyTest {
    private val renderer = source("crop/CropResultRenderer.kt")
    private val service = source("accessibility/FgoAccessibilityService.kt")

    @Test
    fun `crop palette is fixed opaque and owned only by the renderer`() {
        assertTrue(renderer.contains("CROP_TEXT_COLOR = Color.rgb(245, 245, 240)"))
        assertTrue(renderer.contains("CROP_BACKGROUND_COLOR = Color.rgb(27, 51, 85)"))
        val background = section(renderer, "private val backgroundPaint =", "private val textPaint =")
        assertTrue(background.contains("color = CROP_BACKGROUND_COLOR"))
        assertTrue(background.contains("style = Paint.Style.FILL"))
        assertFalse(renderer.contains("Color.argb("))
        assertFalse(renderer.contains("overlayClearPaint"))
        assertFalse(renderer.contains("MaterialTheme"))
        assertFalse(service.contains("CROP_TEXT_COLOR"))
        assertFalse(service.contains("CROP_BACKGROUND_COLOR"))
    }

    @Test
    fun `rows panels and status all use the same background and text drawing helper`() {
        val panel = section(renderer, "fun render(", "fun renderOverlay(")
        val rows = section(renderer, "fun renderOverlay(", "fun renderStatus(")
        val status = section(renderer, "fun renderStatus(", "private fun drawTranslatedLine(")
        for (path in listOf(panel, status)) {
            assertTrue(path.contains("canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)"))
            assertTrue(path.contains("drawTranslatedLine("))
        }
        assertTrue(rows.contains("canvas.drawRoundRect(clearBox, radius, radius, backgroundPaint)"))
        assertTrue(rows.contains("drawTranslatedLine("))
        assertFalse(rows.contains("canvas.drawRect(")) // Do not fill the whole crop in normal row mode.
        assertEquals(3, Regex(Regex.escape(
            "return render(bitmapWidth, bitmapHeight, text, referenceScale, targetLocale)"
        )).findAll(rows).count())
    }

    @Test
    fun `all crop text is fixed while existing shadow and two draw passes remain`() {
        assertFalse(Regex("\\btextColor\\b").containsMatchIn(renderer))
        val draw = section(renderer, "private fun drawTranslatedLine(", "private fun fitLines(")
        assertEquals(2, Regex(Regex.escape("textPaint.color = CROP_TEXT_COLOR")).findAll(draw).count())
        assertEquals(2, Regex(Regex.escape("canvas.drawText(line, x, baseline, textPaint)")).findAll(draw).count())
        assertTrue(draw.contains("textPaint.setShadowLayer(shadowPx, shadowPx, shadowPx, Color.BLACK)"))
        assertTrue(draw.contains("textPaint.clearShadowLayer()"))
    }

    @Test
    fun `crop coordinator no longer samples colours or overrides the renderer palette`() {
        val crop = section(service, "private suspend fun processCropTranslation(", "/** Crop status text")
        assertFalse(service.contains("sampleCropOriginalTextColor"))
        assertFalse(crop.contains("sampleOriginalTextColor("))
        assertFalse(crop.contains("cropTextColor"))
        assertFalse(crop.contains("textColor ="))
        assertTrue(crop.contains("cropResultRenderer.renderOverlay("))
        assertTrue(crop.contains("targetLocale = translationResult.targetLocale"))
        assertEquals(1, Regex("ocrEngine\\.recognize\\(").findAll(crop).count())
        assertEquals(1, Regex("translator\\.translate\\(").findAll(crop).count())
    }

    @Test
    fun `shared non crop colour sampling and white fallback remain available`() {
        assertTrue(service.contains("private fun sampleOriginalTextColor("))
        assertTrue(service.contains("TextRegion.CHOICE_BUTTON -> sampleOriginalTextColor(source, region)"))
        assertTrue(service.contains("rawNameRegion?.let { sampleOriginalTextColor(source, it.region) }"))
        assertTrue(service.contains("private val FGO_RENDER_WHITE = Color.rgb(245, 245, 240)"))
        assertTrue(service.contains("textColor = renderTextColorForRegion(source, regionAndText.region)"))
    }

    @Test
    fun `only painted areas become opaque and the overlay window remains translucent`() {
        val overlay = source("crop/CropResultOverlay.kt")
        assertTrue(overlay.contains("format = PixelFormat.TRANSLUCENT"))
        assertTrue(overlay.contains("type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(overlay.contains("setBackgroundColor("))
        assertFalse(overlay.contains("PixelFormat.OPAQUE"))
    }

    private fun section(text: String, start: String, end: String): String =
        text.substringAfter(start).substringBefore(end)

    private fun source(relative: String): String = listOf(
        File("src/main/java/com/fgogotran/$relative"),
        File("app/src/main/java/com/fgogotran/$relative")
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
