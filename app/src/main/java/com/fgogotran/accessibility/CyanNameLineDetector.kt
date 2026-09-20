package com.fgogotran.accessibility

/** Finds the right end of the thin cyan border above FGO's speaker name. */
internal object CyanNameLineDetector {
    fun rightEdge(
        pixelAt: (Int, Int) -> Int,
        imageWidth: Int,
        imageHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int? {
        val height = bottom - top
        if (height <= 0 || right - left < height * 3 || left < 0 || right > imageWidth) return null

        // The border is just above the fixed OCR band. Choose rows from its fixed left portion;
        // scenery elsewhere in the band must not decide which colour/height we trace.
        val anchorStart = (left + height / 5).coerceAtMost(right - 1)
        val anchorEnd = (left + height).coerceAtMost(right - 1)
        val anchorStep = (height / 20).coerceAtLeast(1)
        val lineRows = ((top - height / 16).coerceAtLeast(0)..
            (top + height / 40).coerceAtMost(imageHeight - 1)).filter { y ->
            var samples = 0
            var matches = 0
            for (x in anchorStart..anchorEnd step anchorStep) {
                samples++
                if (isCyanBorder(pixelAt(x, y))) matches++
            }
            samples > 0 && matches * 4 >= samples * 3
        }
        if (lineRows.size < 2) return null

        val requiredRows = maxOf(2, (lineRows.size * 2 + 4) / 5)
        fun onLine(x: Int): Boolean = lineRows.count { y -> isCyanBorder(pixelAt(x, y)) } >= requiredRows
        if ((left until minOf(right, left + 8)).count(::onLine) < 6) return null

        val gapLength = (height * 0.15f).toInt().coerceAtLeast(8)
        var gap = 0
        for (x in left until right) {
            gap = if (onLine(x)) 0 else gap + 1
            if (gap >= gapLength) {
                val end = x - gap + 1
                return end.takeIf { it - left >= maxOf(60, height * 3) }
            }
        }
        return null
    }

    private fun isCyanBorder(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return r >= 100 && g >= 165 && b >= 170 &&
            g - r >= 20 && b - r >= 15 && kotlin.math.abs(g - b) <= 55
    }
}
