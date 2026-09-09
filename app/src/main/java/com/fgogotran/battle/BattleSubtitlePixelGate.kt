package com.fgogotran.battle

import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cheap change detector for the fixed battle-subtitle band.
 *
 * It tracks bright, dark-outlined pixels rather than the complete frame, so normal
 * battle animation does not force OCR unless the visible subtitle pattern changes.
 * A periodic read is retained as a safety net for low-contrast or unusual captions.
 */
internal class BattleSubtitlePixelGate {
    private var lastReadSignature: Signature? = null
    private var lastReadAt = Long.MIN_VALUE

    fun shouldRecognize(
        width: Int,
        height: Int,
        pixel: (Int, Int) -> Int,
        now: Long,
        confirmationRequired: Boolean
    ): Boolean {
        val signature = sample(width, height, pixel)
        val previous = lastReadSignature
        val periodicReadDue =
            lastReadAt == Long.MIN_VALUE || now - lastReadAt >= MAX_UNCHANGED_READ_MS

        val shouldRead = confirmationRequired ||
                previous == null ||
                periodicReadDue ||
                visiblyDifferent(previous, signature)

        if (shouldRead) {
            lastReadSignature = signature
            lastReadAt = now
        }

        return shouldRead
    }

    fun reset() {
        lastReadSignature = null
        lastReadAt = Long.MIN_VALUE
    }

    private fun sample(
        width: Int,
        height: Int,
        pixel: (Int, Int) -> Int
    ): Signature {
        if (width <= 0 || height <= 0) {
            return Signature(IntArray(CELL_COLUMNS * CELL_ROWS))
        }

        val bounds = BattleLayout.map(BattleLayout.subtitle, width, height)
        val viewportHeight = FgoViewportGeometry.viewport(width, height).height
        val step = (
                viewportHeight / 1080f * SAMPLE_STEP_REFERENCE_PX
                ).roundToInt().coerceAtLeast(1)

        val outlineDistance = (
                viewportHeight / 1080f * OUTLINE_DISTANCE_REFERENCE_PX
                ).roundToInt().coerceAtLeast(1)

        val cells = IntArray(CELL_COLUMNS * CELL_ROWS)

        var y = bounds.top.coerceAtLeast(0)
        val bottom = bounds.bottom.coerceAtMost(height)
        val left = bounds.left.coerceAtLeast(0)
        val right = bounds.right.coerceAtMost(width)

        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = pixel(x, y)

                if (
                    isBrightNeutral(color) &&
                    hasDarkNeighbour(
                        x,
                        y,
                        outlineDistance,
                        width,
                        height,
                        pixel
                    )
                ) {
                    val column = (
                            (x - left) * CELL_COLUMNS /
                                    (right - left).coerceAtLeast(1)
                            ).coerceIn(0, CELL_COLUMNS - 1)

                    val row = (
                            (y - bounds.top) * CELL_ROWS /
                                    bounds.height.coerceAtLeast(1)
                            ).coerceIn(0, CELL_ROWS - 1)

                    val index = row * CELL_COLUMNS + column
                    cells[index] = (cells[index] + 1).coerceAtMost(MAX_CELL_INK)
                }

                x += step
            }
            y += step
        }

        return Signature(cells)
    }

    private fun visiblyDifferent(
        first: Signature,
        second: Signature
    ): Boolean {
        var changedCells = 0
        var totalDifference = 0

        for (index in first.cells.indices) {
            val difference = abs(first.cells[index] - second.cells[index])
            totalDifference += difference

            if (difference >= MIN_CELL_DIFFERENCE) {
                changedCells++
            }
        }

        return changedCells >= MIN_CHANGED_CELLS &&
                totalDifference >= MIN_TOTAL_DIFFERENCE
    }

    private fun hasDarkNeighbour(
        x: Int,
        y: Int,
        distance: Int,
        width: Int,
        height: Int,
        pixel: (Int, Int) -> Int
    ): Boolean {
        return (x >= distance && isDark(pixel(x - distance, y))) ||
                (x + distance < width && isDark(pixel(x + distance, y))) ||
                (y >= distance && isDark(pixel(x, y - distance))) ||
                (y + distance < height && isDark(pixel(x, y + distance)))
    }

    private fun isBrightNeutral(color: Int): Boolean {
        val red = color shr 16 and 255
        val green = color shr 8 and 255
        val blue = color and 255

        return minOf(red, green, blue) >= MIN_BRIGHT_CHANNEL &&
                maxOf(red, green, blue) - minOf(red, green, blue) <= MAX_CHANNEL_SPREAD
    }

    private fun isDark(color: Int): Boolean =
        maxOf(
            color shr 16 and 255,
            color shr 8 and 255,
            color and 255
        ) <= MAX_DARK_CHANNEL

    private data class Signature(
        val cells: IntArray
    )

    companion object {
        private const val CELL_COLUMNS = 64
        private const val CELL_ROWS = 12
        private const val SAMPLE_STEP_REFERENCE_PX = 3f
        private const val OUTLINE_DISTANCE_REFERENCE_PX = 2f
        private const val MIN_BRIGHT_CHANNEL = 172
        private const val MAX_CHANNEL_SPREAD = 58
        private const val MAX_DARK_CHANNEL = 135
        private const val MAX_CELL_INK = 31
        private const val MIN_CELL_DIFFERENCE = 2
        private const val MIN_CHANGED_CELLS = 3
        private const val MIN_TOTAL_DIFFERENCE = 8
        private const val MAX_UNCHANGED_READ_MS = 500L
    }
}