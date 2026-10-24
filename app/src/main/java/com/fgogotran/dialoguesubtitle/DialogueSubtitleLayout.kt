package com.fgogotran.dialoguesubtitle

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
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
    const val LINE_SPACING_MULTIPLIER = 1.25f
    fun textSizePx(width: Int, height: Int): Float = 44f * FgoViewportGeometry.referenceScale(width, height)
    fun displayText(text: String): String = text.trim().replace(Regex("[\\r\\n]+"), " ")
    fun captionWidthPx(textWidthPx: Float, horizontalPaddingPx: Int, maxWidthPx: Int): Int =
        (ceil(textWidthPx).toInt() + horizontalPaddingPx * 2).coerceIn(1, maxWidthPx.coerceAtLeast(1))
}
