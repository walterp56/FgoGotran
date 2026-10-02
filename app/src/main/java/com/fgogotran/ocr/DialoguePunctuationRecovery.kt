package com.fgogotran.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dialogue-only punctuation recovery inside the exact dialogue crop.
 *
 * Shares the annotation cleaner's components, not a second pixel mask. Main-band dots/dashes can
 * form their own row without any recognised words. Internal insertion uses CTC token positions,
 * never an estimate based on the number of characters in a line. Uncertain insertions are reported
 * to the caller for its existing bounded recognition budget.
 */
internal object DialoguePunctuationRecovery {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        fun union(other: Bounds) = Bounds(
            minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom)
        )
    }

    data class Token(val text: String, val centerX: Float)
    data class Line(
        val text: String,
        val bounds: Bounds,
        val confidence: Float,
        val tokens: List<Token> = emptyList()
    )
    data class Result(
        val lines: List<Line>,
        val recoveredCount: Int,
        val unresolvedBounds: Set<Bounds>
    )

    private enum class Kind { PAUSE, DASH }
    private data class Candidate(
        val kind: Kind, val text: String, val bounds: Bounds,
        // 。 sits lower than the dots; it must not move the logical row baseline.
        val baselineY: Float = bounds.centerY
    )

    /** Keep existing words/punctuation authoritative when a bounded retry adds a pause token. */
    fun tokensForText(text: String, tokens: List<Token>): List<Token> {
        var cursor = 0
        val retained = mutableListOf<Token>()
        tokens.forEach { token ->
            when {
                token.text.isNotEmpty() && text.startsWith(token.text, cursor) -> {
                    retained += token
                    cursor += token.text.length
                }
                token.text.isNotEmpty() && token.text.all { isKind(it, Kind.PAUSE) || isKind(it, Kind.DASH) } -> Unit
                else -> return emptyList()
            }
        }
        return if (cursor == text.length) retained else emptyList()
    }

    fun recover(
        pixels: IntArray,
        width: Int,
        height: Int,
        components: List<DialogueAnnotationCleaner.Component>,
        lines: List<Line>
    ): Result {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Result(lines, 0, emptySet())
        }
        val defaultHeight = (height * REFERENCE_HEIGHT_RATIO).roundToInt().coerceAtLeast(8)
        val referenceHeight = lines.map { it.bounds.height }
            .filter { it in (defaultHeight * 0.65f).roundToInt()..(defaultHeight * 1.6f).roundToInt() }
            .sorted().let { it.getOrNull(it.size / 2) } ?: defaultHeight
        val candidates = detectCandidates(pixels, width, height, components, referenceHeight)
        if (candidates.isEmpty()) return Result(lines, 0, emptySet())

        val output = lines.toMutableList()
        val unresolved = mutableSetOf<Int>()
        var recoveredCount = 0
        val consumed = mutableSetOf<Int>()
        val standalone = mutableListOf<Candidate>()

        candidates.sortedWith(compareBy({ it.baselineY }, { it.bounds.left })).forEach { candidate ->
            val targetIndex = output.indices.filter { index ->
                index !in consumed && (output[index].text.any(Char::isLetterOrDigit) ||
                    (isPunctuationOnly(output[index].text) && output[index].tokens.isNotEmpty())) &&
                    output[index].bounds.height >= referenceHeight * 0.6f &&
                    abs(output[index].bounds.centerY - candidate.baselineY) <= referenceHeight * 0.4f &&
                    horizontalGap(output[index].bounds, candidate.bounds) <= referenceHeight * 2.5f
            }.minByOrNull { index -> horizontalGap(output[index].bounds, candidate.bounds) }

            if (targetIndex == null) {
                standalone += candidate
                return@forEach
            }
            val original = output[targetIndex]
            val merged = merge(original, candidate, referenceHeight)
            if (merged == null) {
                unresolved += targetIndex
                return@forEach
            }
            output[targetIndex] = merged
            if (merged.text != original.text) recoveredCount++
            // Consume only a matching detached pause/dash fragment, never quotes, words or ruby.
            output.indices.filter { index ->
                index != targetIndex && index !in consumed &&
                    isMatchingFragment(output[index], candidate, referenceHeight)
            }.forEach(consumed::add)
        }

        val standaloneRows = mutableListOf<MutableList<Candidate>>()
        standalone.forEach { candidate ->
            val row = standaloneRows.lastOrNull()?.takeIf {
                abs(it.first().baselineY - candidate.baselineY) <= referenceHeight * 0.2f
            }
            if (row == null) standaloneRows += mutableListOf(candidate) else row += candidate
        }
        standaloneRows.forEach { row ->
            val inkBounds = row.map(Candidate::bounds).reduce(Bounds::union)
            val baselineY = row.map(Candidate::baselineY).average().toFloat()
            val matchingIndexes = output.indices.filter { index ->
                index !in consumed && isPunctuationOnly(output[index].text) &&
                    output[index].text.any { isKind(it, Kind.PAUSE) || isKind(it, Kind.DASH) } &&
                    abs(output[index].bounds.centerY - baselineY) <= referenceHeight * 0.4f &&
                    horizontalGap(output[index].bounds, inkBounds) <= referenceHeight * 0.5f
            }
            val existing = matchingIndexes.map { output[it] }
            // Keep already recognised terminal quotes/marks attached to a punctuation row.
            val text = row.sortedBy { it.bounds.left }.joinToString("") { it.text }
            val existingText = existing.sortedBy { it.bounds.left }.joinToString("") { it.text }
            val finalText = completeStandaloneText(existingText, text)
            matchingIndexes.forEach(consumed::add)
            // Retained quotes/marks also retain their OCR bounds so the overlay covers them.
            val retainedBounds = existing.fold(inkBounds) { bounds, line -> bounds.union(line.bounds) }
            val top = minOf(retainedBounds.top, (baselineY - referenceHeight / 2f).roundToInt()).coerceAtLeast(0)
            val bottom = maxOf(retainedBounds.bottom, (baselineY + referenceHeight / 2f).roundToInt()).coerceAtMost(height)
            output += Line(finalText, Bounds(retainedBounds.left, top, retainedBounds.right, bottom), 0.55f)
            if (finalText != existingText) recoveredCount++
        }

        // Same-row fragments are one logical dialogue row, not additional source newlines. Ruby
        // is smaller/above and excluded from this join. Overlapping word boxes stay untouched.
        val retained = output.filterIndexed { index, _ -> index !in consumed }
        return Result(joinMainRowFragments(retained, referenceHeight), recoveredCount,
            unresolved.filter { it !in consumed }.mapTo(mutableSetOf()) { output[it].bounds })
    }

    private fun detectCandidates(
        pixels: IntArray,
        width: Int,
        height: Int,
        components: List<DialogueAnnotationCleaner.Component>,
        referenceHeight: Int
    ): List<Candidate> {
        val maximumDotSide = max(3, (referenceHeight * 0.26f).roundToInt())
        val dots = components.filter {
            it.width in 2..maximumDotSide && it.height in 2..maximumDotSide &&
                max(it.width, it.height) <= minOf(it.width, it.height) * 1.8f && it.fillRatio >= 0.45f &&
                it.hasInkAtCenter(pixels, width)
        }
        val candidates = mutableListOf<Candidate>()
        val usedDots = mutableSetOf<DialogueAnnotationCleaner.Component>()
        dots.sortedWith(compareBy({ it.centerY }, { it.centerX })).forEach { seed ->
            if (seed in usedDots) return@forEach
            val row = dots.filter { abs(it.centerY - seed.centerY) <= referenceHeight * 0.12f }
                .sortedBy { it.centerX }
            var run = mutableListOf<DialogueAnnotationCleaner.Component>()
            fun finishRun() {
                if (run.size >= 3 && run.size % 3 == 0) {
                    val gaps = run.zipWithNext { a, b -> b.centerX - a.centerX }
                    val sides = run.map { max(it.width, it.height) }
                    if (gaps.maxOrNull()!! <= gaps.minOrNull()!! * 1.5f &&
                        sides.maxOrNull()!! <= sides.minOrNull()!! * 1.6f &&
                        run.last().right - run.first().left >= referenceHeight * 0.4f
                    ) {
                        var bounds = run.map { it.bounds() }.reduce(Bounds::union)
                        val baselineY = bounds.centerY
                        var text = "…".repeat(run.size / 3)
                        // A trailing 。 needs a hollow round shape and the lower punctuation
                        // baseline. A filled dot or a random small glyph does not qualify.
                        components.firstOrNull { component ->
                            component.left >= bounds.right && component.left - bounds.right <= referenceHeight * 0.6f &&
                                component.centerY - bounds.centerY in referenceHeight * 0.08f..referenceHeight * 0.45f &&
                                component.isPeriod(pixels, width, height, referenceHeight)
                        }?.let { period -> bounds = bounds.union(period.bounds()); text += "。" }
                        if (!isUpperAnnotation(bounds, components, referenceHeight)) {
                            candidates += Candidate(Kind.PAUSE, text, bounds, baselineY)
                            usedDots += run
                        }
                    }
                }
                run = mutableListOf()
            }
            row.forEach { dot ->
                if (run.isNotEmpty() && dot.centerX - run.last().centerX !in
                    referenceHeight * 0.08f..referenceHeight * 0.55f
                ) finishRun()
                run += dot
            }
            finishRun()
        }

        components.filter { component ->
            component.width >= referenceHeight * 1.65f &&
                component.height in 1..max(3, (referenceHeight * 0.22f).roundToInt()) &&
                component.width >= component.height * 6 && component.fillRatio >= 0.55f
        }.forEach { dash ->
            val bounds = dash.bounds()
            if (!isUpperAnnotation(bounds, components, referenceHeight)) {
                candidates += Candidate(Kind.DASH, "───", bounds)
            }
        }
        return candidates.distinctBy { it.kind to it.bounds }
    }

    private fun isUpperAnnotation(
        bounds: Bounds,
        components: List<DialogueAnnotationCleaner.Component>,
        referenceHeight: Int
    ): Boolean = components.any { main ->
        main.height >= referenceHeight * 0.65f &&
            (main.top - bounds.bottom).toFloat() in 0f..referenceHeight * 0.5f &&
            main.left < bounds.right && main.right > bounds.left
    }

    private fun DialogueAnnotationCleaner.Component.isPeriod(
        pixels: IntArray, width: Int, height: Int, referenceHeight: Int
    ): Boolean {
        if (left < 0 || top < 0 || right > width || bottom > height ||
            this.width !in 4..max(4, (referenceHeight * 0.4f).roundToInt()) ||
            this.height !in 4..max(4, (referenceHeight * 0.4f).roundToInt()) ||
            max(this.width, this.height) > minOf(this.width, this.height) * 1.4f ||
            fillRatio !in 0.2f..0.8f
        ) return false
        val cx = centerX.toInt().coerceIn(left, right - 1)
        val cy = centerY.toInt().coerceIn(top, bottom - 1)
        fun ink(x: Int, y: Int): Boolean {
            val pixel = pixels[y * width + x]
            return FgoStoryTextPalette.isDialogueInk(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255)
        }
        return !ink(cx, cy) && ink(cx, top) && ink(cx, bottom - 1) && ink(left, cy) && ink(right - 1, cy)
    }

    private fun merge(line: Line, candidate: Candidate, referenceHeight: Int): Line? {
        val tokens = line.tokens.takeIf { it.joinToString("") { token -> token.text } == line.text }
        if (tokens != null && tokens.isNotEmpty()) {
            val matching = tokens.indices.filter { index ->
                tokens[index].text.any { isKind(it, candidate.kind) } &&
                    tokens[index].centerX in
                    (candidate.bounds.left - referenceHeight * 0.25f)..(candidate.bounds.right + referenceHeight * 0.25f)
            }
            if (matching.isNotEmpty()) {
                val first = matching.first()
                var last = matching.last()
                if (candidate.text.endsWith('。') && last + 1 < tokens.size &&
                    tokens[last + 1].text == "。" && tokens[last + 1].centerX <= candidate.bounds.right + referenceHeight * 0.25f
                ) last++
                val existing = tokens.subList(first, last + 1).joinToString("") { it.text }
                if (existing.all { isKind(it, candidate.kind) || it == '。' } &&
                    punctuationWeight(existing) < punctuationWeight(candidate.text)
                ) {
                    val replacement = candidate.text + if (existing.endsWith('。') && !candidate.text.endsWith('。')) "。" else ""
                    val updated = tokens.take(first) + Token(replacement, candidate.bounds.centerX) + tokens.drop(last + 1)
                    return line.copy(text = updated.joinToString("") { it.text }, tokens = updated,
                        bounds = line.bounds.union(candidate.bounds))
                }
                return line.copy(bounds = line.bounds.union(candidate.bounds))
            }
            // A word token within the visual run makes its insertion position uncertain. Keep the
            // original and let the bounded retry see it; never split a word by uniform-cell guesses.
            if (tokens.any { it.text.any(Char::isLetterOrDigit) &&
                    it.centerX in candidate.bounds.left.toFloat()..candidate.bounds.right.toFloat() }) return null
            val before = tokens.takeWhile { it.centerX < candidate.bounds.centerX }
            val inserted = before + Token(candidate.text, candidate.bounds.centerX) + tokens.drop(before.size)
            return line.copy(text = inserted.joinToString("") { it.text }, tokens = inserted,
                bounds = line.bounds.union(candidate.bounds))
        }
        val tolerance = referenceHeight * 0.25f
        val leading = candidate.bounds.right <= line.bounds.left + tolerance
        val trailing = candidate.bounds.left >= line.bounds.right - tolerance
        if (!leading && !trailing) return null
        val existing = if (leading) line.text.takeWhile { isKind(it, candidate.kind) }
            else line.text.takeLastWhile { isKind(it, candidate.kind) || it == '。' }
        val text = if (existing.isNotEmpty()) line.text else if (leading) candidate.text + line.text else line.text + candidate.text
        return line.copy(text = text, bounds = line.bounds.union(candidate.bounds), tokens = emptyList())
    }

    private fun isMatchingFragment(line: Line, candidate: Candidate, referenceHeight: Int): Boolean =
        isPauseOrDashOnly(line.text) && line.text.any { isKind(it, candidate.kind) } &&
            abs(line.bounds.centerY - candidate.baselineY) <= referenceHeight * 0.4f &&
            horizontalGap(line.bounds, candidate.bounds) <= referenceHeight * 0.3f

    private fun joinMainRowFragments(lines: List<Line>, referenceHeight: Int): List<Line> {
        val result = mutableListOf<Line>()
        lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).forEach { line ->
            val index = result.indexOfLast { previous ->
                previous.bounds.height >= referenceHeight * 0.6f && line.bounds.height >= referenceHeight * 0.6f &&
                    abs(previous.bounds.centerY - line.bounds.centerY) <= referenceHeight * 0.3f &&
                    previous.bounds.right <= line.bounds.left &&
                    line.bounds.left - previous.bounds.right <= referenceHeight * 3f
            }
            if (index < 0) result += line else {
                val previous = result[index]
                val tokens = if (previous.tokens.joinToString("") { it.text } == previous.text &&
                    line.tokens.joinToString("") { it.text } == line.text) previous.tokens + line.tokens else emptyList()
                result[index] = Line(previous.text + line.text, previous.bounds.union(line.bounds),
                    minOf(previous.confidence, line.confidence), tokens)
            }
        }
        return result.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun isPauseOrDashOnly(text: String): Boolean = text.isNotBlank() && text.all {
        it.isWhitespace() || isKind(it, Kind.PAUSE) || isKind(it, Kind.DASH) || it == '。'
    }
    private fun isPunctuationOnly(text: String): Boolean = text.isNotBlank() && text.all {
        it.isWhitespace() || isKind(it, Kind.PAUSE) || isKind(it, Kind.DASH) || it in "。、，,!！?？「」『』“”\"'（）()[]【】"
    }
    private fun completeStandaloneText(existing: String, candidate: String): String {
        if (existing.isBlank()) return candidate
        // Upgrade like-for-like pause/dash runs only. Existing quotes/other marks never disappear.
        fun runs(text: String): List<Pair<Kind, IntRange>> {
            val output = mutableListOf<Pair<Kind, IntRange>>()
            var index = 0
            while (index < text.length) {
                val kind = Kind.entries.firstOrNull { isKind(text[index], it) }
                if (kind == null) { index++; continue }
                val start = index++
                while (index < text.length && isKind(text[index], kind)) index++
                output += kind to (start until index)
            }
            return output
        }
        val currentRuns = runs(existing)
        val proposedRuns = runs(candidate)
        if (currentRuns.map { it.first } != proposedRuns.map { it.first }) return existing
        var result = existing
        currentRuns.indices.reversed().forEach { index ->
            val current = currentRuns[index].second
            val proposed = candidate.substring(proposedRuns[index].second)
            if (punctuationWeight(result.substring(current)) < punctuationWeight(proposed)) {
                result = result.replaceRange(current, proposed)
            }
        }
        if (candidate.endsWith('。') && '。' !in result) {
            val last = runs(result).lastOrNull()?.second?.last ?: return result
            result = result.substring(0, last + 1) + "。" + result.substring(last + 1)
        }
        return result
    }
    private fun isKind(char: Char, kind: Kind): Boolean = when (kind) {
        Kind.PAUSE -> char in "…⋯‥.．·・･•"
        Kind.DASH -> char in "─━―—－-"
    }
    private fun punctuationWeight(text: String): Int = text.fold(0) { total, char ->
        total + if (char == '…' || char == '⋯') 3 else 1
    }
    private fun DialogueAnnotationCleaner.Component.hasInkAtCenter(pixels: IntArray, width: Int): Boolean {
        val pixel = pixels[centerY.toInt() * width + centerX.toInt()]
        return FgoStoryTextPalette.isDialogueInk(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255)
    }
    private fun horizontalGap(a: Bounds, b: Bounds): Int = maxOf(0, a.left - b.right, b.left - a.right)
    private fun DialogueAnnotationCleaner.Component.bounds() = Bounds(left, top, right, bottom)
    private const val REFERENCE_HEIGHT_RATIO = 0.22f
}
