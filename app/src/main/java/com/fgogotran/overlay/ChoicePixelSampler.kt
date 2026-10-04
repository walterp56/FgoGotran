package com.fgogotran.overlay

import com.fgogotran.util.FramePixelReader
import com.fgogotran.util.FramePixelReader.Bounds

/** Exact legacy sampling grids and colour predicates, with capture-local ROI/score reuse. */
internal class ChoicePixelSampler(private val pixels: FramePixelReader) {
    private val darkScores = mutableMapOf<Bounds, Float>()
    private val borderScores = mutableMapOf<Bounds, Float>()

    fun darkRatioInRow(left: Int, right: Int, y: Int): Float {
        val bounds = Bounds(left, y, right, y + 1).clamp(pixels.width, pixels.height)
        if (bounds.isEmpty) return 0f
        val region = pixels.read(bounds)
        var dark = 0
        var total = 0
        for (x in bounds.left until bounds.right step 2) {
            if (isDark(region.getPixel(x, y))) dark++
            total++
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    /**
     * Rules out dark panel runs without scanning every row. A false result is definitive;
     * a true result only asks the full detector to validate the possible panels.
     */
    fun mayContainDarkPanelRows(
        anchor: Bounds,
        minimumHeight: Int,
        maximumGapRows: Int,
        minimumDarkRatio: Float
    ): Boolean {
        if (anchor.isEmpty || anchor != anchor.clamp(pixels.width, pixels.height) ||
            minimumHeight <= 0 || anchor.height < minimumHeight ||
            maximumGapRows < 0 || maximumGapRows >= minimumHeight ||
            minimumDarkRatio !in 0f..1f
        ) return true

        val bandHeight = maximumGapRows + 1
        val stride = minimumHeight - maximumGapRows
        // Every minimum-height run contains a complete sampled band. An accepted run
        // cannot have bandHeight consecutive light rows, so it must hit this exact grid.
        var bandTop = anchor.top
        while (bandTop <= anchor.bottom - bandHeight) {
            pixels.read(Bounds(anchor.left, bandTop, anchor.right, bandTop + bandHeight))
            for (y in bandTop until bandTop + bandHeight) {
                if (darkRatioInRow(anchor.left, anchor.right, y) >= minimumDarkRatio) return true
            }
            bandTop += stride
        }
        return false
    }

    fun darkRatioInRect(bounds: Bounds): Float = ratio(bounds, 3, darkScores, ::isDark)

    fun borderRatioInRect(bounds: Bounds): Float = ratio(bounds, 2, borderScores, ::isBorder)

    private fun ratio(
        requested: Bounds,
        step: Int,
        scores: MutableMap<Bounds, Float>,
        matches: (Int) -> Boolean
    ): Float {
        val bounds = requested.clamp(pixels.width, pixels.height)
        return scores.getOrPut(bounds) {
            if (bounds.isEmpty) return@getOrPut 0f
            val region = pixels.read(bounds)
            var matched = 0
            var total = 0
            for (y in bounds.top until bounds.bottom step step) {
                for (x in bounds.left until bounds.right step step) {
                    if (matches(region.getPixel(x, y))) matched++
                    total++
                }
            }
            if (total == 0) 0f else matched.toFloat() / total
        }
    }

    private fun isDark(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // Keep floating-point rounding identical to BackgroundDetector's original predicate.
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt() < 80
    }

    private fun isBorder(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        return (r >= 175 && g >= 185 && b >= 190 && spread <= 82) ||
            (r >= 80 && g >= 125 && b >= 150 && b >= r + 24 && g >= r + 12) ||
            (r >= 110 && g >= 150 && b >= 170 && b >= r + 18 && spread <= 115)
    }
}
