package com.fgogotran.accessibility

/** A conservative width fallback for a nameplate whose usual cyan top line is missing. */
internal object BlueNamePlateDetector {
    // Read only the top edge and upper interior, never the dialogue joined to the plate below.
    fun bandTopOffset(nameHeight: Int): Int = -nameHeight / 16
    fun bandBottomOffset(nameHeight: Int): Int = nameHeight / 4

    /** Returns an exclusive, band-local right edge; the caller keeps the fixed name left/height. */
    fun rightEdge(
        pixels: IntArray,
        width: Int,
        bandHeight: Int,
        nameHeight: Int,
        nameTopInBand: Int
    ): Int? {
        if (nameHeight < 32 || width < nameHeight * 3L || bandHeight <= 0 ||
            nameTopInBand !in 0 until bandHeight || pixels.size.toLong() < width.toLong() * bandHeight
        ) return null

        val interiorTop = nameTopInBand + maxOf(4, nameHeight / 8)
        val interiorBottom = minOf(bandHeight, nameTopInBand + nameHeight / 4)
        if (interiorTop >= interiorBottom) return null

        val anchorStart = nameHeight / 5
        val anchorEnd = nameHeight
        val anchorStep = (nameHeight / 20).coerceAtLeast(1)
        fun anchorVotes(y: Int): Pair<Int, Int> {
            var samples = 0
            var matches = 0
            for (x in anchorStart until anchorEnd step anchorStep) {
                samples++
                if (isBlueBackground(pixels[y * width + x])) matches++
            }
            return matches to samples
        }

        // A horizontal blue patch alone can be scenery. Require evidence of a top transition
        // immediately above the sampled interior, without depending on a border's specific colour.
        var nonBlueRows = 0
        for (y in 0 until interiorTop) {
            val (matches, samples) = anchorVotes(y)
            if (matches * 4 <= samples) nonBlueRows++
        }
        if (nonBlueRows < 2) return null

        val rowStep = (nameHeight / 40).coerceAtLeast(1)
        val sampledRows = (interiorBottom - interiorTop + rowStep - 1) / rowStep
        val minimumRows = maxOf(3, (sampledRows * 3 + 3) / 4)
        val ends = IntArray(sampledRows)
        var endCount = 0
        for (y in interiorTop until interiorBottom step rowStep) {
            val (matches, samples) = anchorVotes(y)
            if (matches * 4 < samples * 3) continue
            val end = rowRightEdge(pixels, width, y, nameHeight, anchorStart) ?: continue
            ends[endCount++] = end
        }
        if (endCount < minimumRows) return null

        val sortedEnds = ends.copyOf(endCount).apply { sort() }
        val medianEnd = sortedEnds[endCount / 2]
        val edgeTolerance = maxOf(3, nameHeight / 10)
        var agreeingRows = 0
        var right = 0
        for (end in sortedEnds) {
            if (kotlin.math.abs(end - medianEnd) > edgeTolerance) continue
            agreeingRows++
            right = maxOf(right, end)
        }
        return right.takeIf { agreeingRows >= minimumRows }
    }

    private fun rowRightEdge(
        pixels: IntArray,
        width: Int,
        y: Int,
        nameHeight: Int,
        anchorStart: Int
    ): Int? {
        val row = y * width
        // A thick leading frame may occupy a few pixels, but it must still meet the fixed left
        // anchor. This does not move the actual OCR left edge.
        var firstBlue = 0
        while (firstBlue <= anchorStart && !isBlueBackground(pixels[row + firstBlue])) firstBlue++
        if (firstBlue > anchorStart) return null

        val gapLength = (nameHeight * 0.15f).toInt().coerceAtLeast(8)
        var gap = 0
        for (x in firstBlue until width) {
            gap = if (isBlueBackground(pixels[row + x])) 0 else gap + 1
            if (gap >= gapLength) {
                val end = x - gap + 1
                return end.takeIf { it >= maxOf(60, nameHeight * 3) }
            }
        }
        // Never assume the search limit is the real plate end.
        return null
    }

    private fun isBlueBackground(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        // This is the dark blue UI fill, not cyan glyphs, bright borders, or the story palette.
        return r <= 150 && g in 25..185 && b in 55..230 &&
            b - r >= 25 && g - r >= 10 && b - g >= 15
    }
}
