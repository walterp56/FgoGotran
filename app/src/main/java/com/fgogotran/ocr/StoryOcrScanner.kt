package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.fgogotran.overlay.ChoicePlateGeometry
import com.fgogotran.util.FgoLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** One recognized story line, in screen coordinates. */
internal data class StoryOcrLine(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    /** 1 or 2 for a dialogue row, 0 when the line has no row ownership. */
    val row: Int
)

/** The fixed-layout parts of one story frame. */
internal data class StoryOcrFrame(
    val dialogue: List<StoryOcrLine>,
    val name: StoryOcrLine?
) {
    /**
     * A single story line is vertically centred by the game and can cross both fixed bands.
     *
     * The overlay sizes its white cover panel from the reported bounds, so a box clipped to
     * the band that happened to read the line leaves the bottom of a large title uncovered
     * (a real capture: band bottom 933, glyph bottom 975). [bounds] is the glyph box of the
     * whole dialogue crop; null keeps the band box.
     */
    fun withSingleLineBounds(bounds: Rect?): StoryOcrFrame =
        if (dialogue.size == 1 && bounds != null) {
            copy(dialogue = listOf(dialogue.single().copy(bounds = bounds)))
        } else {
            this
        }
}

/**
 * Story geometry, in the reference space `DialogueRubyLayout` already describes.
 *
 * FGO's dialogue crop is 219 px tall and splits into two ruby bands (15–48 and 98–134)
 * with the base glyphs of each line below them (48–98 and 134–184). A story line is
 * top-anchored, which is also how the overlay draws it.
 */
internal object StoryOcrGeometry {
    private const val REFERENCE_DIALOGUE_HEIGHT = 219f
    private val mainRows = listOf(48f to 100f, 134f to 186f)

    /** Bright-enough pixel for name-plate ink; the plate itself is dark. */
    private const val NAME_INK_LUMA = 170
    private const val MIN_ROW_INK_PIXELS = 2

    /**
     * Bright-enough pixel for dialogue ink, measured on the strongest channel.
     *
     * Red shout text is dim in luma (~76) but bright in red, so a luma threshold would
     * report those lines as empty.
     */
    private const val TEXT_INK_MAX_CHANNEL = 170

    /** Tight ink boxes keep their margins, so a glyph's antialiased edge is not clipped. */
    private const val INK_PADDING = 2

    /** Below this share of the band height the box is a thin mark, not a line of text. */
    private const val MIN_RECOGNITION_HEIGHT_RATIO = 0.6f

    private const val CHOICE_TEXT_INSET_LEFT = 0.10f
    private const val CHOICE_TEXT_INSET_RIGHT = 0.06f
    private const val CHOICE_TEXT_INSET_Y = 0.18f

    /** Bright enough for a choice glyph core; the pale plate border stays below this. */
    private const val CHOICE_GLYPH_MIN_CHANNEL = 190

    /** Keeps a glyph's antialiased edge and a little bar either side inside the crop. */
    private const val CHOICE_CROP_PAD = 6

    /** Keeps the plate outline out of the crop even when the glyphs reach the panel edge. */
    private const val CHOICE_CROP_EDGE = 2

    /** Keeps the plate's antialiased fill edge out of the glyph window. */
    private const val CHOICE_PLATE_EDGE_INSET = 4

    /** The two fixed dialogue rows, in screen coordinates. */
    fun dialogueBands(dialogue: Rect): List<Rect> {
        if (dialogue.width() <= 0 || dialogue.height() <= 0) return emptyList()
        val scale = dialogue.height() / REFERENCE_DIALOGUE_HEIGHT
        return mainRows.mapNotNull { (top, bottom) ->
            Rect(
                dialogue.left,
                dialogue.top + (top * scale).roundToInt(),
                dialogue.right,
                dialogue.top + (bottom * scale).roundToInt()
            ).takeIf { it.height() > 0 }
        }
    }

