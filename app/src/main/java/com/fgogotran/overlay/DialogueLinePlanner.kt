package com.fgogotran.overlay

import kotlin.math.abs
import kotlin.math.max

/**
 * Builds render-only line-break candidates for FGO's two-line dialogue box.
 *
 * Translation text is deliberately not modified at the translation/cache boundary. When a
 * translation contains more than two meaningful lines, this planner joins adjacent fragments
 * into two contiguous lines and ranks the possible breaks using the active render font.
 */
internal object DialogueLinePlanner {
    private enum class BreakOrigin {
        SOURCE_LINE,
        SYNTHETIC
    }

    private data class Candidate(
        val left: String,
        val right: String,
        val origin: BreakOrigin,
        val order: Int
    ) {
        val text: String = "$left\n$right"
    }

    private data class ScoredCandidate(
        val candidate: Candidate,
        val fitRank: Int,
        val overflow: Float,
        val qualityPenalty: Float,
        val widestLine: Float
    )

    private val leadingConnectors = setOf(
        "以及", "還有", "还有", "或者", "但是", "因此", "所以", "不過", "不过",
        "然後", "然后", "而且", "並且", "并且", "可是", "只是", "接著", "接着",
        "那麼", "那么", "於是", "于是"
    )

    private val strongBreakCharacters = setOf('。', '！', '？', '!', '?', '…')
    private val mediumBreakCharacters = setOf('；', ';')
    private val weakBreakCharacters = setOf('，', '、', ',', '：', ':')
    private val prohibitedLineStartCharacters = setOf(
        '。', '，', '、', '；', '：', '！', '？', '.', ',', ';', ':', '!', '?',
        '）', ')', '】', ']', '」', '』', '》', '〉'
    )
    private val prohibitedLineEndCharacters = setOf(
        '（', '(', '【', '[', '「', '『', '《', '〈'
    )

    fun plan(
        lines: List<String>,
        maxWidth: Float,
        measureText: (String) -> Float
    ): List<String> {
        val normalizedLines = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (normalizedLines.isEmpty()) return listOf("")
        if (normalizedLines.size <= 2) {
            return listOf(normalizedLines.joinToString("\n"))
        }

        val mergedLines = mergeLeadingFragments(normalizedLines).let { merged ->
            if (merged.size >= 2) {
                merged
            } else {
                listOf(
                    joinFragments(normalizedLines.dropLast(1)),
                    normalizedLines.last()
                )
            }
        }
        if (mergedLines.size <= 2) {
            return listOf(mergedLines.joinToString("\n"))
        }

        val candidates = mutableListOf<Candidate>()
        var order = 0
        for (splitIndex in 1 until mergedLines.size) {
            candidates += Candidate(
                left = joinFragments(mergedLines.take(splitIndex)),
                right = joinFragments(mergedLines.drop(splitIndex)),
                origin = BreakOrigin.SOURCE_LINE,
                order = order++
            )
        }

        val flattened = joinFragments(mergedLines)
        softBreakIndices(flattened).forEach { splitIndex ->
            val left = flattened.substring(0, splitIndex).trimEnd()
            val right = flattened.substring(splitIndex).trimStart()
            if (left.isNotBlank() && right.isNotBlank()) {
                candidates += Candidate(
                    left = left,
                    right = right,
                    origin = BreakOrigin.SYNTHETIC,
                    order = order++
                )
            }
        }

        val safeMaxWidth = maxWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
        return candidates
            .distinctBy { it.text }
            .map { candidate -> score(candidate, safeMaxWidth, measureText) }
            .sortedWith(
                compareBy<ScoredCandidate> { it.fitRank }
                    .thenBy { it.overflow }
                    .thenBy { it.qualityPenalty }
                    .thenBy { it.widestLine }
                    .thenBy { it.candidate.order }
            )
            .map { it.candidate.text }
            .ifEmpty { listOf(flattened) }
    }

