package com.fgogotran.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes FGO emphasis dots from a dialogue crop before text detection.
 *
 * Emphasis dots are an annotation layer: small, filled, evenly aligned components immediately
 * above separate full-size glyphs. They are not dialogue punctuation and must not be sent through
 * the recognizer together with the main row. Readable ruby is deliberately left alone; its shapes
 * do not satisfy the filled-dot geometry below and the existing ruby formatter handles it later.
 */
internal object DialogueAnnotationCleaner {
    data class Result(
        val pixels: IntArray,
        val maskedComponents: Int,
        val maskedRows: Int
    ) {
        val changed: Boolean get() = maskedComponents > 0
    }

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixelCount: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val fillRatio: Float get() = pixelCount.toFloat() / (width * height).coerceAtLeast(1)
    }

    private data class MainEvidence(
        val top: Int,
        val bottom: Int,
        val pixelCount: Int
    ) {
        val height: Int get() = bottom - top
    }

    private data class DotCandidate(
        val component: Component,
        val main: MainEvidence
    )

    fun clean(pixels: IntArray, width: Int, height: Int): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Result(pixels, maskedComponents = 0, maskedRows = 0)
        }

        val foreground = BooleanArray(width * height) { index -> pixels[index].isNeutralDialogueInk() }
        val components = connectedComponents(foreground, width, height)
        val maximumDotSide = max(MIN_DOT_SIDE, (height * MAX_DOT_SIDE_HEIGHT_RATIO).roundToInt())
        val candidates = components.mapNotNull { component ->
            if (!component.isDotCandidate(maximumDotSide)) return@mapNotNull null
            val evidence = mainEvidenceBelow(component, foreground, width, height)
                ?: return@mapNotNull null
            DotCandidate(component, evidence)
        }
        if (candidates.size < MIN_DOTS_PER_EMPHASIS_ROW) {
            return Result(pixels, maskedComponents = 0, maskedRows = 0)
        }

        val emphasisRows = candidateRows(candidates)
            .flatMap(::trustedRuns)
            .filter { it.size >= MIN_DOTS_PER_EMPHASIS_ROW }
        if (emphasisRows.isEmpty()) {
            return Result(pixels, maskedComponents = 0, maskedRows = 0)
        }

        val emphasisComponents = emphasisRows
            .flatten()
            .map(DotCandidate::component)
            .distinct()
        val cleaned = pixels.copyOf()
        emphasisComponents.forEach { component ->
            maskComponent(cleaned, width, height, component)
        }
        return Result(
            pixels = cleaned,
            maskedComponents = emphasisComponents.size,
            maskedRows = emphasisRows.size
        )
    }

    private fun connectedComponents(
        foreground: BooleanArray,
        width: Int,
        height: Int
    ): List<Component> {
        val visited = BooleanArray(foreground.size)
        val queue = IntArray(foreground.size)
        val components = mutableListOf<Component>()

        for (start in foreground.indices) {
            if (!foreground[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var count = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
                count++

                for (nextY in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (nextX in max(0, x - 1)..min(width - 1, x + 1)) {
                        val next = nextY * width + nextX
                        if (foreground[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            if (count >= MIN_COMPONENT_PIXELS) {
                components += Component(left, top, right, bottom, count)
            }
        }
        return components
    }

    private fun Component.isDotCandidate(maximumSide: Int): Boolean {
        if (width !in MIN_DOT_SIDE..maximumSide || height !in MIN_DOT_SIDE..maximumSide) return false
        val aspect = max(width, height).toFloat() / min(width, height).coerceAtLeast(1)
        return aspect <= MAX_DOT_ASPECT_RATIO && fillRatio >= MIN_DOT_FILL_RATIO
    }

    private fun mainEvidenceBelow(
        dot: Component,
        foreground: BooleanArray,
        width: Int,
        height: Int
    ): MainEvidence? {
        val halfWidth = max(dot.width, dot.height) * MAIN_SEARCH_HALF_WIDTH_SIDES
        val searchLeft = (dot.centerX.roundToInt() - halfWidth).coerceAtLeast(0)
        val searchRight = (dot.centerX.roundToInt() + halfWidth + 1).coerceAtMost(width)
        if (searchRight <= searchLeft) return null

        val minimumGap = max(MIN_ANNOTATION_GAP, (dot.height * MIN_ANNOTATION_GAP_HEIGHT_RATIO).roundToInt())
        val maximumGap = max(MIN_MAX_ANNOTATION_GAP, (dot.height * MAX_ANNOTATION_GAP_HEIGHT_RATIO).roundToInt())
        val firstSearchRow = (dot.bottom + minimumGap).coerceAtMost(height)
        val lastFirstRow = (dot.bottom + maximumGap).coerceAtMost(height - 1)
        if (firstSearchRow > lastFirstRow) return null

        var mainTop = -1
        for (y in firstSearchRow..lastFirstRow) {
            if (foregroundCount(foreground, width, searchLeft, searchRight, y) >= MIN_MAIN_ROW_PIXELS) {
                mainTop = y
                break
            }
        }
        if (mainTop < 0) return null

        val searchBottom = min(height, mainTop + max(MIN_MAIN_SEARCH_HEIGHT, dot.height * MAIN_SEARCH_HEIGHT_SIDES))
        var mainBottom = mainTop
        var pixelCount = 0
        for (y in mainTop until searchBottom) {
            val rowCount = foregroundCount(foreground, width, searchLeft, searchRight, y)
            pixelCount += rowCount
            if (rowCount > 0) mainBottom = y + 1
        }

        val minimumHeight = max(MIN_MAIN_HEIGHT, (dot.height * MIN_MAIN_HEIGHT_DOT_RATIO).roundToInt())
        if (mainBottom - mainTop < minimumHeight) return null
        if (pixelCount < dot.pixelCount * MIN_MAIN_PIXEL_COUNT_RATIO) return null
        return MainEvidence(mainTop, mainBottom, pixelCount)
    }

    private fun foregroundCount(
        foreground: BooleanArray,
        width: Int,
        left: Int,
        right: Int,
        y: Int
    ): Int {
        var count = 0
        val row = y * width
        for (x in left until right) {
            if (foreground[row + x]) count++
        }
        return count
    }

    private fun candidateRows(candidates: List<DotCandidate>): List<List<DotCandidate>> {
        val rows = mutableListOf<MutableList<DotCandidate>>()
        candidates.sortedWith(compareBy({ it.component.centerY }, { it.component.centerX })).forEach { candidate ->
            val row = rows.lastOrNull()
            val reference = row?.map { it.component.centerY }?.average()?.toFloat()
            val tolerance = max(
                MIN_ROW_ALIGNMENT_TOLERANCE.toFloat(),
                candidate.component.height * MAX_ROW_ALIGNMENT_HEIGHT_RATIO
            )
            if (row != null && reference != null && abs(candidate.component.centerY - reference) <= tolerance) {
                row += candidate
            } else {
                rows += mutableListOf(candidate)
            }
        }
        return rows
    }

    private fun trustedRuns(row: List<DotCandidate>): List<List<DotCandidate>> {
        if (row.size < MIN_DOTS_PER_EMPHASIS_ROW) return emptyList()
        val sorted = row.sortedBy { it.component.centerX }
        val runs = mutableListOf<MutableList<DotCandidate>>()

        sorted.forEach { candidate ->
            val current = runs.lastOrNull()
            val previous = current?.lastOrNull()
            if (previous == null || candidatesShareEmphasisRow(previous, candidate)) {
                if (current == null) runs += mutableListOf(candidate) else current += candidate
            } else {
                runs += mutableListOf(candidate)
            }
        }
        return runs.filter(::isTrustedEmphasisRun)
    }

    private fun candidatesShareEmphasisRow(first: DotCandidate, second: DotCandidate): Boolean {
        val dotSide = (max(first.component.width, first.component.height) +
            max(second.component.width, second.component.height)) / 2f
        val mainHeight = (first.main.height + second.main.height) / 2f
        val centerGap = second.component.centerX - first.component.centerX
        val minimumGap = max(dotSide * MIN_CENTER_GAP_DOT_RATIO, mainHeight * MIN_CENTER_GAP_MAIN_RATIO)
        val maximumGap = mainHeight * MAX_CENTER_GAP_MAIN_RATIO
        return centerGap in minimumGap..maximumGap
    }

    private fun isTrustedEmphasisRun(run: List<DotCandidate>): Boolean {
        if (run.size < MIN_DOTS_PER_EMPHASIS_ROW) return false
        val widths = run.map { it.component.width }
        val heights = run.map { it.component.height }
        if (widths.maxOrNull()!! > widths.minOrNull()!! * MAX_DOT_SIZE_VARIATION_RATIO) return false
        if (heights.maxOrNull()!! > heights.minOrNull()!! * MAX_DOT_SIZE_VARIATION_RATIO) return false

        val mainTops = run.map { it.main.top }
        val mainHeight = run.map { it.main.height }.average().toFloat()
        if ((mainTops.maxOrNull()!! - mainTops.minOrNull()!!).toFloat() >
            max(MIN_MAIN_TOP_VARIATION.toFloat(), mainHeight * MAX_MAIN_TOP_VARIATION_RATIO)
        ) return false

        val gaps = run.zipWithNext { first, second -> second.component.centerX - first.component.centerX }
        if (gaps.size > 1 && gaps.maxOrNull()!! > gaps.minOrNull()!! * MAX_CENTER_GAP_VARIATION_RATIO) {
            return false
        }
        return true
    }

    private fun maskComponent(
        pixels: IntArray,
        width: Int,
        height: Int,
        component: Component
    ) {
        val left = (component.left - MASK_PADDING).coerceAtLeast(0)
        val right = (component.right + MASK_PADDING).coerceAtMost(width)
        val top = (component.top - MASK_PADDING).coerceAtLeast(0)
        val bottom = (component.bottom + MASK_PADDING).coerceAtMost(height)
        for (y in top until bottom) {
            val sampleDistance = MASK_PADDING + 1
            val leftSampleX = (left - sampleDistance).coerceAtLeast(0)
            val rightSampleX = (right + sampleDistance - 1).coerceAtMost(width - 1)
            val leftPixel = pixels[y * width + leftSampleX]
            val rightPixel = pixels[y * width + rightSampleX]
            val background = if (leftPixel.luminance() <= rightPixel.luminance()) leftPixel else rightPixel
            val row = y * width
            for (x in left until right) pixels[row + x] = background
        }
    }

    private fun Int.isNeutralDialogueInk(): Boolean {
        val red = (this shr 16) and 0xff
        val green = (this shr 8) and 0xff
        val blue = this and 0xff
        val brightest = max(red, max(green, blue))
        val darkest = min(red, min(green, blue))
        return luminance() >= MIN_TEXT_LUMINANCE &&
            darkest >= MIN_TEXT_CHANNEL &&
            brightest - darkest <= MAX_TEXT_CHROMA
    }

    private fun Int.luminance(): Int {
        val red = (this shr 16) and 0xff
        val green = (this shr 8) and 0xff
        val blue = this and 0xff
        return (red * 77 + green * 150 + blue * 29) shr 8
    }

    private const val MIN_TEXT_LUMINANCE = 165
    private const val MIN_TEXT_CHANNEL = 120
    private const val MAX_TEXT_CHROMA = 95
    private const val MIN_COMPONENT_PIXELS = 5
    private const val MIN_DOT_SIDE = 3
    private const val MAX_DOT_SIDE_HEIGHT_RATIO = 0.09f
    private const val MAX_DOT_ASPECT_RATIO = 1.55f
    private const val MIN_DOT_FILL_RATIO = 0.55f
    private const val MIN_DOTS_PER_EMPHASIS_ROW = 2
    private const val MAIN_SEARCH_HALF_WIDTH_SIDES = 2
    private const val MIN_ANNOTATION_GAP = 3
    private const val MIN_ANNOTATION_GAP_HEIGHT_RATIO = 0.30f
    private const val MIN_MAX_ANNOTATION_GAP = 8
    private const val MAX_ANNOTATION_GAP_HEIGHT_RATIO = 1.25f
    private const val MIN_MAIN_ROW_PIXELS = 2
    private const val MIN_MAIN_SEARCH_HEIGHT = 28
    private const val MAIN_SEARCH_HEIGHT_SIDES = 6
    private const val MIN_MAIN_HEIGHT = 14
    private const val MIN_MAIN_HEIGHT_DOT_RATIO = 1.80f
    private const val MIN_MAIN_PIXEL_COUNT_RATIO = 1.50f
    private const val MIN_ROW_ALIGNMENT_TOLERANCE = 2
    private const val MAX_ROW_ALIGNMENT_HEIGHT_RATIO = 0.35f
    private const val MIN_CENTER_GAP_DOT_RATIO = 2.40f
    private const val MIN_CENTER_GAP_MAIN_RATIO = 0.65f
    private const val MAX_CENTER_GAP_MAIN_RATIO = 1.80f
    private const val MAX_DOT_SIZE_VARIATION_RATIO = 1.60f
    private const val MIN_MAIN_TOP_VARIATION = 5
    private const val MAX_MAIN_TOP_VARIATION_RATIO = 0.40f
    private const val MAX_CENTER_GAP_VARIATION_RATIO = 1.55f
    private const val MASK_PADDING = 2
}