    /**
     * Tight box around the ink inside [band], or null when the band holds no text.
     *
     * A story line is left-anchored and usually far shorter than the band, while the
     * overlay sizes its white cover panel from these bounds, so the reported box must
     * follow the glyphs instead of the crop.
     */
    fun inkBounds(source: Bitmap, band: Rect): Rect? {
        val clipped = Rect(band)
        if (!clipped.intersect(0, 0, source.width, source.height) ||
            clipped.width() <= 0 ||
            clipped.height() <= 0
        ) {
            return null
        }
        val pixels = IntArray(clipped.width() * clipped.height())
        source.getPixels(
            pixels,
            0,
            clipped.width(),
            clipped.left,
            clipped.top,
            clipped.width(),
            clipped.height()
        )
        val columns = IntArray(clipped.width())
        var top = -1
        var bottom = -1
        for (y in 0 until clipped.height()) {
            var rowInk = 0
            val offset = y * clipped.width()
            for (x in 0 until clipped.width()) {
                if (!isTextInk(pixels[offset + x])) continue
                rowInk++
                columns[x]++
            }
            if (rowInk >= MIN_ROW_INK_PIXELS) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null
        var left = -1
        var right = -1
        for (x in columns.indices) {
            if (columns[x] < MIN_ROW_INK_PIXELS) continue
            if (left < 0) left = x
            right = x
        }
        if (left < 0) return null
        return Rect(
            (clipped.left + left - INK_PADDING).coerceAtLeast(clipped.left),
            (clipped.top + top - INK_PADDING).coerceAtLeast(clipped.top),
            (clipped.left + right + 1 + INK_PADDING).coerceAtMost(clipped.right),
            (clipped.top + bottom + 1 + INK_PADDING).coerceAtMost(clipped.bottom)
        )
    }

    /**
     * Crop used for recognition.
     *
     * The tight box stops a short line from being read as a sliver at the left of a
     * full-width crop, but a thin mark (`……`) would be stretched by the resize, so it
     * keeps the band.
     */
    fun recognitionBand(band: Rect, ink: Rect?): Rect =
        if (ink != null && ink.height() >= band.height() * MIN_RECOGNITION_HEIGHT_RATIO) ink else band

    /**
     * The name plate is taller than one line of glyphs, so crop to the ink rows before
     * recognition: a squashed crop loses exactly the small kana the fold rules must see.
     */
    fun inkBand(source: Bitmap, bounds: Rect): Rect? {
        val clipped = Rect(bounds)
        if (!clipped.intersect(0, 0, source.width, source.height) ||
            clipped.width() <= 0 ||
            clipped.height() <= 0
        ) {
            return null
        }
        val pixels = IntArray(clipped.width() * clipped.height())
        source.getPixels(
            pixels,
            0,
            clipped.width(),
            clipped.left,
            clipped.top,
            clipped.width(),
            clipped.height()
        )
        var top = -1
        var bottom = -1
        for (y in 0 until clipped.height()) {
            var ink = 0
            val offset = y * clipped.width()
            for (x in 0 until clipped.width()) {
                if (luma(pixels[offset + x]) >= NAME_INK_LUMA) {
                    ink++
                    if (ink >= MIN_ROW_INK_PIXELS) break
                }
            }
            if (ink >= MIN_ROW_INK_PIXELS) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) return null
        return Rect(clipped.left, clipped.top + top, clipped.right, clipped.top + bottom + 1)
    }

    /** The text rows inside one choice panel, clear of its border and selection marker. */
    fun choiceBand(button: Rect): Rect {
        val left = button.left + (button.width() * CHOICE_TEXT_INSET_LEFT).roundToInt()
        val right = button.right - (button.width() * CHOICE_TEXT_INSET_RIGHT).roundToInt()
        val insetY = (button.height() * CHOICE_TEXT_INSET_Y).roundToInt()
        return Rect(left, button.top + insetY, right, button.bottom - insetY)
    }

    /**
     * Columns of [button] that the plate's own fill covers.
     *
     * The fixed slot is wider than the plate: measured on story screenshots the fill starts about
     * 20 px inside the slot, so the slot's margins show the live scene. A lantern, a lit window or
     * a red costume there is bright enough to pass [isChoiceGlyph], which stretched the glyph
     * window to the whole slot even for a short line; the reported box that decides the cover and
     * the text colour then covered scene pixels outside the plate, and a white choice on a
     * lantern-lit street sampled the lanterns and rendered red.
     */
    fun choicePlateWindow(sourceWidth: Int, sourceHeight: Int, button: Rect): IntRange {
        val fill = ChoicePlateGeometry.fill(RectF(button), sourceWidth, sourceHeight)
        val left = (fill.left.roundToInt() + CHOICE_PLATE_EDGE_INSET).coerceAtLeast(button.left)
        val right = (fill.right.roundToInt() - CHOICE_PLATE_EDGE_INSET).coerceAtMost(button.right)
        if (right <= left) {
            return (button.left + CHOICE_CROP_EDGE)..(button.right - CHOICE_CROP_EDGE)
        }
        return left..right
    }

    /**
     * Columns that hold choice glyphs inside [window].
     *
     * FGO centres the choice text, so a long choice runs almost edge to edge: the fixed
     * horizontal insets of [choiceBand] cut its head and tail off, and Paddle then reads a
     * confident but truncated line. The plate border is a pale blue curve that never reaches
     * the glyph channel, so a bright-ink test finds the text and leaves the border out.
     */
    fun choiceTextColumns(source: Bitmap, button: Rect, window: IntRange): IntRange? {
        val band = choiceBand(button)
        val clipped = Rect(window.first, band.top, window.last + 1, band.bottom)
        if (!clipped.intersect(0, 0, source.width, source.height) ||
            clipped.width() <= 0 ||
            clipped.height() <= 0
        ) {
            return null
        }
        val width = clipped.width()
        val height = clipped.height()
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, clipped.left, clipped.top, width, height)
        var first = -1
        var last = -1
        for (x in 0 until width) {
            var ink = 0
            for (y in 0 until height) {
                if (!isChoiceGlyph(pixels[y * width + x])) continue
                ink++
                if (ink >= MIN_ROW_INK_PIXELS) break
            }
            if (ink >= MIN_ROW_INK_PIXELS) {
                if (first < 0) first = x
                last = x
            }
        }
        if (first < 0) return null
        return IntRange(clipped.left + first, clipped.left + last)
    }

