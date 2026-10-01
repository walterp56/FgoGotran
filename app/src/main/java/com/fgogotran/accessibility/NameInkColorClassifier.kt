package com.fgogotran.accessibility

import com.fgogotran.ocr.FgoStoryTextPalette

internal typealias NameInkColor = FgoStoryTextPalette.Family

/** Classifies the supported FGO speaker-name glyph palettes without examining the background. */
internal fun classifyNameInkColor(red: Int, green: Int, blue: Int): NameInkColor? {
    // Brown and pink must be checked before the legacy red and neutral tests.
    FgoStoryTextPalette.additionalInkFamily(red, green, blue)?.let { family ->
        return family
    }
    val brightest = maxOf(red, green, blue)
    val darkest = minOf(red, green, blue)
    return when {
        red >= NAME_INK_MIN_BRIGHTNESS && FgoStoryTextPalette.isRedInk(red, green, blue) ->
            NameInkColor.RED
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
    FgoStoryTextPalette.isCyanInk(red, green, blue)

private const val NAME_INK_MIN_BRIGHTNESS = 170
private const val NAME_INK_MAX_NEUTRAL_SPREAD = 80
