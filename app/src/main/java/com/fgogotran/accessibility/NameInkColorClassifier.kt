package com.fgogotran.accessibility

internal enum class NameInkColor {
    NEUTRAL,
    RED,
    CYAN,
    YELLOW_GREEN
}

/** Classifies the supported FGO speaker-name glyph palettes without examining the background. */
internal fun classifyNameInkColor(red: Int, green: Int, blue: Int): NameInkColor? {
    val brightest = maxOf(red, green, blue)
    val darkest = minOf(red, green, blue)
    return when {
        red >= NAME_INK_MIN_BRIGHTNESS && red - maxOf(green, blue) >= 40 -> NameInkColor.RED
        isFgoCyanTextColor(red, green, blue) -> NameInkColor.CYAN
        green >= NAME_INK_MIN_BRIGHTNESS && green - maxOf(red, blue) >= 15 ->
            NameInkColor.YELLOW_GREEN
        brightest >= NAME_INK_MIN_BRIGHTNESS &&
            brightest - darkest <= NAME_INK_MAX_NEUTRAL_SPREAD -> NameInkColor.NEUTRAL
        else -> null
    }
}

/** The cyan predicate already used by text masks, reused by name-glyph measurement. */
internal fun isFgoCyanTextColor(red: Int, green: Int, blue: Int): Boolean =
    green >= 140 && blue >= 140 && minOf(green, blue) - red >= 35

private const val NAME_INK_MIN_BRIGHTNESS = 170
private const val NAME_INK_MAX_NEUTRAL_SPREAD = 80
