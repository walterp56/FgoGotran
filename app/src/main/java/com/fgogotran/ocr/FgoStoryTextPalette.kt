package com.fgogotran.ocr

/** Text ink only: deliberately not used to detect cyan UI borders or nameplate widths. */
internal object FgoStoryTextPalette {
    enum class Family { NEUTRAL, RED, CYAN, YELLOW_GREEN, PINK, PURPLE, BROWN }
    enum class Scope { STORY, CHOICE }

    data class Sample(val rgb: Int, val family: Family, val maxDistanceSquared: Int) {
        val renderColor: Int = rgb or (0xff shl 24)
        private val red = (rgb shr 16) and 0xff
        private val green = (rgb shr 8) and 0xff
        private val blue = rgb and 0xff

        fun distanceSquared(r: Int, g: Int, b: Int): Int {
            val dr = r - red
            val dg = g - green
            val db = b - blue
            return dr * dr + dg * dg + db * db
        }

        fun accepts(r: Int, g: Int, b: Int, distance: Int): Boolean =
            distance <= maxDistanceSquared &&
                // Red's antialiased edges can be near brown in RGB distance. Brown has a
                // warmer green-over-blue hue; do not let it steal unchanged red strokes.
                (family != Family.BROWN || (r - g >= 30 && g - b >= 8))
    }

    // Keep the four legacy vote indices and render colours stable. Only the three approved
    // additions are runtime swatches; the research inventory is not a runtime whitelist.
    // See FGO_STORY_TEXT_COLOURS.md and scripts/research-fgo-story-colours.py for provenance.
    val samples = listOf(
        Sample(0xF5F5F0, Family.NEUTRAL, 120 * 120),
        Sample(0xDC0000, Family.RED, 100 * 100),
        Sample(0x50EBEB, Family.CYAN, 115 * 115),
        Sample(0xC5E35E, Family.YELLOW_GREEN, 90 * 90),
        Sample(0xFF69B4, Family.PINK, TAG_TOLERANCE_SQUARED),
        Sample(0x8753D1, Family.PURPLE, TAG_TOLERANCE_SQUARED),
        Sample(0xB86349, Family.BROWN, TAG_TOLERANCE_SQUARED)
    )
    private val choiceSamples = samples.take(2)

    fun samplesFor(scope: Scope): List<Sample> = if (scope == Scope.CHOICE) choiceSamples else samples

    fun nearestIndex(red: Int, green: Int, blue: Int, scope: Scope = Scope.STORY): Int {
        val allowedSamples = samplesFor(scope)
        var bestIndex = -1
        var bestDistance = Int.MAX_VALUE
        for (index in allowedSamples.indices) {
            val sample = allowedSamples[index]
            val distance = sample.distanceSquared(red, green, blue)
            if (distance < bestDistance && sample.accepts(red, green, blue, distance)) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    /** Only the approved pink, purple and brown additions, with screenshot tolerance. */
    fun additionalInkFamily(red: Int, green: Int, blue: Int): Family? {
        if (maxOf(red, green, blue) < 100) return null
        var bestDistance = Int.MAX_VALUE
        var family: Family? = null
        for (index in LEGACY_SAMPLE_COUNT until samples.size) {
            val sample = samples[index]
            val distance = sample.distanceSquared(red, green, blue)
            if (distance < bestDistance && sample.accepts(red, green, blue, distance)) {
                bestDistance = distance
                family = sample.family
            }
        }
        // A pastel tag must not steal an unchanged white/cyan/red pixel from its closer
        // legacy swatch. This also keeps name-glyph voting stable around antialiased edges.
        if (family != null) {
            for (index in 0 until LEGACY_SAMPLE_COUNT) {
                if (samples[index].distanceSquared(red, green, blue) <= bestDistance) return null
            }
        }
        return family
    }

    fun isCyanInk(red: Int, green: Int, blue: Int): Boolean =
        green >= 140 && blue >= 140 && minOf(green, blue) - red >= 35

    fun isColoredInk(red: Int, green: Int, blue: Int, scope: Scope = Scope.STORY): Boolean {
        if (scope == Scope.CHOICE) return isRedInk(red, green, blue)
        return (red >= 165 && red - maxOf(green, blue) >= 40 && hasRedHue(red, green, blue)) ||
            isCyanInk(red, green, blue) ||
            (green >= 150 && green - maxOf(red, blue) >= 15) ||
            additionalInkFamily(red, green, blue) != null
    }

    // Preserve the existing neutral-pixel thresholds used by each OCR helper.
    fun isDialogueInk(red: Int, green: Int, blue: Int): Boolean =
        isNeutralInk(red, green, blue, minLuma = 165, minChannel = 120, maxChroma = 95) ||
            isColoredInk(red, green, blue)

    fun isChoiceInk(red: Int, green: Int, blue: Int): Boolean =
        isNeutralInk(red, green, blue, minLuma = 160, minChannel = 115, maxChroma = 90) ||
            isRedInk(red, green, blue)

    private fun isNeutralInk(
        red: Int, green: Int, blue: Int, minLuma: Int, minChannel: Int, maxChroma: Int
    ): Boolean {
        val luminance = (red * 77 + green * 150 + blue * 29) shr 8
        return luminance >= minLuma && minOf(red, green, blue) >= minChannel &&
            maxOf(red, green, blue) - minOf(red, green, blue) <= maxChroma
    }

    /** Retain the dim-red fallback, but exclude warm highlights and the approved brown/pink. */
    fun isRedInk(red: Int, green: Int, blue: Int): Boolean {
        val strongestNonRed = maxOf(green, blue)
        val vividRed = red >= 130 && red - strongestNonRed >= 35
        val dimRed = red >= 95 && red - strongestNonRed >= 24
        if ((!vividRed && !dimRed) || !hasRedHue(red, green, blue)) return false
        return additionalInkFamily(red, green, blue) == null
    }

    private fun hasRedHue(red: Int, green: Int, blue: Int): Boolean =
        red >= green * 2 && red >= blue * 2

    private const val LEGACY_SAMPLE_COUNT = 4
    private const val TAG_TOLERANCE_SQUARED = 55 * 55
}
