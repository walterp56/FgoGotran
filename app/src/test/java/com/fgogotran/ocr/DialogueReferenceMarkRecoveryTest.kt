package com.fgogotran.ocr

import java.io.InputStream
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.fgogotran.ocr.DialoguePunctuationRecovery.Bounds
import com.fgogotran.ocr.DialoguePunctuationRecovery.Line
import com.fgogotran.ocr.DialoguePunctuationRecovery.Token

class DialogueReferenceMarkRecoveryTest {
    private val prefix = "即ち、"
    private val suffix = "が設置された世界であり───"
    private val marks = "※".repeat(8)

    @Test
    fun `real JP screenshot restores eight marks and preserves two rows at multiple resolutions`() {
        for (scale in listOf(0.6, 0.8, 1.0, 1.25)) {
            val image = screenshot(scale)
            val pixels = image.pixels()
            val lines = screenshotLines(scale)
            val cleanup = DialogueAnnotationCleaner.clean(pixels, image.width, image.height, true)
            val plan = DialogueReferenceMarkRecovery.plan(pixels, image.width, image.height, cleanup.components, lines)
            assertEquals(listOf(8), plan.runs.map { it.glyphs.size }, "scale=$scale")
            // Exercise the production order: exclude owned dots, recover punctuation, merge ※ last.
            val punctuation = DialoguePunctuationRecovery.recover(cleanup.pixels, image.width, image.height,
                plan.punctuationComponents(cleanup.components), lines)
            val result = DialogueReferenceMarkRecovery.recover(punctuation.lines, plan)
            assertEquals(listOf("歴史の勝者は汎人類史。", prefix + marks + suffix), result.lines.map { it.text }, "scale=$scale")
            assertEquals(8, result.recoveredSymbols)
            assertEquals(result.lines, DialogueReferenceMarkRecovery.recover(result.lines, plan).lines)
        }
    }

    @Test
    fun `partial garbled and fully recognized runs do not duplicate symbols`() {
        val image = screenshot()
        val base = screenshotLines().last()
        val plan = plan(image, listOf(base))
        for (recognized in listOf("※※", "x.x.x.x.", marks)) {
            val tokens = listOf(Token(prefix, 220f)) + recognized.mapIndexed { index, char ->
                Token(char.toString(), 365f + index * 73f)
            } + Token(suffix, 1350f)
            val result = DialogueReferenceMarkRecovery.recover(listOf(base.copy(
                text = prefix + recognized + suffix, tokens = tokens)), plan)
            assertEquals(prefix + marks + suffix, result.lines.single().text)
        }
    }

    @Test
    fun `nonoverlapping left and right boxes suffice without CTC positions`() {
        val image = screenshot()
        val left = Line(prefix, Bounds(110, 208, 325, 272), 0.95f)
        val right = Line(suffix, Bounds(920, 208, 1950, 272), 0.96f)
        val plan = plan(image, listOf(left, right))
        val result = DialogueReferenceMarkRecovery.recover(listOf(left, right), plan)
        assertEquals(prefix + marks + suffix, result.lines.single().text)
        assertEquals(8, result.recoveredSymbols)
    }

    @Test
    fun `uncertain internal positions and overlapping word tokens preserve original text`() {
        val image = screenshot()
        val base = screenshotLines().last()
        val plan = plan(image, listOf(base))
        for (line in listOf(base.copy(tokens = emptyList()), base.copy(
            text = prefix + "本文" + suffix,
            tokens = listOf(Token(prefix, 220f), Token("本文", 600f), Token(suffix, 1350f))))) {
            val result = DialogueReferenceMarkRecovery.recover(listOf(line), plan)
            assertEquals(listOf(line), result.lines)
            assertEquals(0, result.recoveredSymbols)
        }
    }

