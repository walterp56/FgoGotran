package com.fgogotran.battle

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.math.ceil

/** Stable battle regions on FGO's centered 1920x1080 gameplay canvas. */
object BattleLayout {
    val subtitle = FgoReferenceRect(100, 688, 1820, 866)

    fun map(rect: FgoReferenceRect, width: Int, height: Int) = FgoViewportGeometry.map(rect, width, height)
}

/** Battle-caption typography expressed on FGO's 1920x1080 reference canvas. */
object BattleSubtitleStyle {
    private const val REFERENCE_HEIGHT_PX = 1080f
    private const val REFERENCE_TEXT_SIZE_PX = 44f

    /** Matches FGO's subtitle line separation without changing the font size to fit longer text. */
    const val LINE_SPACING_MULTIPLIER = 1.25f

    fun textSizePx(screenWidth: Int, screenHeight: Int): Float {
        val viewportHeight = FgoViewportGeometry.viewport(screenWidth, screenHeight).height
        return REFERENCE_TEXT_SIZE_PX * viewportHeight / REFERENCE_HEIGHT_PX
    }

    /**
     * Battle OCR rows and model output line breaks describe source/display wrapping,
     * not paragraph semantics. Reflow them so the overlay can use its full safe width.
     * A space is retained only when joining two ASCII words.
     */
    fun displayText(text: String): String {
        val rows = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (rows.isEmpty()) return ""
        return buildString(text.length) {
            rows.forEachIndexed { index, row ->
                if (index > 0 && lastOrNull().isAsciiWordCharacter() && row.first().isAsciiWordCharacter()) {
                    append(' ')
                }
                append(row)
            }
        }
    }

    /** Exact width for short captions; the full safe band for captions that must wrap. */
    fun captionWidthPx(textWidthPx: Float, horizontalPaddingPx: Int, maxWidthPx: Int): Int {
        if (maxWidthPx <= 0) return 0
        val desired = ceil(textWidthPx.coerceAtLeast(0f)).toInt() +
            horizontalPaddingPx.coerceAtLeast(0) * 2
        return desired.coerceIn(1, maxWidthPx)
    }

    private fun Char?.isAsciiWordCharacter(): Boolean =
        this != null && code < 128 && isLetterOrDigit()
}

