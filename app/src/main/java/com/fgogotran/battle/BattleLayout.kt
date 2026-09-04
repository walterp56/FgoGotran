package com.fgogotran.battle

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.math.ceil

/** All coordinates are on the same centered 1920x1080 canvas as story controls. */
object BattleLayout {
    val subtitle = FgoReferenceRect(100, 688, 1820, 866)
    val resultHeader = FgoReferenceRect(720, 0, 1200, 185)
    val hudText = FgoReferenceRect(1315, 6, 1460, 180)
    val hudLabels = listOf(
        FgoReferenceRect(1325, 14, 1455, 66),
        FgoReferenceRect(1325, 68, 1455, 118),
        FgoReferenceRect(1325, 119, 1455, 174)
    )

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

/** Reads only the three small static label regions; changing counters are excluded. */
object BattleHudDetector {
    /** One OCR confirmation on entry prevents a similarly colored effect from entering battle. */
    fun confirmsLabels(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
        return listOf("BATTLE", "ENEMY", "TURN").count { it in compact } >= 2
    }

    fun isVisible(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        if (width < height || height < 240) return false
        return BattleLayout.hudLabels.withIndex().count { (label, reference) ->
            val r = BattleLayout.map(reference, width, height)
            val xs = BooleanArray(r.width.coerceAtLeast(1))
            val ys = BooleanArray(r.height.coerceAtLeast(1))
            var ink = 0
            var outlined = 0
            val step = (height / 1080).coerceAtLeast(1)
            val offset = (height / 540).coerceAtLeast(1)
            for (y in r.top until r.bottom step step) for (x in r.left until r.right step step) {
                val color = pixel(x, y)
                val red = color shr 16 and 255
                val green = color shr 8 and 255
                val blue = color and 255
                val matches = when (label) {
                    0 -> red > 155 && green > 100 && red > blue + 45 && green > blue + 25
                    1 -> red > 140 && red > green * 1.30 && red > blue * 1.35
                    else -> green > 125 && blue > 125 && green > red + 30 && blue > red + 30
                }
                if (matches) {
                    ink++
                    xs[x - r.left] = true
                    ys[y - r.top] = true
                    if (isDark(pixel(x, (y - offset).coerceAtLeast(0))) ||
                        isDark(pixel(x, (y + offset).coerceAtMost(height - 1)))) outlined++
                }
            }
            val area = r.width * r.height / (step * step).toFloat()
            ink >= 12 && ink / area in 0.025f..0.48f &&
                xs.count { it } * step >= r.width * 0.32f &&
                ys.count { it } * step >= r.height * 0.15f && outlined >= ink * 0.25f
        } >= 2
    }

    private fun isDark(pixel: Int) = maxOf(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255) < 140
}
