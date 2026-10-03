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
        val confidence: Float,
        val tokens: List<DialoguePunctuationRecovery.Token> = emptyList()
    )

    data class Result(
        val lines: List<Line>,
        val recoveredCount: Int,
        val unresolvedSourceIndices: Set<Int> = emptySet()
    )

    private enum class Kind {
        PAUSE,
        DASH
    }

    data class Component(
        val bounds: Bounds,
        val pixelCount: Int
    )

    data class ButtonAnalysis(
        val bounds: Bounds,
        val interior: Bounds,
        val components: List<Component>,
        val mainBand: Bounds?
    )

    /** One white/red component scan per button, shared by ruby and punctuation recovery. */
    fun analyze(pixels: IntArray, width: Int, height: Int, buttons: List<Bounds>): List<ButtonAnalysis> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        return buttons.mapNotNull { raw ->
            val button = raw.clipped(width, height) ?: return@mapNotNull null
            val insetX = max(MIN_SCAN_INSET, (button.width * HORIZONTAL_SCAN_INSET_RATIO).roundToInt())
            val insetY = max(MIN_SCAN_INSET, (button.height * VERTICAL_SCAN_INSET_RATIO).roundToInt())
            val interior = Bounds(button.left + insetX, button.top + insetY,
                button.right - insetX, button.bottom - insetY).clipped(width, height) ?: return@mapNotNull null
            val components = brightNeutralComponents(pixels, width, interior)
            // Use full-size ink, not the number of small ruby fragments, to locate the lower row.
            val mainInk = components.filter {
                it.bounds.height >= button.height * 0.22f && it.bounds.height <= button.height * 0.70f
            }
            val anchor = mainInk.maxWithOrNull(compareBy<Component> { it.bounds.height }
                .thenBy { it.pixelCount }.thenBy { it.bounds.centerY })
            val band = anchor?.let {
                mainInk.filter { part -> abs(part.bounds.centerY - it.bounds.centerY) <= it.bounds.height * 0.35f }
                    .map(Component::bounds).reduce(Bounds::union)
            }
            ButtonAnalysis(button, interior, components, band)
        }
    }

    /** Only verified, separated upper dots over full-size glyphs are emphasis, not kana/dakuten. */
    fun emphasisDots(analysis: List<ButtonAnalysis>): Set<Component> = analysis.flatMap { button ->
        val band = button.mainBand ?: return@flatMap emptyList()
        val upper = button.components.filter { it.bounds.centerY < band.top }
        fun isDot(component: Component): Boolean {
            val bounds = component.bounds
            val side = max(bounds.width, bounds.height)
            return side in 3..max(3, (band.height * 0.24f).roundToInt()) &&
                side <= minOf(bounds.width, bounds.height) * 1.55f &&
                component.pixelCount.toFloat() / (bounds.width * bounds.height).coerceAtLeast(1) >= 0.65f
        }
        val dots = upper.filter { dot ->
            isDot(dot) && band.top - dot.bounds.bottom in 2..(band.height * 0.45f).roundToInt() &&
                button.components.any { main -> main.bounds.height >= band.height * 0.65f &&
                    dot.bounds.centerX in main.bounds.left.toFloat()..main.bounds.right.toFloat() &&
                    abs(main.bounds.centerY - band.centerY) <= band.height * 0.35f } &&
                upper.none { part -> part != dot && !isDot(part) &&
                    abs(part.bounds.centerY - dot.bounds.centerY) <= band.height * 0.25f &&
                    maxOf(0, part.bounds.left - dot.bounds.right, dot.bounds.left - part.bounds.right) <=
                    max(dot.bounds.width, dot.bounds.height) * 1.5f }
        }.sortedBy { it.bounds.left }
        val runs = mutableListOf<MutableList<Component>>()
        dots.forEach { dot ->
            val previous = runs.lastOrNull()?.lastOrNull()
            if (previous != null &&
                abs(previous.bounds.centerY - dot.bounds.centerY) <= band.height * 0.12f &&
                dot.bounds.centerX - previous.bounds.centerX in band.height * 0.65f..band.height * 1.8f) {
                runs.last() += dot
            } else runs += mutableListOf(dot)
        }
        runs.filter { run ->
            if (run.size < 2) false else {
                val gaps = run.zipWithNext { a, b -> b.bounds.centerX - a.bounds.centerX }
                gaps.maxOrNull()!! <= gaps.minOrNull()!! * 1.55f
            }
        }.flatten()
    }.toSet()

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
        lines: List<Line>,
        analysis: List<ButtonAnalysis>? = null
    ): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height || buttons.isEmpty()) {
            return Result(lines, 0)
        }

        val output = lines.toMutableList()
        var nextSourceIndex = (lines.maxOfOrNull(Line::sourceIndex) ?: -1) + 1
        var recoveredCount = 0
        val unresolved = mutableSetOf<Int>()

        (analysis ?: analyze(pixels, width, height, buttons)).forEach { buttonAnalysis ->
            val button = buttonAnalysis.bounds
            val rowLines = output.filter { lineBelongsToButton(it.bounds, button) }
            var mainLines = mainTextLines(rowLines, buttonAnalysis.mainBand)
            val orderedMain = mainLines.sortedBy { it.bounds.left }
            if (orderedMain.size > 1 && orderedMain.zipWithNext().all { (a, b) -> a.bounds.right <= b.bounds.left }) {
                val joined = orderedMain.first().copy(
                    text = orderedMain.joinToString("") { it.text },
                    bounds = orderedMain.map(Line::bounds).reduce(Bounds::union),
                    confidence = orderedMain.minOf(Line::confidence),
                    tokens = if (orderedMain.all { it.tokens.isNotEmpty() && it.tokens.joinToString("") { token -> token.text } == it.text }) {
                        orderedMain.flatMap(Line::tokens)
                    } else emptyList()
                )
                orderedMain.forEach { output.removeLine(it.sourceIndex) }
                output += joined
                mainLines = listOf(joined)
            }
            val referenceHeight = mainLines.maxOfOrNull { it.bounds.height }
                ?.coerceAtLeast(1)
                ?: buttonAnalysis.mainBand?.height
                ?: (button.height * DEFAULT_TEXT_HEIGHT_RATIO).roundToInt().coerceAtLeast(1)
            val expectedCenterY = mainLines
                .maxByOrNull { it.bounds.height }
                ?.bounds
                ?.centerY
                ?: buttonAnalysis.mainBand?.centerY
                ?: button.centerY

            val candidates = detectCandidates(
                pixels = pixels,
                sourceWidth = width,
                components = buttonAnalysis.components,
                referenceHeight = referenceHeight,
                expectedCenterY = expectedCenterY,
                hasMainText = mainLines.isNotEmpty()
            )
            if (candidates.isEmpty()) return@forEach

            if (mainLines.isEmpty()) {
                val punctuationLines = rowLines.filter { line ->
                    line.text.isKnownPunctuationOnly() &&
                        abs(line.bounds.centerY - expectedCenterY) <= referenceHeight * FRAGMENT_MAX_CENTER_DIFFERENCE_RATIO
                }
                val standaloneText = candidates
                    .sortedBy { it.bounds.left }
                    .joinToString(separator = "", transform = Candidate::text)
                val standaloneBounds = candidates
                    .map(Candidate::bounds)
                    .reduce(Bounds::union)
                val existing = punctuationLines.minByOrNull { it.bounds.left }
                if (existing != null) {
                    val previousText = punctuationLines.sortedBy { it.bounds.left }.joinToString("") { it.text.trim() }
                    // Normalize OCR's dash confusables only when the complete row is verified
                    // as a dash. Never treat an ordinary kanji/long vowel inside words this way.
                    val verifiedPrevious = if (candidates.all { it.kind == Kind.DASH } &&
                        previousText.all { it.isWhitespace() || it in DASH_SYMBOLS || it == '。' }) {
                        previousText.map { if (it in "一ー_") '─' else it }.joinToString("")
                    } else previousText
                    val recoveredText = DialoguePunctuationRecovery.completeStandaloneRun(verifiedPrevious, standaloneText)
                    val recoveredBounds = punctuationLines.map(Line::bounds).fold(standaloneBounds, Bounds::union)
                    if (existing.text.trim() != recoveredText || existing.bounds != recoveredBounds || punctuationLines.size > 1) {
                        output.replaceLine(
                            existing.sourceIndex,
                            existing.copy(text = recoveredText, bounds = recoveredBounds, tokens = emptyList())
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
                val positionedLine = DialoguePunctuationRecovery.Line(target.text, target.bounds.toDialogueBounds(),
                    target.confidence, target.tokens)
                val merged = mergeAtPixelEdge(positionedLine, candidate, buttonAnalysis.mainBand, referenceHeight)
                    ?: DialoguePunctuationRecovery.mergeVisualRun(
                    positionedLine, candidate.text, candidate.bounds.toDialogueBounds(), referenceHeight
                ) ?: run { unresolved += target.sourceIndex; return@candidateLoop }
                val textChanged = merged.text != target.text
                val boundsChanged = !target.bounds.contains(candidate.bounds)
                if (textChanged || boundsChanged) {
                    output.replaceLine(
                        target.sourceIndex,
                        target.copy(
                            text = merged.text,
                            bounds = target.bounds.union(candidate.bounds),
                            tokens = merged.tokens
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

        return Result(output, recoveredCount, unresolved)
    }

    private fun detectCandidates(
        pixels: IntArray,
        sourceWidth: Int,
        components: List<Component>,
        referenceHeight: Int,
        expectedCenterY: Float,
        hasMainText: Boolean
    ): List<Candidate> {
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

        val pauseCandidates = detectPauseCandidates(aligned, referenceHeight).map { candidate ->
            val period = components.firstOrNull { component ->
                val bounds = component.bounds
                val fill = component.pixelCount.toFloat() / (bounds.width * bounds.height).coerceAtLeast(1)
                bounds.left >= candidate.bounds.right && bounds.left - candidate.bounds.right <= referenceHeight * 0.6f &&
                    bounds.centerY - candidate.bounds.centerY in referenceHeight * 0.08f..referenceHeight * 0.45f &&
                    bounds.width in 3..max(3, (referenceHeight * 0.30f).roundToInt()) &&
                    bounds.height in 3..max(3, (referenceHeight * 0.30f).roundToInt()) &&
                    max(bounds.width, bounds.height) <= minOf(bounds.width, bounds.height) * 1.5f &&
                    fill in 0.15f..0.70f && !pixels[(bounds.centerY.toInt()) * sourceWidth + bounds.centerX.toInt()].isChoiceTextPixel()
            }
            if (period == null) candidate else candidate.copy(text = candidate.text + "。", bounds = candidate.bounds.union(period.bounds))
        }
        val dashCandidates = aligned.mapNotNull { component ->
            val componentWidth = component.bounds.width
            val componentHeight = component.bounds.height
            val minimumWidth = (referenceHeight * DASH_MIN_WIDTH_RATIO).roundToInt()
            val maximumHeight = max(
                DASH_MIN_MAX_HEIGHT,
                (referenceHeight * DASH_MAX_HEIGHT_RATIO).roundToInt()
            )
            val fillRatio = component.pixelCount.toFloat() /
                (componentWidth * componentHeight).coerceAtLeast(1)
            if (componentWidth >= minimumWidth &&
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
                if (run.size != 3 && run.size < 5) {
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
                if (bounds.width < referenceHeight * if (run.size == 3) 0.40f else DOT_MIN_SPAN_RATIO) {
                    run = mutableListOf()
                    return
                }
                val ellipsisCount = max(1, (run.size / DOTS_PER_ELLIPSIS.toFloat()).roundToInt())
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

    private fun mainTextLines(lines: List<Line>, mainBand: Bounds?): List<Line> {
        val readable = lines.filterNot { it.text.isPauseOrDashOnly() }
            .filter { line -> line.text.any { it.isLetterOrDigit() || it.isJapaneseOrCjk() } }
            .filter { line -> mainBand == null || (line.bounds.height >= mainBand.height * 0.60f &&
                abs(line.bounds.centerY - mainBand.centerY) <= mainBand.height * 0.45f) }
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

    private fun String.isPauseOrDashOnly(): Boolean {
        val visible = trim().filterNot(Char::isWhitespace)
        return visible.isNotEmpty() && visible.any { it in PAUSE_SYMBOLS || it in DASH_SYMBOLS } &&
            visible.all { it in PAUSE_SYMBOLS || it in DASH_SYMBOLS || it == '。' }
    }

    private fun String.isKnownPunctuationOnly(): Boolean = isNotBlank() && all {
        it.isWhitespace() || it in PAUSE_SYMBOLS || it in DASH_SYMBOLS || it in "。、，,!！?？「」『』“”\"'（）()[]【】"
    }

    private fun Char.isJapaneseOrCjk(): Boolean =
        this in '\u3040'..'\u30ff' ||
            this in '\u31f0'..'\u31ff' ||
            this in '\u3400'..'\u9fff' ||
            this in '\uf900'..'\ufaff' ||
            this in '\uff66'..'\uff9d'

    private fun Bounds.toDialogueBounds() = DialoguePunctuationRecovery.Bounds(left, top, right, bottom)

    /** Expanded OCR boxes can include a partially read pause. Main ink provides a real edge anchor. */
    private fun mergeAtPixelEdge(
        line: DialoguePunctuationRecovery.Line, candidate: Candidate, band: Bounds?, reference: Int
    ): DialoguePunctuationRecovery.Line? {
        if (line.tokens.isNotEmpty() || band == null) return null
        val leading = candidate.bounds.right <= band.left + reference * 0.25f
        val trailing = candidate.bounds.left >= band.right - reference * 0.25f
        if (!leading && !trailing) return null
        fun matches(char: Char) = if (candidate.kind == Kind.PAUSE) char in PAUSE_SYMBOLS else char in DASH_SYMBOLS
        fun weight(text: String): Int = text.fold(0) { total, char ->
            total + if (char == '…' || char == '⋯') 3 else if (char == '‥') 2 else 1
        }
        val existing = if (leading) line.text.takeWhile(::matches) else line.text.takeLastWhile { matches(it) || it == '。' }
        val replacement = candidate.text + if (existing.endsWith('。') && !candidate.text.endsWith('。')) "。" else ""
        val text = when {
            existing.isNotEmpty() && weight(existing) >= weight(replacement) -> line.text
            leading -> replacement + line.text.drop(existing.length)
            else -> line.text.dropLast(existing.length) + replacement
        }
        return line.copy(text = text, bounds = line.bounds.union(candidate.bounds.toDialogueBounds()))
    }

    private fun Int.isChoiceTextPixel(): Boolean {
        val red = (this shr 16) and 0xff
        val green = (this shr 8) and 0xff
        val blue = this and 0xff
        return FgoStoryTextPalette.isChoiceInk(red, green, blue)
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
    private const val MIN_PAUSE_DOTS = 3
    private const val DOTS_PER_ELLIPSIS = 3
    private const val DASH_MIN_WIDTH_RATIO = 1.65f
    private const val DASH_MAX_HEIGHT_RATIO = 0.25f
    private const val DASH_MIN_MAX_HEIGHT = 3
    private const val DASH_MIN_ASPECT_RATIO = 5f
    private const val DASH_MIN_FILL_RATIO = 0.32f
    private const val DUPLICATE_OVERLAP_RATIO = 0.55f
    private const val MAX_CANDIDATE_LINE_GAP_RATIO = 2.25f
    private const val VERTICAL_TARGET_PENALTY = 0.5f
    private const val FRAGMENT_MAX_CENTER_DIFFERENCE_RATIO = 0.45f
    private const val FRAGMENT_MAX_GAP_RATIO = 0.75f
    private const val MIN_LINE_BUTTON_OVERLAP_RATIO = 0.45f
}
