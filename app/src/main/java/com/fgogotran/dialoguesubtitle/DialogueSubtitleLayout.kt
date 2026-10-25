package com.fgogotran.dialoguesubtitle

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import com.fgogotran.overlay.DialogueReferenceGeometry
import kotlin.math.ceil

/** Voiced Servant dialogue panel, independent of BattleLayout and story completion markers. */
object DialogueSubtitleLayout {
    val text = FgoReferenceRect(106, 833, 1805, 1052)
    fun map(rect: FgoReferenceRect, width: Int, height: Int) = FgoViewportGeometry.map(rect, width, height)

    /** Probe the plate's empty margins, not the animated character or white text. */
    fun panelPresent(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width < height || height < 240) return false
        var blue = 0
        var tested = 0
        for (x in listOf(82, 1830)) for (y in listOf(870, 920, 980, 1032)) {
            val point = map(FgoReferenceRect(x, y, x + 1, y + 1), width, height)
            val color = pixel(point.left.coerceIn(0, width - 1), point.top.coerceIn(0, height - 1))
            val red = color shr 16 and 255
            val green = color shr 8 and 255
            val b = color and 255
            if (b >= 35 && b > red * 1.3f && b > green * 1.05f && red < 110) blue++
            tested++
        }
        return blue >= tested - 1
    }
}

object DialogueSubtitleStyle {
    const val MAX_LINES = 2
    fun textSizePx(width: Int, height: Int): Float = 52f * FgoViewportGeometry.referenceScale(width, height)
    fun displayText(text: String): String = text.trim().replace(Regex("[\\r\\n]+"), " ")

    /** Independent of caption length, font fitting and whether the second row is used. */
    fun geometry(width: Int, height: Int): DialogueSubtitleRenderGeometry {
        val scale = FgoViewportGeometry.referenceScale(width, height)
        val source = DialogueSubtitleLayout.map(DialogueSubtitleLayout.text, width, height)
        val bottom = source.top - ceil(10f * scale).toInt()
        // Reserve room for 52 px CJK fallback font bounds, padding and shadows in both slots.
        // This frame stays transparent; only the snug per-row plates are painted.
        val panelHeight = ceil(200f * scale).toInt()
        val rowPitch = ceil(86f * scale).toInt()
        val lowerBaseline = ceil(172f * scale).toInt()
        val horizontalPadding = ceil(12f * scale).toInt()
        val shadow = 1.5f * scale
        val textStart = DialogueSubtitleLayout.map(
            DialogueSubtitleLayout.text.copy(left = DialogueReferenceGeometry.TEXT_LEFT.toInt()), width, height
        ).left
        // Align the text (not the background edge) to FGO's dialogue start. Keep the established
        // right boundary and leave the OCR crop untouched; fitting uses the remaining width.
        val renderLeft = textStart - horizontalPadding - ceil(shadow).toInt()
        return DialogueSubtitleRenderGeometry(
            FgoReferenceRect(renderLeft, bottom - panelHeight, source.right, bottom),
            horizontalPadding, lowerBaseline - rowPitch, rowPitch,
            shadow, 4f * scale
        )
    }

    /** Largest measured full-text fit. A maxLines cap/ellipsis must never conceal extra text. */
    fun fitTextSize(preferredSize: Float, fits: (Float) -> Boolean): Float? {
        if (!preferredSize.isFinite() || preferredSize <= 0f) return null
        if (fits(preferredSize)) return preferredSize
        var high = preferredSize
        var low = preferredSize / 2f
        var found = false
        // Tiny glyphs can round to empty bounds. Find a real fitting size before refining;
        // failure at a subpixel size does not mean ordinary smaller text cannot fit.
        for (attempt in 0 until 7) {
            if (fits(low)) {
                found = true
                break
            }
            high = low
            low /= 2f
        }
        if (!found) return null
        repeat(14) {
            val size = (low + high) / 2f
            if (fits(size)) low = size else high = size
        }
        return low
    }

    /** A transparent window contains only snug per-row plates, never an opaque full-width frame. */
    fun rowPlate(geometry: DialogueSubtitleRenderGeometry, slot: Int, line: DialogueSubtitleLineMetrics,
                 fullTextCovered: Boolean): DialogueSubtitlePlate? {
        if (!fullTextCovered || slot !in 0..1 || line.baselineOffset != 0 ||
            geometry.contentWidth <= 0 || line.right <= line.left) return null
        val plate = DialogueSubtitlePlate(
            geometry.textOriginX + line.left - geometry.horizontalPadding - geometry.shadowPx,
            geometry.baseline(slot) + line.ascent - geometry.verticalPadding - geometry.shadowPx,
            geometry.textOriginX + line.right + geometry.horizontalPadding + geometry.shadowPx,
            geometry.baseline(slot) + line.descent + geometry.verticalPadding + geometry.shadowPx * 2
        )
        if (plate.left < 0 || plate.top < 0 || plate.right > geometry.bounds.width ||
            plate.bottom > geometry.bounds.height ||
            plate.bottom - plate.top > geometry.rowPitch - geometry.verticalPadding) return null
        return plate
    }
}

data class DialogueSubtitleLineMetrics(
    val left: Float, val right: Float, val ascent: Int, val descent: Int, val baselineOffset: Int
)

data class DialogueSubtitlePlate(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class DialogueSubtitleRenderRow(val id: Long, val slot: Int, val text: String, val targetLocale: String)

data class DialogueSubtitleRenderGeometry(
    val bounds: FgoReferenceRect,
    val horizontalPadding: Int,
    val firstBaseline: Int,
    val rowPitch: Int,
    val shadowPx: Float,
    val verticalPadding: Float
) {
    val textOriginX: Float get() = horizontalPadding + ceil(shadowPx)
    val contentWidth: Int get() = bounds.width - ceil(textOriginX * 2).toInt()
    fun baseline(line: Int): Int = firstBaseline + line * rowPitch
}
