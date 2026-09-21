package com.fgogotran.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recovers FGO choice pauses which OCR detectors commonly discard as noise.
 *
 * Choice panels are known one-line controls, but may also contain a smaller ruby row above the
 * main text. This helper therefore finds the largest lower text row first and only accepts visual
 * punctuation on that row. It works on the already captured shared choice pixels and never invokes
 * an OCR model.
 */
internal object ChoicePunctuationRecovery {
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun union(other: Bounds): Bounds = Bounds(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom)
        )
    }

    data class Line(
        val sourceIndex: Int,
        val text: String,
        val bounds: Bounds,
        val confidence: Float
    )

    data class Result(
        val lines: List<Line>,
        val recoveredCount: Int
    )

    private enum class Kind {
        PAUSE,
        DASH
    }

    private data class Component(
        val bounds: Bounds,
        val pixelCount: Int
    )

    private data class Candidate(
        val kind: Kind,
        val text: String,
        val bounds: Bounds
    )

    fun recover(
        pixels: IntArray,
        width: Int,
        height: Int,
        buttons: List<Bounds>,
        lines: List<Line>
    ): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height || buttons.isEmpty()) {
            return Result(lines, 0)
        }

        val output = lines.toMutableList()
        var nextSourceIndex = (lines.maxOfOrNull(Line::sourceIndex) ?: -1) + 1
        var recoveredCount = 0

        buttons.forEach { rawButton ->
            val button = rawButton.clipped(width, height) ?: return@forEach
            val rowLines = output.filter { lineBelongsToButton(it.bounds, button) }
            val mainLines = mainTextLines(rowLines)
            val referenceHeight = mainLines.maxOfOrNull { it.bounds.height }
                ?.coerceAtLeast(1)
                ?: (button.height * DEFAULT_TEXT_HEIGHT_RATIO).roundToInt().coerceAtLeast(1)
            val expectedCenterY = mainLines
                .maxByOrNull { it.bounds.height }
                ?.bounds
                ?.centerY
                ?: button.centerY

            val candidates = detectCandidates(
                pixels = pixels,
                sourceWidth = width,
                sourceHeight = height,
                button = button,
                referenceHeight = referenceHeight,
                expectedCenterY = expectedCenterY,
                hasMainText = mainLines.isNotEmpty()
            )
            if (candidates.isEmpty()) return@forEach

            if (mainLines.isEmpty()) {
                val punctuationLines = rowLines.filter { it.text.isPauseOrDashOnly() }
                val standaloneText = candidates
                    .sortedBy { it.bounds.left }
                    .joinToString(separator = "", transform = Candidate::text)
                val standaloneBounds = candidates
                    .map(Candidate::bounds)
                    .reduce(Bounds::union)
                val existing = punctuationLines.minByOrNull { it.bounds.left }
                if (existing != null) {
                    if (existing.text.trim() != standaloneText || existing.bounds != standaloneBounds) {
                        output.replaceLine(
                            existing.sourceIndex,
                            existing.copy(text = standaloneText, bounds = standaloneBounds)
                        )
                        punctuationLines
                            .filterNot { it.sourceIndex == existing.sourceIndex }
                            .forEach { output.removeLine(it.sourceIndex) }
                        recoveredCount++
                    }
                } else {
                    output += Line(
                        sourceIndex = nextSourceIndex++,
                        text = standaloneText,
                        bounds = standaloneBounds,
                        confidence = RECOVERED_CONFIDENCE
                    )
                    recoveredCount++
                }
                return@forEach
            }

            val mainSourceIndexes = mainLines.mapTo(mutableSetOf(), Line::sourceIndex)
            candidates.sortedBy { it.bounds.left }.forEach candidateLoop@ { candidate ->
                val currentMainLines = output.filter { it.sourceIndex in mainSourceIndexes }
                val target = bestTargetLine(candidate, currentMainLines, referenceHeight)
                    ?: return@candidateLoop
                val mergedText = mergeCandidateText(target, candidate, referenceHeight)
                    ?: return@candidateLoop
                val textChanged = mergedText != target.text
                val boundsChanged = !target.bounds.contains(candidate.bounds)
                if (textChanged || boundsChanged) {
                    output.replaceLine(
                        target.sourceIndex,
                        target.copy(
                            text = mergedText,
                            bounds = target.bounds.union(candidate.bounds)
                        )
                    )
                    if (textChanged) recoveredCount++
                }

                // A high-confidence punctuation fragment may already be present as its own OCR
                // line. Once it is attached to the main choice line, remove only the geometrically
                // matching fragment so ruby and ordinary text remain untouched.
                output
                    .filter { line ->
                        line.sourceIndex != target.sourceIndex &&
                            line.sourceIndex !in mainSourceIndexes &&
                            line.text.isPauseOrDashOnly() &&
                            line.bounds.isNear(candidate.bounds, referenceHeight)
                    }
                    .map(Line::sourceIndex)
                    .forEach { sourceIndex -> output.removeLine(sourceIndex) }
            }
        }

        return Result(output, recoveredCount)
    }

    private fun detectCandidates(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        button: Bounds,
        referenceHeight: Int,
        expectedCenterY: Float,
        hasMainText: Boolean
    ): List<Candidate> {
        val insetX = max(MIN_SCAN_INSET, (button.width * HORIZONTAL_SCAN_INSET_RATIO).roundToInt())
        val insetY = max(MIN_SCAN_INSET, (button.height * VERTICAL_SCAN_INSET_RATIO).roundToInt())
        val scan = Bounds(
            left = button.left + insetX,
            top = button.top + insetY,
            right = button.right - insetX,
            bottom = button.bottom - insetY
        ).clipped(sourceWidth, sourceHeight) ?: return emptyList()
        if (scan.width <= 0 || scan.height <= 0) return emptyList()

        val components = brightNeutralComponents(pixels, sourceWidth, scan)
        if (components.isEmpty()) return emptyList()

        val maximumCenterDifference = referenceHeight * if (hasMainText) {
            MAX_MAIN_CENTER_DIFFERENCE_RATIO
        } else {
            MAX_STANDALONE_CENTER_DIFFERENCE_RATIO
        }
        val aligned = components.filter { component ->
            abs(component.bounds.centerY - expectedCenterY) <= maximumCenterDifference
        }
        if (aligned.isEmpty()) return emptyList()

        val pauseCandidates = detectPauseCandidates(aligned, referenceHeight)
        val dashCandidates = aligned.mapNotNull { component ->
            val componentWidth = component.bounds.width
            val componentHeight = component.bounds.height
            val minimumWidth = (referenceHeight * DASH_MIN_WIDTH_RATIO).roundToInt()
            val maximumWidth = (referenceHeight * DASH_MAX_WIDTH_RATIO).roundToInt()
            val maximumHeight = max(
                DASH_MIN_MAX_HEIGHT,
                (referenceHeight * DASH_MAX_HEIGHT_RATIO).roundToInt()
            )
            val fillRatio = component.pixelCount.toFloat() /
                (componentWidth * componentHeight).coerceAtLeast(1)
            if (componentWidth in minimumWidth..maximumWidth &&
                componentHeight in 1..maximumHeight &&
                componentWidth >= componentHeight * DASH_MIN_ASPECT_RATIO &&
                fillRatio >= DASH_MIN_FILL_RATIO
            ) {
                Candidate(Kind.DASH, DASH_TEXT, component.bounds)
            } else {
                null
            }
        }

        return (pauseCandidates + dashCandidates)
            .sortedBy { it.bounds.left }
            .fold(mutableListOf()) { accepted, candidate ->
                val duplicate = accepted.any { existing ->
                    existing.kind == candidate.kind &&
                        existing.bounds.overlapRatio(candidate.bounds) >= DUPLICATE_OVERLAP_RATIO
                }
                if (!duplicate) accepted += candidate
                accepted
            }
    }

    private fun brightNeutralComponents(
        pixels: IntArray,
        sourceWidth: Int,
        scan: Bounds
    ): List<Component> {
        val localWidth = scan.width
        val localHeight = scan.height
        val active = BooleanArray(localWidth * localHeight)
        for (localY in 0 until localHeight) {
            val sourceRow = (scan.top + localY) * sourceWidth + scan.left
            val localRow = localY * localWidth
            for (localX in 0 until localWidth) {
                active[localRow + localX] = pixels[sourceRow + localX].isChoiceTextPixel()
            }
        }

        val queue = IntArray(active.size)
        val components = mutableListOf<Component>()
        for (start in active.indices) {
            if (!active[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            active[start] = false
            var left = localWidth
            var top = localHeight
            var right = 0
            var bottom = 0
            var pixelCount = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % localWidth
                val y = current / localWidth
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
                pixelCount++

                val minY = maxOf(0, y - 1)
                val maxY = minOf(localHeight - 1, y + 1)
                val minX = maxOf(0, x - 1)
                val maxX = minOf(localWidth - 1, x + 1)
                for (nextY in minY..maxY) {
                    val nextRow = nextY * localWidth
                    for (nextX in minX..maxX) {
                        if (nextX == x && nextY == y) continue
                        val next = nextRow + nextX
                        if (active[next]) {
                            active[next] = false
                            queue[tail++] = next
                        }
                    }
                }
            }

            if (pixelCount >= MIN_COMPONENT_PIXELS) {
                components += Component(
                    bounds = Bounds(
                        left = scan.left + left,
                        top = scan.top + top,
                        right = scan.left + right,
                        bottom = scan.top + bottom
                    ),
                    pixelCount = pixelCount
                )
            }
        }
        return components
    }

    private fun detectPauseCandidates(
        components: List<Component>,
        referenceHeight: Int
    ): List<Candidate> {
        val maximumDotSize = max(
            DOT_MIN_SIZE,
            (referenceHeight * DOT_MAX_SIZE_RATIO).roundToInt()
        )
        val dots = components.filter { component ->
            val width = component.bounds.width
            val height = component.bounds.height
            val fillRatio = component.pixelCount.toFloat() / (width * height).coerceAtLeast(1)
            width in DOT_MIN_SIZE..maximumDotSize &&
                height in DOT_MIN_SIZE..maximumDotSize &&
                width <= height * DOT_MAX_ASPECT_RATIO &&
                height <= width * DOT_MAX_ASPECT_RATIO &&
                fillRatio >= DOT_MIN_FILL_RATIO
        }
        if (dots.size < MIN_PAUSE_DOTS) return emptyList()

        val maximumRowDifference = referenceHeight * DOT_MAX_ROW_DIFFERENCE_RATIO
        val minimumGap = referenceHeight * DOT_MIN_GAP_RATIO
        val maximumGap = referenceHeight * DOT_MAX_GAP_RATIO
        val candidates = mutableListOf<Candidate>()

        dots.sortedBy { it.bounds.left }.forEach { seed ->
            val row = dots
                .filter { abs(it.bounds.centerY - seed.bounds.centerY) <= maximumRowDifference }
                .sortedBy { it.bounds.left }
            var run = mutableListOf<Component>()

            fun finishRun() {
                if (run.size < MIN_PAUSE_DOTS) {
                    run = mutableListOf()
                    return
                }
                val gaps = run.zipWithNext { left, right ->
                    right.bounds.centerX - left.bounds.centerX
                }
                val smallestGap = gaps.minOrNull() ?: 0f
                val largestGap = gaps.maxOrNull() ?: Float.MAX_VALUE
                if (smallestGap < minimumGap ||
                    largestGap > maximumGap ||
                    largestGap > smallestGap * DOT_MAX_GAP_VARIATION_RATIO
                ) {
                    run = mutableListOf()
                    return
                }
                val bounds = run.map(Component::bounds).reduce(Bounds::union)
                if (bounds.width < referenceHeight * DOT_MIN_SPAN_RATIO) {
                    run = mutableListOf()
                    return
                }
                val ellipsisCount = max(2, (run.size / DOTS_PER_ELLIPSIS.toFloat()).roundToInt())
                candidates += Candidate(
                    kind = Kind.PAUSE,
                    text = ELLIPSIS.repeat(ellipsisCount),
                    bounds = bounds
                )
                run = mutableListOf()
            }

            row.forEach { dot ->
                if (run.isEmpty()) {
                    run += dot
                    return@forEach
                }
                val gap = dot.bounds.centerX - run.last().bounds.centerX
                if (gap in minimumGap..maximumGap) {
                    run += dot
                } else {
                    finishRun()
                    run += dot
                }
            }
            finishRun()
        }

        return candidates
            .sortedByDescending { it.bounds.width }
            .fold(mutableListOf()) { accepted, candidate ->
                if (accepted.none { it.bounds.overlapRatio(candidate.bounds) >= DUPLICATE_OVERLAP_RATIO }) {
                    accepted += candidate
                }
                accepted
            }
    }

    private fun mainTextLines(lines: List<Line>): List<Line> {
        val readable = lines.filterNot { it.text.isPauseOrDashOnly() }
            .filter { line -> line.text.any { it.isLetterOrDigit() || it.isJapaneseOrCjk() } }
        val anchor = readable.maxWithOrNull(
            compareBy<Line> { it.bounds.height }
                .thenBy { it.bounds.width }
                .thenBy { it.bounds.centerY }
        ) ?: return emptyList()
        val minimumHeight = anchor.bounds.height * MAIN_LINE_MIN_HEIGHT_RATIO
        val maximumCenterDifference = anchor.bounds.height * MAIN_LINE_MAX_CENTER_DIFFERENCE_RATIO
        return readable.filter { line ->
            line.bounds.height >= minimumHeight &&
                abs(line.bounds.centerY - anchor.bounds.centerY) <= maximumCenterDifference
        }
    }

    private fun bestTargetLine(
        candidate: Candidate,
        lines: List<Line>,
        referenceHeight: Int
    ): Line? {
        val maximumGap = referenceHeight * MAX_CANDIDATE_LINE_GAP_RATIO
        return lines
            .mapNotNull { line ->
                val horizontalGap = when {
                    candidate.bounds.right < line.bounds.left -> line.bounds.left - candidate.bounds.right
                    candidate.bounds.left > line.bounds.right -> candidate.bounds.left - line.bounds.right
                    else -> 0
                }
                if (horizontalGap > maximumGap) return@mapNotNull null
                val verticalDifference = abs(candidate.bounds.centerY - line.bounds.centerY)
                line to horizontalGap + verticalDifference * VERTICAL_TARGET_PENALTY
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun mergeCandidateText(
        line: Line,
        candidate: Candidate,
        referenceHeight: Int
    ): String? {
        val text = line.text
        if (text.isBlank()) return candidate.text
        val edgeTolerance = referenceHeight * EDGE_ATTACHMENT_TOLERANCE_RATIO
        val insertionIndex = when {
            candidate.bounds.centerX <= line.bounds.left + edgeTolerance -> 0
            candidate.bounds.centerX >= line.bounds.right - edgeTolerance -> text.length
            else -> {
                val estimatedCellCount = text.length + candidate.text.length
                val relativeCenter = (
                    (candidate.bounds.centerX - line.bounds.left) /
                        line.bounds.width.coerceAtLeast(1).toFloat()
                    ).coerceIn(0f, 1f)
                (relativeCenter * estimatedCellCount - candidate.text.length / 2f)
                    .roundToInt()
                    .coerceIn(0, text.length)
            }
        }

        val existingRun = punctuationRuns(text, candidate.kind)
            .minByOrNull { run ->
                val runCenter = (run.first + run.last + 1) / 2f
                abs(runCenter - insertionIndex)
            }
            ?.takeIf { run ->
                val characterWidth = line.bounds.width.toFloat() / text.length.coerceAtLeast(1)
                val runCenterX = line.bounds.left +
                    ((run.first + run.last + 1) / 2f / text.length.coerceAtLeast(1)) * line.bounds.width
                abs(runCenterX - candidate.bounds.centerX) <= max(
                    referenceHeight * EXISTING_RUN_MAX_DISTANCE_RATIO,
                    characterWidth * EXISTING_RUN_MAX_CHARACTER_DISTANCE
                )
            }
        if (existingRun != null) {
            val current = text.substring(existingRun)
            return if (current.visualPunctuationLength(candidate.kind) >=
                candidate.text.visualPunctuationLength(candidate.kind)
            ) {
                text
            } else {
                text.replaceRange(existingRun, candidate.text)
            }
        }

        if (insertionIndex in 1 until text.length &&
            text[insertionIndex - 1].isAsciiLetterOrDigit() &&
            text[insertionIndex].isAsciiLetterOrDigit()
        ) {
            return null
        }
        return text.substring(0, insertionIndex) + candidate.text + text.substring(insertionIndex)
    }

    private fun punctuationRuns(text: String, kind: Kind): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (index in 0..text.length) {
            val matches = index < text.length && when (kind) {
                Kind.PAUSE -> text[index] in PAUSE_SYMBOLS
                Kind.DASH -> text[index] in DASH_SYMBOLS
            }
            if (matches && start < 0) start = index
            if (!matches && start >= 0) {
                val range = start until index
                val value = text.substring(range)
                val valid = when (kind) {
                    Kind.PAUSE -> value.visualPunctuationLength(kind) >= MIN_EXISTING_PAUSE_DOTS
                    Kind.DASH -> value.length >= MIN_EXISTING_DASHES
                }
                if (valid) runs += range
                start = -1
            }
        }
        return runs
    }

    private fun String.visualPunctuationLength(kind: Kind): Int = when (kind) {
        Kind.PAUSE -> fold(0) { total, symbol -> total +
            when (symbol) {
                '…', '⋯' -> 3
                '‥' -> 2
                else -> 1
            }
        }
        Kind.DASH -> length
    }

    private fun String.isPauseOrDashOnly(): Boolean {
        val visible = trim().filterNot(Char::isWhitespace)
        return visible.isNotEmpty() && visible.all { it in PAUSE_SYMBOLS || it in DASH_SYMBOLS }
    }

    private fun Char.isJapaneseOrCjk(): Boolean =
        this in '\u3040'..'\u30ff' ||
            this in '\u31f0'..'\u31ff' ||
            this in '\u3400'..'\u9fff' ||
            this in '\uf900'..'\ufaff' ||
            this in '\uff66'..'\uff9d'

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private fun Int.isChoiceTextPixel(): Boolean {
        val red = (this shr 16) and 0xff
        val green = (this shr 8) and 0xff
        val blue = this and 0xff
        val brightest = maxOf(red, green, blue)
        val darkest = minOf(red, green, blue)
        val luminance = (red * 77 + green * 150 + blue * 29) shr 8
        return luminance >= MIN_TEXT_LUMA &&
            darkest >= MIN_TEXT_CHANNEL &&
            brightest - darkest <= MAX_TEXT_CHROMA
    }

    private fun Bounds.clipped(width: Int, height: Int): Bounds? {
        val clipped = Bounds(
            left = left.coerceIn(0, width),
            top = top.coerceIn(0, height),
            right = right.coerceIn(0, width),
            bottom = bottom.coerceIn(0, height)
        )
        return clipped.takeIf { it.width > 0 && it.height > 0 }
    }

    private fun Bounds.contains(other: Bounds): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    private fun Bounds.isNear(other: Bounds, referenceHeight: Int): Boolean {
        val verticalDifference = abs(centerY - other.centerY)
        val horizontalGap = when {
            right < other.left -> other.left - right
            other.right < left -> left - other.right
            else -> 0
        }
        return verticalDifference <= referenceHeight * FRAGMENT_MAX_CENTER_DIFFERENCE_RATIO &&
            horizontalGap <= referenceHeight * FRAGMENT_MAX_GAP_RATIO
    }

    private fun Bounds.overlapRatio(other: Bounds): Float {
        val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        val overlapArea = overlapWidth * overlapHeight
        val minimumArea = minOf(width * height, other.width * other.height).coerceAtLeast(1)
        return overlapArea.toFloat() / minimumArea
    }

    private fun lineBelongsToButton(line: Bounds, button: Bounds): Boolean {
        if (line.centerX in button.left.toFloat()..button.right.toFloat() &&
            line.centerY in button.top.toFloat()..button.bottom.toFloat()
        ) {
            return true
        }
        val overlapWidth = (minOf(line.right, button.right) - maxOf(line.left, button.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(line.bottom, button.bottom) - maxOf(line.top, button.top)).coerceAtLeast(0)
        val overlapArea = overlapWidth * overlapHeight
        return overlapArea >= line.width.coerceAtLeast(1) * line.height.coerceAtLeast(1) *
            MIN_LINE_BUTTON_OVERLAP_RATIO
    }

    private fun MutableList<Line>.replaceLine(sourceIndex: Int, replacement: Line) {
        val index = indexOfFirst { it.sourceIndex == sourceIndex }
        if (index >= 0) this[index] = replacement
    }

    private fun MutableList<Line>.removeLine(sourceIndex: Int) {
        removeAll { it.sourceIndex == sourceIndex }
    }

    private val PAUSE_SYMBOLS = setOf('.', '．', '·', '・', '･', '•', '…', '‥', '⋯')
    private val DASH_SYMBOLS = setOf('—', '―', '─', '━', '－', 'ー', '一', '-', '_')
    private const val ELLIPSIS = "…"
    private const val DASH_TEXT = "───"
    private const val DEFAULT_TEXT_HEIGHT_RATIO = 0.40f
    private const val RECOVERED_CONFIDENCE = 0.55f
    private const val MIN_SCAN_INSET = 2
    private const val HORIZONTAL_SCAN_INSET_RATIO = 0.015f
    private const val VERTICAL_SCAN_INSET_RATIO = 0.08f
    private const val MIN_TEXT_LUMA = 160
    private const val MIN_TEXT_CHANNEL = 115
    private const val MAX_TEXT_CHROMA = 90
    private const val MIN_COMPONENT_PIXELS = 4
    private const val MAIN_LINE_MIN_HEIGHT_RATIO = 0.66f
    private const val MAIN_LINE_MAX_CENTER_DIFFERENCE_RATIO = 0.45f
    private const val MAX_MAIN_CENTER_DIFFERENCE_RATIO = 0.32f
    private const val MAX_STANDALONE_CENTER_DIFFERENCE_RATIO = 0.55f
    private const val DOT_MIN_SIZE = 2
    private const val DOT_MAX_SIZE_RATIO = 0.24f
    private const val DOT_MAX_ASPECT_RATIO = 2f
    private const val DOT_MIN_FILL_RATIO = 0.32f
    private const val DOT_MAX_ROW_DIFFERENCE_RATIO = 0.13f
    private const val DOT_MIN_GAP_RATIO = 0.08f
    private const val DOT_MAX_GAP_RATIO = 0.55f
    private const val DOT_MAX_GAP_VARIATION_RATIO = 2.2f
    private const val DOT_MIN_SPAN_RATIO = 0.70f
    private const val MIN_PAUSE_DOTS = 5
    private const val DOTS_PER_ELLIPSIS = 3
    private const val DASH_MIN_WIDTH_RATIO = 1.65f
    private const val DASH_MAX_WIDTH_RATIO = 6f
    private const val DASH_MAX_HEIGHT_RATIO = 0.25f
    private const val DASH_MIN_MAX_HEIGHT = 3
    private const val DASH_MIN_ASPECT_RATIO = 5f
    private const val DASH_MIN_FILL_RATIO = 0.32f
    private const val DUPLICATE_OVERLAP_RATIO = 0.55f
    private const val MAX_CANDIDATE_LINE_GAP_RATIO = 2.25f
    private const val VERTICAL_TARGET_PENALTY = 0.5f
    private const val EDGE_ATTACHMENT_TOLERANCE_RATIO = 0.35f
    private const val EXISTING_RUN_MAX_DISTANCE_RATIO = 1.20f
    private const val EXISTING_RUN_MAX_CHARACTER_DISTANCE = 2f
    private const val MIN_EXISTING_PAUSE_DOTS = 2
    private const val MIN_EXISTING_DASHES = 2
    private const val FRAGMENT_MAX_CENTER_DIFFERENCE_RATIO = 0.45f
    private const val FRAGMENT_MAX_GAP_RATIO = 0.75f
    private const val MIN_LINE_BUTTON_OVERLAP_RATIO = 0.45f
}