    /**
     * The recognition crop of one choice panel.
     *
     * The rows stay [choiceBand]'s so a short line is not stretched, while the columns follow
     * [columns]. A panel without measurable ink keeps the whole [window] — the plate fill —
     * because reading a truncated line is worse than reading some border.
     */
    fun choiceCrop(button: Rect, window: IntRange, columns: IntRange?): Rect {
        val band = choiceBand(button)
        val leftmost = (button.left + CHOICE_CROP_EDGE).coerceAtLeast(window.first)
        val rightmost = (button.right - CHOICE_CROP_EDGE).coerceAtMost(window.last + 1)
        if (rightmost <= leftmost) {
            return Rect(
                button.left + CHOICE_CROP_EDGE,
                band.top,
                button.right - CHOICE_CROP_EDGE,
                band.bottom
            )
        }
        if (columns == null) {
            return Rect(leftmost, band.top, rightmost, band.bottom)
        }
        val left = (columns.first - CHOICE_CROP_PAD).coerceAtLeast(leftmost)
        val right = (columns.last + 1 + CHOICE_CROP_PAD).coerceAtMost(rightmost)
        if (right <= left) return Rect(leftmost, band.top, rightmost, band.bottom)
        return Rect(left, band.top, right, band.bottom)
    }

    private fun luma(pixel: Int): Int {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun isTextInk(pixel: Int): Boolean {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return maxOf(red, green, blue) >= TEXT_INK_MAX_CHANNEL
    }

    /** A choice glyph core: bright neutral, or the vivid red of a shout choice. */
    private fun isChoiceGlyph(pixel: Int): Boolean {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        if (minOf(red, green, blue) >= CHOICE_GLYPH_MIN_CHANNEL) return true
        return red >= 150 && red - maxOf(green, blue) >= 60
    }
}

/**
 * Turns raw band readings into story lines.
 *
 * Kept pure so the acceptance rules (one row, two rows, a straddling single line, a weak
 * read, a weak button) are testable without a model, a bitmap, or a device.
 */
internal object StoryOcrAssembler {