    private fun score(
        candidate: Candidate,
        maxWidth: Float,
        measureText: (String) -> Float
    ): ScoredCandidate {
        val leftWidth = measureText(candidate.left).coerceAtLeast(0f)
        val rightWidth = measureText(candidate.right).coerceAtLeast(0f)
        val widestLine = max(leftWidth, rightWidth)
        val overflow = (
            (leftWidth - maxWidth).coerceAtLeast(0f) +
                (rightWidth - maxWidth).coerceAtLeast(0f)
            ) / maxWidth
        val fitRank = if (overflow <= 0.0001f) 0 else 1
        val balancePenalty = abs(leftWidth - rightWidth) / widestLine.coerceAtLeast(1f)
        val semanticPenalty = when (candidate.origin) {
            BreakOrigin.SOURCE_LINE -> when (breakStrength(candidate.left)) {
                3 -> -0.12f
                2 -> -0.06f
                else -> 0f
            }
            BreakOrigin.SYNTHETIC -> when (breakStrength(candidate.left)) {
                3 -> 0.18f
                2 -> 0.30f
                1 -> 0.44f
                else -> 0.68f
            }
        }
        val punctuationPenalty =
            (if (candidate.right.firstOrNull() in prohibitedLineStartCharacters) 1.2f else 0f) +
                (if (candidate.left.lastOrNull() in prohibitedLineEndCharacters) 1.2f else 0f)
        val danglingConnectorPenalty = if (
            leadingConnectors.any { connector -> candidate.left.endsWith(connector) }
        ) {
            0.9f
        } else {
            0f
        }

        return ScoredCandidate(
            candidate = candidate,
            fitRank = fitRank,
            overflow = overflow,
            qualityPenalty = balancePenalty + semanticPenalty +
                punctuationPenalty + danglingConnectorPenalty,
            widestLine = widestLine
        )
    }

    private fun mergeLeadingFragments(lines: List<String>): List<String> {
        val merged = mutableListOf<String>()
        var pendingPrefix = ""
        lines.forEachIndexed { index, line ->
            val hasFollowingLine = index < lines.lastIndex
            if (hasFollowingLine && line.isLeadingFragment()) {
                pendingPrefix = joinFragments(listOf(pendingPrefix, line))
                return@forEachIndexed
            }

            val nextLine = if (pendingPrefix.isNotBlank()) {
                joinFragments(listOf(pendingPrefix, line))
            } else {
                line
            }
            merged += nextLine
            pendingPrefix = ""
        }

        if (pendingPrefix.isNotBlank()) {
            if (merged.isEmpty()) {
                merged += pendingPrefix
            } else {
                merged[merged.lastIndex] = joinFragments(listOf(merged.last(), pendingPrefix))
            }
        }
        return merged
    }

    private fun String.isLeadingFragment(): Boolean {
        if (this in leadingConnectors) return true
        val unwrapped = trim(' ', '\t', '\u3000')
            .trim('「', '」', '『', '』', '（', '）', '(', ')', '[', ']')
        return unwrapped.isNotBlank() && unwrapped.all {
            it in setOf(
                '—', '－', '-', 'ー', '…', '.', '。', '、', ',', '，',
                ':', '：', '!', '！', '?', '？'
            )
        }
    }

    private fun joinFragments(fragments: List<String>): String {
        val result = StringBuilder()
        fragments.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { fragment ->
                if (result.isNotEmpty() && needsAsciiWordSpace(result.last(), fragment.first())) {
                    result.append(' ')
                }
                result.append(fragment)
            }
        return result.toString()
    }

    private fun needsAsciiWordSpace(left: Char, right: Char): Boolean {
        if (!left.isAscii() || !right.isAscii()) return false
        if (right in prohibitedLineStartCharacters || left in prohibitedLineEndCharacters) return false
        return (left.isLetterOrDigit() || left in setOf('_', ',', '.', ';', ':', '!', '?')) &&
            (right.isLetterOrDigit() || right in setOf('_', '(', '[', '"', '\''))
    }

    private fun softBreakIndices(text: String): Sequence<Int> = sequence {
        for (index in 1 until text.length) {
            val left = text[index - 1]
            val right = text[index]
            if (right.isLowSurrogate()) continue
            if (left.isAsciiWordCharacter() && right.isAsciiWordCharacter()) continue
            yield(index)
        }
    }

    private fun breakStrength(left: String): Int {
        return when (left.lastOrNull { !it.isWhitespace() }) {
            in strongBreakCharacters -> 3
            in mediumBreakCharacters -> 2
            in weakBreakCharacters -> 1
            else -> 0
        }
    }

    private fun Char.isAscii(): Boolean = code <= 0x7f

    private fun Char.isAsciiWordCharacter(): Boolean =
        isAscii() && (isLetterOrDigit() || this == '_')
}
