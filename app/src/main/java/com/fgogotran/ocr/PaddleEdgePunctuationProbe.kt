package com.fgogotran.ocr

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cheap visual gate for Paddle's wider punctuation-recognition pass.
 *
 * [pixels] contains the axis-aligned bounds of the wider crop. The text bounds
 * identify the original detector box inside that window. Only bright,
 * punctuation-sized components immediately beside the original box and on the
 * same visual row are treated as evidence. This intentionally prefers an
 * occasional missed edge mark over running a second ONNX recognition for every
 * ordinary FGO text line.
 */
internal object PaddleEdgePunctuationProbe {
    fun hasEvidence(
        pixels: IntArray,
        width: Int,
        height: Int,
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int
    ): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        if (textLeft !in 0 until width || textTop !in 0 until height) return false
        if (textRight !in 1..width || textBottom !in 1..height) return false
        if (textRight <= textLeft || textBottom <= textTop) return false

        val lineHeight = (textBottom - textTop).coerceAtLeast(1)
        val foregroundThreshold = foregroundThreshold(
            pixels = pixels,
            width = width,
            textLeft = textLeft,
            textTop = textTop,
            textRight = textRight,
            textBottom = textBottom
        ) ?: return false
        val verticalPadding = max(1, (lineHeight * VERTICAL_SCAN_PADDING_RATIO).roundToInt())
        val scanTop = (textTop - verticalPadding).coerceAtLeast(0)
        val scanBottom = (textBottom + verticalPadding).coerceAtMost(height)
        val active = BooleanArray(width * height)
        for (y in scanTop until scanBottom) {
            val rowOffset = y * width
            for (x in 0 until width) {
                if (x in textLeft until textRight) continue
                active[rowOffset + x] = luminance(pixels[rowOffset + x]) >= foregroundThreshold
            }
        }

        val visited = BooleanArray(active.size)
        val queue = IntArray(active.size)
        for (start in active.indices) {
            if (!active[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var pixelCount = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
                pixelCount++

                val minY = maxOf(scanTop, y - 1)
                val maxY = minOf(scanBottom - 1, y + 1)
                val minX = maxOf(0, x - 1)
                val maxX = minOf(width - 1, x + 1)
                for (nextY in minY..maxY) {
                    for (nextX in minX..maxX) {
                        if (nextX == x && nextY == y) continue
                        val next = nextY * width + nextX
                        if (active[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            if (isPunctuationSizedEvidence(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    pixelCount = pixelCount,
                    textLeft = textLeft,
                    textTop = textTop,
                    textRight = textRight,
                    textBottom = textBottom,
                    lineHeight = lineHeight
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun foregroundThreshold(
        pixels: IntArray,
        width: Int,
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int
    ): Int? {
        val histogram = IntArray(256)
        var samples = 0
        for (y in textTop until textBottom step SAMPLE_STEP) {
            val rowOffset = y * width
            for (x in textLeft until textRight step SAMPLE_STEP) {
                histogram[luminance(pixels[rowOffset + x])]++
                samples++
            }
        }
        if (samples == 0) return null

        val brightReference = percentile(histogram, samples, FOREGROUND_REFERENCE_PERCENTILE)
        if (brightReference < MIN_TEXT_REFERENCE_LUMA) return null
        return max(MIN_FOREGROUND_LUMA, brightReference - FOREGROUND_REFERENCE_MARGIN)
    }

    private fun percentile(histogram: IntArray, samples: Int, percentile: Float): Int {
        val target = (samples * percentile).roundToInt().coerceIn(1, samples)
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen >= target) return value
        }
        return histogram.lastIndex
    }

    private fun isPunctuationSizedEvidence(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        pixelCount: Int,
        textLeft: Int,
        textTop: Int,
        textRight: Int,
        textBottom: Int,
        lineHeight: Int
    ): Boolean {
        val componentWidth = right - left
        val componentHeight = bottom - top
        val minimumPixels = max(MIN_COMPONENT_PIXELS, (lineHeight * MIN_PIXEL_HEIGHT_RATIO).roundToInt())
        if (pixelCount < minimumPixels || componentWidth <= 0 || componentHeight <= 0) return false

        val compactMark = componentWidth <= lineHeight * MAX_COMPACT_WIDTH_RATIO &&
            componentHeight <= lineHeight * MAX_COMPACT_HEIGHT_RATIO
        val horizontalMark = componentWidth <= lineHeight * MAX_HORIZONTAL_WIDTH_RATIO &&
            componentHeight <= lineHeight * MAX_HORIZONTAL_HEIGHT_RATIO
        if (!compactMark && !horizontalMark) return false

        val verticalOverlap = (
            minOf(bottom, textBottom) - maxOf(top, textTop)
            ).coerceAtLeast(0)
        val componentCenterY = (top + bottom) / 2f
        val textCenterY = (textTop + textBottom) / 2f
        val sharesTextRow = verticalOverlap >= minOf(componentHeight, lineHeight) * MIN_VERTICAL_OVERLAP_RATIO ||
            kotlin.math.abs(componentCenterY - textCenterY) <= lineHeight * MAX_CENTER_DIFFERENCE_RATIO
        if (!sharesTextRow) return false

        val horizontalGap = when {
            right <= textLeft -> textLeft - right
            left >= textRight -> left - textRight
            else -> return false
        }
        val maximumGap = max(MIN_HORIZONTAL_GAP, (lineHeight * MAX_HORIZONTAL_GAP_RATIO).roundToInt())
        return horizontalGap <= maximumGap
    }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return (red * 77 + green * 150 + blue * 29) shr 8
    }

    private const val SAMPLE_STEP = 2
    private const val FOREGROUND_REFERENCE_PERCENTILE = 0.97f
    private const val MIN_TEXT_REFERENCE_LUMA = 155
    private const val MIN_FOREGROUND_LUMA = 150
    private const val FOREGROUND_REFERENCE_MARGIN = 55
    private const val VERTICAL_SCAN_PADDING_RATIO = 0.12f
    private const val MIN_COMPONENT_PIXELS = 2
    private const val MIN_PIXEL_HEIGHT_RATIO = 0.06f
    private const val MAX_COMPACT_WIDTH_RATIO = 1.30f
    private const val MAX_COMPACT_HEIGHT_RATIO = 1.05f
    private const val MAX_HORIZONTAL_WIDTH_RATIO = 6f
    private const val MAX_HORIZONTAL_HEIGHT_RATIO = 0.38f
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.18f
    private const val MAX_CENTER_DIFFERENCE_RATIO = 0.58f
    private const val MIN_HORIZONTAL_GAP = 3
    private const val MAX_HORIZONTAL_GAP_RATIO = 0.85f
}