    /** Below this the read is noise; the caller runs the full pipeline instead. */
    const val MIN_LINE_CONFIDENCE = 0.55f

    /** The two fixed dialogue rows, or null when the read cannot be trusted. */
    fun dialogue(results: List<StoryBandText?>, bands: List<Rect>): List<StoryOcrLine>? {
        val present = ArrayList<StoryOcrLine>(results.size)
        for (index in results.indices) {
            val result = results[index] ?: continue
            val text = result.text.trim()
            if (text.isEmpty()) continue
            if (result.confidence < MIN_LINE_CONFIDENCE) return null
            present += StoryOcrLine(text, bands[index], result.confidence, row = index + 1)
        }
        if (present.isEmpty()) return null
        if (present.size == 2) {
            // A single line that straddles both bands reads as overlapping fragments.
            val first = present[0].text
            val second = present[1].text
            if (second.startsWith(first)) return listOf(present[1])
            if (first.startsWith(second)) return listOf(present[0])
        }
        return present
    }

    /**
     * One line per choice panel; null when any panel is unreadable.
     *
     * [reported] is the box each line carries. The caller passes the glyph box inside the
     * panel, because the service samples the text colour from that box; [buttons] only says
     * which panel a line belongs to.
     */
    fun choices(
        results: List<StoryBandText?>,
        buttons: List<Rect>,
        reported: List<Rect> = buttons
    ): List<StoryOcrLine>? {
        if (results.size != buttons.size || reported.size != buttons.size) return null
        val lines = ArrayList<StoryOcrLine>(buttons.size)
        for (index in buttons.indices) {
            val result = results[index] ?: return null
            val text = result.text.trim()
            if (text.isEmpty() || result.confidence < MIN_LINE_CONFIDENCE) return null
            lines += StoryOcrLine(text, reported[index], result.confidence, row = 0)
        }
        return lines
    }