    @Test
    fun `valid repeated synthetic glyphs recover while a single footnote remains ordinary text`() {
        for (count in listOf(1, 2, 3, 8)) {
            val image = drawn(count, "reference")
            val line = drawnLine(image)
            val plan = plan(image, listOf(line))
            assertEquals(if (count == 1) emptyList() else listOf(count), plan.runs.map { it.glyphs.size })
        }
    }

    @Test
    fun `ordinary crosses kanji strokes emphasis dots pluses and asterisks are never masks`() {
        for (kind in listOf("cross", "rice", "plus", "asterisk", "emphasis", "missing-dot")) {
            val image = drawn(3, kind)
            val line = drawnLine(image)
            val plan = plan(image, listOf(line))
            assertTrue(plan.runs.isEmpty(), kind)
            assertEquals(listOf(line), DialogueReferenceMarkRecovery.recover(listOf(line), plan).lines)
        }
    }

    @Test
    fun `small upper annotations cannot become main dialogue reference marks`() {
        val image = drawn(3, "reference", upper = true)
        assertTrue(plan(image, listOf(drawnLine(image))).runs.isEmpty())
    }

    @Test
    fun `empty plan leaves both lines and component list untouched`() {
        val lines = screenshotLines()
        val components = emptyList<DialogueAnnotationCleaner.Component>()
        assertSame(lines, DialogueReferenceMarkRecovery.recover(lines, DialogueReferenceMarkRecovery.Plan()).lines)
        assertSame(components, DialogueReferenceMarkRecovery.Plan().punctuationComponents(components))
        assertTrue(DialogueReferenceMarkRecovery.plan(intArrayOf(), 1, 1, components, lines).runs.isEmpty())
        assertTrue(DialogueReferenceMarkRecovery.plan(intArrayOf(0), 1, 1, components,
            listOf(Line("文字", Bounds(0, 0, 0, 0), 1f))).runs.isEmpty())
    }

    @Test
    fun `report host helper overhead without including existing OCR or component scanning`() {
        val image = screenshot()
        val lines = screenshotLines()
        val components = DialogueAnnotationCleaner.clean(image.argb, image.width, image.height, true).components
        val marked = plan(image, lines)
        val normal = marked.punctuationComponents(components)
        fun measure(input: List<DialogueAnnotationCleaner.Component>): Pair<Double, Double> {
            fun once(): Long {
                val start = System.nanoTime()
                val plan = DialogueReferenceMarkRecovery.plan(image.argb, image.width, image.height, input, lines)
                DialogueReferenceMarkRecovery.recover(lines, plan)
                return System.nanoTime() - start
            }
            repeat(100) { once() }
            val samples = List(300) { once() }.sorted()
            return samples[150] / 1_000_000.0 to samples[284] / 1_000_000.0
        }
        println("REFERENCE_MARK_HELPER_HOST_MS normal_median_p95=${measure(normal)} marked_median_p95=${measure(components)}")
        assertEquals(listOf(8), marked.runs.map { it.glyphs.size })
    }

    private data class PixelImage(val width: Int, val height: Int, val argb: IntArray) {
        fun pixels() = argb
    }

    private fun plan(image: PixelImage, lines: List<Line>): DialogueReferenceMarkRecovery.Plan {
        val pixels = image.pixels()
        val cleanup = DialogueAnnotationCleaner.clean(pixels, image.width, image.height, true)
        return DialogueReferenceMarkRecovery.plan(pixels, image.width, image.height, cleanup.components, lines)
    }

