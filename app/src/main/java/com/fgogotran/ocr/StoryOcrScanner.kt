package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.Rect
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
)

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

    private const val CHOICE_TEXT_INSET_LEFT = 0.10f
    private const val CHOICE_TEXT_INSET_RIGHT = 0.06f
    private const val CHOICE_TEXT_INSET_Y = 0.18f

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

    /** The text area inside one choice panel, clear of its border and selection marker. */
    fun choiceBand(button: Rect): Rect {
        val left = button.left + (button.width() * CHOICE_TEXT_INSET_LEFT).roundToInt()
        val right = button.right - (button.width() * CHOICE_TEXT_INSET_RIGHT).roundToInt()
        val insetY = (button.height() * CHOICE_TEXT_INSET_Y).roundToInt()
        return Rect(left, button.top + insetY, right, button.bottom - insetY)
    }

    private fun luma(pixel: Int): Int {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return (red * 299 + green * 587 + blue * 114) / 1000
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

    /** One line per choice panel; null when any panel is unreadable. */
    fun choices(results: List<StoryBandText?>, buttons: List<Rect>): List<StoryOcrLine>? {
        if (results.size != buttons.size) return null
        val lines = ArrayList<StoryOcrLine>(buttons.size)
        for (index in buttons.indices) {
            val result = results[index] ?: return null
            val text = result.text.trim()
            if (text.isEmpty() || result.confidence < MIN_LINE_CONFIDENCE) return null
            lines += StoryOcrLine(text, buttons[index], result.confidence, row = 0)
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
        val nameBand = namePlate?.let { StoryOcrGeometry.inkBand(source, it) }
        val bands = if (nameBand == null) rows else rows + nameBand

        val startedAt = System.currentTimeMillis()
        val results = ocrEngine.recognizeStoryBands(source, bands) ?: return null
        if (results.size != bands.size) return null
        val dialogue = StoryOcrAssembler.dialogue(results.subList(0, rows.size), rows) ?: return null
        val name = nameBand?.let { band ->
            StoryOcrAssembler.name(results.getOrNull(rows.size), band)
        }
        FgoLogger.debug(
            tag,
            "Story OCR fast dialogue: rows=${dialogue.size}, name=${name != null}, " +
                "confidence=${dialogue.joinToString(",") { (it.confidence * 100).roundToInt().toString() }}, " +
                "elapsed=${System.currentTimeMillis() - startedAt}ms"
        )
        return StoryOcrFrame(dialogue = dialogue, name = name)
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

        val startedAt = System.currentTimeMillis()
        val results = ocrEngine.recognizeStoryBands(source, bands) ?: return null
        val lines = StoryOcrAssembler.choices(results, buttons) ?: return null
        FgoLogger.debug(
            tag,
            "Story OCR fast choices: count=${lines.size}, " +
                "confidence=${lines.joinToString(",") { (it.confidence * 100).roundToInt().toString() }}, " +
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