    /** The speaker line, or null when the plate is empty or too weak to trust. */
    fun name(result: StoryBandText?, band: Rect): StoryOcrLine? {
        val value = result ?: return null
        val text = value.text.trim()
        if (text.isEmpty() || value.confidence < MIN_LINE_CONFIDENCE) return null
        return StoryOcrLine(text, band, value.confidence, row = 0)
    }
}

/**
 * The Story Library OCR path.
 *
 * The API path transcribes a screenshot into presentable Japanese: it detects boxes,
 * separates ruby, repairs edges, and retries weak crops. A downloaded chapter pack
 * needs something else — it needs to know which stored line this frame is, and the
 * database itself decides whether the read was good enough. So this path recognizes
 * the fixed story bands once, with no detection and no repair passes, and hands back
 * the text and the model's confidence. The caller keeps the full pipeline as a
 * fallback whenever this returns null or a line misses.
 */
@Singleton
class StoryOcrScanner @Inject constructor(
    private val ocrEngine: OcrEngine
) {
    /**
     * Reads the two dialogue rows and, when a plate was measured, the speaker line.
     *
     * @return null when band recognition is unavailable, a row is too weak to trust, or
     * the two rows cannot be told apart, so the caller runs the full pipeline instead.
     */
    internal suspend fun scanDialogue(
        source: Bitmap,
        dialogueBounds: Rect,
        namePlate: Rect?
    ): StoryOcrFrame? {
        val rows = StoryOcrGeometry.dialogueBands(dialogueBounds)
        if (rows.size != DIALOGUE_ROW_COUNT) return null
        val inks = rows.map { StoryOcrGeometry.inkBounds(source, it) }
        val crops = rows.mapIndexed { index, band ->
            StoryOcrGeometry.recognitionBand(band, inks[index])
        }
        val nameBand = namePlate?.let { StoryOcrGeometry.inkBand(source, it) }
        // Same rule as a choice: recognition keeps the measured band, while the reported box
        // also follows the glyph columns, because the colour vote scans that box.
        val nameReported = nameBand?.let { band -> StoryOcrGeometry.inkBounds(source, band) ?: band }
        val bands = if (nameBand == null) crops else crops + nameBand

        val startedAt = System.currentTimeMillis()
        val results = ocrEngine.recognizeStoryBands(source, bands) ?: return null
        if (results.size != bands.size) return null
        // The reported box follows the glyphs, never the crop's full width: the overlay sizes
        // its cover panel from these bounds, so a band-wide box would paint over the whole box.
        val reported = rows.mapIndexed { index, band -> inks[index] ?: band }
        val dialogue = StoryOcrAssembler.dialogue(results.subList(0, rows.size), reported) ?: return null
        val name = nameReported?.let { band ->
            StoryOcrAssembler.name(results.getOrNull(rows.size), band)
        }
        // A frame with one line may be centred across both bands, so its box is measured
        // over the whole crop instead of the band that read it.
        val singleLineBounds = if (dialogue.size == 1) {
            StoryOcrGeometry.inkBounds(source, dialogueBounds)
        } else {
            null
        }
        val frame = StoryOcrFrame(dialogue = dialogue, name = name).withSingleLineBounds(singleLineBounds)
        FgoLogger.debug(
            tag,
            "Story OCR fast dialogue: rows=${dialogue.size}, name=${name != null}, " +
                "confidence=${dialogue.joinToString(",") { (it.confidence * 100).roundToInt().toString() }}, " +
                "elapsed=${System.currentTimeMillis() - startedAt}ms"
        )
        return frame
    }

    /**
     * Reads one line per choice panel.
     *
     * @return null when any panel is unreadable, because the caller's full choice path
     * can still recover a single weak button without losing the rest of the list.
     */
    internal suspend fun scanChoices(source: Bitmap, buttons: List<Rect>): List<StoryOcrLine>? {
        if (buttons.isEmpty() || buttons.size > MAX_CHOICE_COUNT) return null
        val bands = buttons.map { StoryOcrGeometry.choiceBand(it) }
        if (bands.any { it.width() <= 0 || it.height() <= 0 }) return null
        // Both the glyph window and the crop stay inside the plate fill: the slot's margins show
        // the live scene, and a bright pixel there would stretch the window to the whole slot.
        val windows = buttons.map {
            StoryOcrGeometry.choicePlateWindow(source.width, source.height, it)
        }
        // Recognition keeps the band's rows but takes its columns from the glyphs: FGO centres
        // the choice text, so the fixed insets cut a long choice short and Paddle then reads a
        // confident but truncated line.
        val crops = buttons.mapIndexed { index, button ->
            val window = windows[index]
            StoryOcrGeometry.choiceCrop(
                button,
                window,
                StoryOcrGeometry.choiceTextColumns(source, button, window)
            )
        }
        // The reported box follows the glyphs. The service samples the text colour inside that
        // box, so a box as wide as the panel covers the scene beside the plate, and its lanterns
        // outvote the white glyphs and the line renders red.
        val reported = crops.map { StoryOcrGeometry.inkBounds(source, it) ?: it }

        val startedAt = System.currentTimeMillis()
        val results = ocrEngine.recognizeStoryBands(source, crops) ?: return null
        val lines = StoryOcrAssembler.choices(results, buttons, reported) ?: return null
        FgoLogger.debug(
            tag,
            "Story OCR fast choices: count=${lines.size}, " +
                "confidence=${lines.joinToString(",") { (it.confidence * 100).roundToInt().toString() }}, " +
                "cropWidths=${crops.joinToString(",") { it.width().toString() }}, " +
                "elapsed=${System.currentTimeMillis() - startedAt}ms"
        )
        return lines
    }

    private companion object {
        const val tag = "OCR"
        const val DIALOGUE_ROW_COUNT = 2
        const val MAX_CHOICE_COUNT = 6
    }
}
