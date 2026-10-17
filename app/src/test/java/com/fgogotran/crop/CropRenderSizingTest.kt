package com.fgogotran.crop

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random
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
    fun `dense Chinese rows fit measured glyphs and shadows into separated bands`() {
        val sizing = CropRenderSizing(1f)
        val sources = listOf(
            CropRenderRect(100f, 50f, 300f, 90f),
            CropRenderRect(100f, 90f, 300f, 130f)
        )
        val areas = sources.map { sizing.textArea(it, 600, 200) }
        val metrics = areas.map { area -> { size: Float -> chineseMetrics(area, size) } }
        val preferred = 44f
        val geometry = sources.indices.map { index ->
            val measured = metrics[index](preferred)
            val painted = sizing.paintedBounds(measured.inkBounds, areas[index].left, measured.preferredBaseline, 2f)
            CropRowGeometry(sources[index], areas[index].union(painted), painted)
        }
        assertTrue(geometry[0].paintedBounds.bottom > geometry[1].paintedBounds.top)
        val safe = sizing.safeRowAreas(geometry, 600, 200)
        assertEquals(2f, safe[1].top - safe[0].bottom, 0.0002f)
        val fitted = sources.indices.map { index ->
            checkNotNull(sizing.fitRow(safe[index], areas[index].left, preferred, metrics[index]))
        }
        assertTrue(fitted.any { it.textSize < preferred })
        fitted.forEachIndexed { index, fit ->
            assertTrue(fit.textSize > 0f && fit.textSize <= preferred)
            assertContained(fit.paintedBounds, safe[index])
        }
        assertTrue(fitted[0].paintedBounds.bottom + 2f <= fitted[1].paintedBounds.top + 0.0002f)
    }

    @Test
    fun `already separated English retains its size and baseline`() {
        val sizing = CropRenderSizing(1f)
        val geometry = listOf(
            CropRowGeometry(CropRenderRect(20f, 30f, 200f, 70f), CropRenderRect(0f, 20f, 400f, 80f), CropRenderRect(20f, 25f, 200f, 65f)),
            CropRowGeometry(CropRenderRect(20f, 74f, 200f, 114f), CropRenderRect(0f, 64f, 400f, 124f), CropRenderRect(20f, 69f, 200f, 109f))
        )
        val safe = sizing.safeRowAreas(geometry, 400, 160)
        val metrics = CropRowMetrics(CropRenderRect(0f, -25f, 170f, 6f), 55f)
        val fitted = checkNotNull(sizing.fitRow(safe[0], 20f, 35f) { metrics })
        assertEquals(35f, fitted.textSize)
        assertEquals(55f, fitted.baseline)
        assertContained(fitted.paintedBounds, safe[0])
    }

    @Test
    fun `separate columns and same baseline fields are not mistaken for stacked rows`() {
        val sizing = CropRenderSizing(1f)
        val first = CropRowGeometry(CropRenderRect(10f, 20f, 100f, 60f), CropRenderRect(0f, 10f, 150f, 70f), CropRenderRect(10f, 10f, 100f, 70f))
        val otherColumn = CropRowGeometry(CropRenderRect(220f, 45f, 350f, 85f), CropRenderRect(200f, 35f, 400f, 95f), CropRenderRect(220f, 35f, 350f, 95f))
        val sameBaseline = otherColumn.copy(
            source = CropRenderRect(220f, 22f, 350f, 62f),
            area = CropRenderRect(200f, 12f, 400f, 72f),
            paintedBounds = CropRenderRect(80f, 12f, 350f, 72f)
        )
        for (other in listOf(otherColumn, sameBaseline)) {
            val safe = sizing.safeRowAreas(listOf(first, other), 400, 160)
            assertEquals(first.area.top, safe[0].top)
            assertEquals(first.area.bottom, safe[0].bottom)
            assertEquals(other.area.top, safe[1].top)
            assertEquals(other.area.bottom, safe[1].bottom)
        }
    }

    @Test
    fun `large titles and indented paragraphs preserve anchors and paragraph gaps`() {
        val sizing = CropRenderSizing(1f)
        val rows = listOf(
            CropRowGeometry(CropRenderRect(100f, 20f, 300f, 80f), CropRenderRect(0f, 10f, 500f, 100f), CropRenderRect(100f, 15f, 300f, 90f)),
            CropRowGeometry(CropRenderRect(120f, 80f, 280f, 110f), CropRenderRect(0f, 70f, 500f, 125f), CropRenderRect(120f, 75f, 280f, 115f)),
            CropRowGeometry(CropRenderRect(120f, 200f, 450f, 230f), CropRenderRect(0f, 190f, 500f, 245f), CropRenderRect(120f, 195f, 450f, 235f))
        )
        val safe = sizing.safeRowAreas(rows, 500, 300)
        assertEquals(2f, safe[1].top - safe[0].bottom, 0.0002f)
        assertEquals(rows[1].area.bottom, safe[1].bottom)
        assertEquals(rows[2].area.top, safe[2].top)
        assertEquals(listOf(100f, 120f, 120f), rows.map { it.source.left })
    }

    @Test
    fun `actual mixed Chinese and Latin descenders override the nominal font height`() {
        val sizing = CropRenderSizing(1f)
        val area = CropRenderRect(0f, 0f, 600f, 40f)
        // Traditional font glyph union including Chinese ascenders and the g in 53kg.
        val fit = checkNotNull(sizing.fitRow(area, 10f, 44f) { size ->
            CropRowMetrics(CropRenderRect(0f, -0.855f * size, 5f * size, 0.271f * size), 0.88f * size)
        })
        assertTrue(fit.textSize < 32.8f) // Even the old 82%-of-source minimum is too large.
        assertContained(fit.paintedBounds, area)
        assertTrue(fit.baseline + fit.inkBounds.bottom < area.bottom)
    }

    @Test
    fun `minimum preference cannot exceed the upper bound of a tiny row`() {
        val sizing = CropRenderSizing(1f)
        assertEquals(3f, sizing.minimumSourceTextSize(40f, 3f))
        val fit = checkNotNull(sizing.fitRow(CropRenderRect(0f, 0f, 20f, 3f), 2f, 3f) { size ->
            CropRowMetrics(CropRenderRect(0f, -size, size, size / 4f), size)
        })
        assertTrue(fit.textSize > 0f && fit.textSize < 3f)
        assertContained(fit.paintedBounds, CropRenderRect(0f, 0f, 20f, 3f))
        assertEquals(null, sizing.fitRow(CropRenderRect(0f, 0f, 1f, 0f), 0f, 20f) {
            error("Invalid geometry must not run font measurement")
        })
    }

    @Test
    fun `neighbour bands and glyph fitting scale with FGO pixels not Android density`() {
        val first = CropRowGeometry(CropRenderRect(20f, 20f, 180f, 60f), CropRenderRect(0f, 10f, 400f, 75f), CropRenderRect(20f, 15f, 180f, 70f))
        val second = first.copy(source = CropRenderRect(20f, 60f, 180f, 100f), area = CropRenderRect(0f, 50f, 400f, 115f), paintedBounds = CropRenderRect(20f, 55f, 180f, 110f))
        val rows = listOf(first, second)
        val original = CropRenderSizing(1f).safeRowAreas(rows, 400, 160)
        for (scale in listOf(0.5f, 2f / 3f, 1f, 2f, 4f)) {
            val sizing = CropRenderSizing(scale)
            val scaled = rows.map { CropRowGeometry(it.source.scaled(scale), it.area.scaled(scale), it.paintedBounds.scaled(scale)) }
            val safe = sizing.safeRowAreas(scaled, (400 * scale).roundToInt(), (160 * scale).roundToInt())
            for (index in rows.indices) {
                assertEquals(original[index].top * scale, safe[index].top, 0.0002f)
                assertEquals(original[index].bottom * scale, safe[index].bottom, 0.0002f)
                val fit = checkNotNull(sizing.fitRow(safe[index], 20f * scale, 44f * scale) { size ->
                    CropRowMetrics(CropRenderRect(0f, -size * 0.855f, size * 3f, size * 0.1f), safe[index].centerY)
                })
                assertContained(fit.paintedBounds, safe[index])
            }
        }
    }

    @Test
    fun `crop edge rows get positive separated bands without losing an OCR row`() {
        for ((width, height) in listOf(1 to 2, 2 to 3, 32 to 32)) {
            val first = CropRenderRect(0f, 0f, width.toFloat(), 1f)
            val last = CropRenderRect(0f, height - 1f, width.toFloat(), height.toFloat())
            val safe = CropRenderSizing(1f).safeRowAreas(listOf(
                CropRowGeometry(first, first, first), CropRowGeometry(last, last, last)
            ), width, height)
            assertEquals(2, safe.size)
            safe.forEach { assertInside(it, width, height) }
            assertTrue(safe[0].bottom < safe[1].top)
        }
    }

    @Test
    fun `mixed height stacks keep every measured row inside non overlapping bands`() {
        val random = Random(17)
        val sizing = CropRenderSizing(1f)
        repeat(60) {
            val sources = (0 until 6).map { index ->
                val center = 40f + index * 35f
                val height = random.nextInt(22, 56).toFloat()
                CropRenderRect(40f, center - height / 2f, 300f, center + height / 2f)
            }
            val areas = sources.map { sizing.textArea(it, 400, 280) }
            val preferred = sources.map { sizing.sourceTextSize(it.height) }
            val geometry = sources.indices.map { index ->
                val measured = chineseMetrics(areas[index], preferred[index])
                val painted = sizing.paintedBounds(measured.inkBounds, areas[index].left, measured.preferredBaseline, 2f)
                CropRowGeometry(sources[index], areas[index].union(painted), painted)
            }
            val safe = sizing.safeRowAreas(geometry, 400, 280)
            val fitted = sources.indices.map { index ->
                assertInside(safe[index], 400, 280)
                checkNotNull(sizing.fitRow(safe[index], areas[index].left, preferred[index]) { size ->
                    chineseMetrics(areas[index], size)
                }).also { fit ->
                    assertContained(fit.paintedBounds, safe[index])
                    assertTrue(fit.textSize <= preferred[index])
                }
            }
            fitted.zipWithNext().forEach { (above, below) ->
                assertTrue(above.paintedBounds.bottom + 2f <= below.paintedBounds.top + 0.0002f)
            }
        }
    }

    @Test
    fun `both row layout paths share measured fits and drawing uses the stored baseline`() {
        val renderer = source("crop/CropResultRenderer.kt")
        val overlay = renderer.substringAfter("fun renderOverlay(").substringBefore("fun renderStatus(")
        assertTrue(overlay.contains("val layouts = fitTranslatedRows("))
        assertTrue(overlay.contains("sizing.safeRowAreas(geometry, bitmapWidth, bitmapHeight)"))
        assertTrue(overlay.contains("sizing.fitRow(safeArea, layout.textArea.left, layout.textSize)"))
        assertTrue(overlay.contains("textPaint.textSize = row.fit.textSize"))
        assertTrue(overlay.contains("row.fit.baseline, textColor, row.fit.shadowPx"))
        assertTrue(overlay.contains("canvas.clipRect(row.safeArea)"))
        assertFalse(overlay.contains("val baseline = rowBaseline("))
        assertTrue(renderer.substringAfter("private fun measureRow(").contains("textPaint.getTextBounds(layout.text"))
        assertTrue(overlay.indexOf("canvas.drawRoundRect(") < overlay.indexOf("drawTranslatedLine("))
        assertTrue(overlay.indexOf("sizing.fitRow(") < overlay.indexOf("Bitmap.createBitmap("))
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

    private fun assertContained(inner: CropRenderRect, outer: CropRenderRect) {
        assertTrue(inner.left >= outer.left - 0.0002f && inner.right <= outer.right + 0.0002f, "$inner in $outer")
        assertTrue(inner.top >= outer.top - 0.0002f && inner.bottom <= outer.bottom + 0.0002f, "$inner in $outer")
    }

    private fun chineseMetrics(area: CropRenderRect, size: Float): CropRowMetrics = CropRowMetrics(
        CropRenderRect(0f, -0.855f * size, size * 4f, 0.088f * size),
        area.top + ((area.height - size) / 2f).coerceAtLeast(0f) + 0.88f * size
    )

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