    private fun screenshot(scale: Double = 1.0): PixelImage {
        // Android's compile-time API excludes java.desktop; ImageIO is available in the host JVM.
        // Reflection keeps this a host-only fixture reader with no new production dependency.
        val image = checkNotNull(javaClass.getResourceAsStream("/ocr/jp-reference-marks.png")).use { stream ->
            Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream::class.java).invoke(null, stream)
        }
        val type = image.javaClass
        val sourceWidth = type.getMethod("getWidth").invoke(image) as Int
        val sourceHeight = type.getMethod("getHeight").invoke(image) as Int
        val source = type.getMethod("getRGB", Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
            IntArray::class.java, Integer.TYPE, Integer.TYPE)
            .invoke(image, 0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth) as IntArray
        if (scale == 1.0) return PixelImage(sourceWidth, sourceHeight, source)
        val width = (sourceWidth * scale).toInt()
        val height = (sourceHeight * scale).toInt()
        val pixels = IntArray(width * height) { index ->
            val x = ((index % width + 0.5) * sourceWidth / width - 0.5).coerceIn(0.0, sourceWidth - 1.0)
            val y = ((index / width + 0.5) * sourceHeight / height - 0.5).coerceIn(0.0, sourceHeight - 1.0)
            val left = floor(x).toInt()
            val top = floor(y).toInt()
            val right = minOf(left + 1, sourceWidth - 1)
            val bottom = minOf(top + 1, sourceHeight - 1)
            val dx = x - left
            val dy = y - top
            var pixel = -0x1000000
            for (shift in listOf(0, 8, 16)) {
                fun channel(xx: Int, yy: Int) = (source[yy * sourceWidth + xx] ushr shift) and 255
                val upper = channel(left, top) * (1 - dx) + channel(right, top) * dx
                val lower = channel(left, bottom) * (1 - dx) + channel(right, bottom) * dx
                pixel = pixel or (((upper * (1 - dy) + lower * dy).roundToInt()) shl shift)
            }
            pixel
        }
        return PixelImage(width, height, pixels)
    }

    private fun screenshotLines(scale: Double = 1.0): List<Line> {
        fun box(left: Int, top: Int, right: Int, bottom: Int) = Bounds(
            (left * scale).toInt(), (top * scale).toInt(), (right * scale).toInt(), (bottom * scale).toInt())
        return listOf(
            Line("歴史の勝者は汎人類史。", box(110, 88, 880, 154), 0.96f,
                listOf(Token("歴史の勝者は汎人類史。", (500 * scale).toFloat()))),
            Line(prefix + suffix, box(110, 208, 1950, 272), 0.96f,
                listOf(Token(prefix, (220 * scale).toFloat()),
                    Token(suffix.removeSuffix("───"), (1350 * scale).toFloat()), Token("───", (1830 * scale).toFloat())))
        )
    }

    private fun drawnLine(image: PixelImage) = Line("前後", Bounds(0, 60, image.width, 120), 0.95f,
        listOf(Token("前", 10f), Token("後", image.width - 10f)))

    private fun drawn(count: Int, kind: String, upper: Boolean = false): PixelImage {
        val width = 100 + count * 75
        val height = 180
        val pixels = IntArray(width * height) { -0x1000000 }
        fun dot(cx: Int, cy: Int, radius: Int) {
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until width && y in 0 until height && dx * dx + dy * dy <= radius * radius) {
                    pixels[y * width + x] = -1
                }
            }
        }
        repeat(count) { index ->
            val cx = 65 + index * 75
            val cy = if (upper) 25 else 90
            val radius = if (upper) 10 else 25
            if (kind != "plus") for (d in -radius..radius) {
                dot(cx + d, cy + d, 1)
                dot(cx + d, cy - d, 1)
            }
            if (kind in listOf("rice", "plus", "asterisk")) for (d in -radius..radius) {
                dot(cx + d, cy, 1)
                dot(cx, cy + d, 1)
            }
            if (kind in listOf("reference", "missing-dot")) {
                for ((dx, dy) in listOf(0 to -21, -21 to 0, 21 to 0, 0 to 21)) {
                    if (kind == "missing-dot" && dx == 21) continue
                    dot(cx + dx, cy + dy, 7)
                }
            }
            if (kind == "emphasis") dot(cx, cy - 37, 5)
        }
        return PixelImage(width, height, pixels)
    }
}
