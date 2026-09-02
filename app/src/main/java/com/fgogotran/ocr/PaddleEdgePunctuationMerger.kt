package com.fgogotran.ocr

/**
 * Merges punctuation seen only by a wider PaddleOCR recognition crop.
 *
 * The tight crop remains authoritative for words. A wider result contributes
 * only leading pause/dash/quotation marks or terminal punctuation, and only
 * when both recognitions have exactly the same non-edge text. The katakana
 * prolonged sound mark (`ー`) is deliberately not treated as a dash.
 */
internal object PaddleEdgePunctuationMerger {
    data class NoisyLeadingQuoteCandidate(
        val recoveredText: String,
        val ignoredNoise: Char,
        val openingQuote: Char
    )

    data class PositionedLine(
        val sourceIndex: Int,
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    fun merge(tightText: String, paddedText: String): String {
        val tight = tightText.trim()
        val padded = paddedText.trim()
        if (tight.isBlank() || padded.isBlank() || tight == padded) return tight

        val tightParts = splitEdges(tight)
        val paddedParts = splitEdges(padded)
        if (tightParts.body.isBlank() || !tightParts.body.hasJapaneseOrCjkText()) return tight
        if (comparisonKey(tightParts.body) != comparisonKey(paddedParts.body)) return tight

        val leading = chooseMoreCompleteEdge(
            current = tightParts.leading,
            candidate = paddedParts.leading,
            isTrusted = ::isTrustedLeadingEdge
        )
        val trailing = chooseMoreCompleteEdge(
            current = tightParts.trailing,
            candidate = paddedParts.trailing,
            isTrusted = ::isTrustedTrailingEdge
        )
        if (leading == tightParts.leading && trailing == tightParts.trailing) return tight
        return leading + tightParts.body + trailing
    }

    /**
     * A wide recognition crop can see a real opening quote while also
     * hallucinating one ASCII glyph in the blank padding before it, for
     * example `V“本文`. Keep the tight crop authoritative for words and expose
     * only a pending punctuation candidate. The caller must confirm a matching
     * closing quote before applying it.
     */
    fun findNoisyLeadingQuoteCandidate(
        tightText: String,
        paddedText: String
    ): NoisyLeadingQuoteCandidate? {
        val tight = tightText.trim()
        val padded = paddedText.trim()
        if (tight.isBlank() || padded.length < 3) return null

        val ignoredNoise = padded.first()
        if (!ignoredNoise.isAsciiLetterOrDigit()) return null

        val sanitized = padded.drop(1).trimStart()
        val openingQuote = sanitized.firstOrNull()
            ?.takeIf { it in OPENING_QUOTE_SYMBOLS }
            ?: return null
        val tightParts = splitEdges(tight)
        if (tightParts.leading.any { it in OPENING_QUOTE_SYMBOLS }) return null

        val recovered = merge(tightText = tight, paddedText = sanitized)
        if (recovered == tight) return null
        val recoveredLeading = splitEdges(recovered).leading
        if (openingQuote !in recoveredLeading) return null

        return NoisyLeadingQuoteCandidate(
            recoveredText = recovered,
            ignoredNoise = ignoredNoise,
            openingQuote = openingQuote
        )
    }

    /**
     * Confirm a noisy opening-quote candidate from the candidate line itself
     * or from a later OCR line. A quote inside ordinary text is not evidence;
     * the matching closer must occur at the terminal punctuation edge.
     */
    fun hasMatchingClosingQuote(
        candidate: NoisyLeadingQuoteCandidate,
        laterTexts: List<String>
    ): Boolean {
        val closingQuote = QUOTE_COUNTERPARTS[candidate.openingQuote] ?: return false
        return sequenceOf(candidate.recoveredText)
            .plus(laterTexts.asSequence())
            .any { text -> text.hasClosingQuoteAtTrailingEdge(closingQuote) }
    }

    /**
     * Paddle sometimes detects a thin edge mark as a separate text box. Attach
     * only punctuation-only boxes that are immediately beside a CJK line on
     * the same visual row. The main line keeps its original geometry so region
     * classification cannot be pulled outside its configured FGO target.
     */
    fun mergeDetachedFragments(lines: List<PositionedLine>): List<PositionedLine> {
        if (lines.size < 2) return lines

        val mainLineIndexes = lines.indices.filter { index ->
            val text = lines[index].text.trim()
            text.isNotEmpty() && !text.isDetachedEdgeFragment() &&
                splitEdges(text).body.hasJapaneseOrCjkText()
        }
        val fragmentIndexes = lines.indices.filter { lines[it].text.isDetachedEdgeFragment() }
        if (mainLineIndexes.isEmpty() || fragmentIndexes.isEmpty()) return lines

        val leadingByMain = mutableMapOf<Int, MutableList<Int>>()
        val trailingByMain = mutableMapOf<Int, MutableList<Int>>()
        fragmentIndexes.forEach { fragmentIndex ->
            val fragment = lines[fragmentIndex]
            val fragmentText = fragment.text.trim()
            val attachment = mainLineIndexes.mapNotNull { mainIndex ->
                bestAttachment(
                    fragment = fragment,
                    main = lines[mainIndex],
                    mainIndex = mainIndex,
                    allowLeading = isTrustedLeadingEdge(fragmentText),
                    allowTrailing = isTrustedTrailingEdge(fragmentText)
                )
            }.minByOrNull { it.score } ?: return@forEach

            val target = when (attachment.side) {
                EdgeSide.LEADING -> leadingByMain
                EdgeSide.TRAILING -> trailingByMain
            }
            target.getOrPut(attachment.mainIndex) { mutableListOf() } += fragmentIndex
        }

        val consumedFragments = mutableSetOf<Int>()
        val mergedMainLines = mutableMapOf<Int, PositionedLine>()
        mainLineIndexes.forEach { mainIndex ->
            val leadingIndexes = leadingByMain[mainIndex]
                .orEmpty()
                .sortedBy { lines[it].left }
            val trailingIndexes = trailingByMain[mainIndex]
                .orEmpty()
                .sortedBy { lines[it].left }
            if (leadingIndexes.isEmpty() && trailingIndexes.isEmpty()) return@forEach

            val main = lines[mainIndex]
            val mainEdges = splitEdges(main.text.trim())
            val redundantLeading = leadingIndexes.filter { index ->
                lines[index].text.isQuoteOnlyEdge() &&
                    mainEdges.leading.alreadyContainsQuoteEdge(lines[index].text)
            }
            val redundantTrailing = trailingIndexes.filter { index ->
                lines[index].text.isQuoteOnlyEdge() &&
                    mainEdges.trailing.alreadyContainsQuoteEdge(lines[index].text)
            }
            val leadingToAppend = leadingIndexes - redundantLeading.toSet()
            val trailingToAppend = trailingIndexes - redundantTrailing.toSet()
            val paddedText = buildString {
                leadingToAppend.forEach { append(lines[it].text.trim()) }
                append(main.text.trim())
                trailingToAppend.forEach { append(lines[it].text.trim()) }
            }
            val mergedText = merge(main.text, paddedText)
            val textChanged = mergedText != main.text.trim()
            if (!textChanged && redundantLeading.isEmpty() && redundantTrailing.isEmpty()) {
                return@forEach
            }

            mergedMainLines[mainIndex] = main.copy(text = mergedText)
            consumedFragments += redundantLeading
            consumedFragments += redundantTrailing
            if (textChanged) {
                consumedFragments += leadingToAppend
                consumedFragments += trailingToAppend
            }
        }

        if (mergedMainLines.isEmpty()) return lines
        return lines.indices.mapNotNull { index ->
            when {
                index in consumedFragments -> null
                index in mergedMainLines -> mergedMainLines.getValue(index)
                else -> lines[index]
            }
        }
    }

    fun mayHaveRecoverableEdges(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotBlank() && splitEdges(trimmed).body.hasJapaneseOrCjkText()
    }

    fun isRecoverableDetachedFragment(text: String): Boolean {
        return text.isDetachedEdgeFragment()
    }

    private fun splitEdges(text: String): EdgeParts {
        var bodyStart = 0
        while (bodyStart < text.length && text[bodyStart].isLeadingEdgeCharacter()) {
            bodyStart++
        }

        var bodyEnd = text.length
        while (bodyEnd > bodyStart && text[bodyEnd - 1].isTrailingEdgeCharacter()) {
            bodyEnd--
        }
        return EdgeParts(
            leading = text.substring(0, bodyStart),
            body = text.substring(bodyStart, bodyEnd),
            trailing = text.substring(bodyEnd)
        )
    }

    private fun chooseMoreCompleteEdge(
        current: String,
        candidate: String,
        isTrusted: (String) -> Boolean
    ): String {
        if (!isTrusted(candidate)) return current
        if (current.isBlank()) return candidate

        val currentKey = canonicalEdgeKey(current)
        val candidateKey = canonicalEdgeKey(candidate)
        if (candidateKey == currentKey) return current
        if (candidateKey.length <= currentKey.length) return current
        return if (candidateKey.isCompatibleExtensionOf(currentKey)) {
            candidate
        } else {
            current
        }
    }

    /**
     * Closing quotes can surround a longer terminal unit, for example a tight
     * `?` versus a padded `!?」`. Compare quotation nesting and the remaining
     * punctuation independently so the quote does not hide a valid extension.
     */
    private fun String.isCompatibleExtensionOf(current: String): Boolean {
        if (startsWith(current) || endsWith(current)) return true

        val currentQuotes = current.filter { it.isCanonicalQuoteSymbol() }
        val candidateQuotes = filter { it.isCanonicalQuoteSymbol() }
        val currentPunctuation = current.filterNot { it.isCanonicalQuoteSymbol() }
        val candidatePunctuation = filterNot { it.isCanonicalQuoteSymbol() }
        return candidateQuotes.extendsEdgeSequence(currentQuotes) &&
            candidatePunctuation.extendsEdgeSequence(currentPunctuation)
    }

    private fun String.extendsEdgeSequence(current: String): Boolean {
        return current.isEmpty() || startsWith(current) || endsWith(current)
    }

    private fun Char.isCanonicalQuoteSymbol(): Boolean = this in CANONICAL_QUOTE_SYMBOLS

    private fun String.isDetachedEdgeFragment(): Boolean {
        val symbols = trim().filterNot(Char::isWhitespace)
        return symbols.isNotEmpty() &&
            symbols.none { it.hasJapaneseOrCjkText() } &&
            symbols.all { it in DETACHED_EDGE_SYMBOLS }
    }

    private fun String.isQuoteOnlyEdge(): Boolean {
        val symbols = trim().filterNot(Char::isWhitespace)
        return symbols.isNotEmpty() && symbols.all { it in ALL_QUOTE_SYMBOLS }
    }

    private fun String.alreadyContainsQuoteEdge(candidate: String): Boolean {
        val currentQuotes = canonicalEdgeKey(this).filter { it.isCanonicalQuoteSymbol() }
        val candidateQuotes = canonicalEdgeKey(candidate).filter { it.isCanonicalQuoteSymbol() }
        return candidateQuotes.isNotEmpty() && candidateQuotes in currentQuotes
    }

    private fun Char.hasJapaneseOrCjkText(): Boolean {
        return this in '\u3040'..'\u30ff' ||
            this in '\u31f0'..'\u31ff' ||
            this in '\u3400'..'\u9fff' ||
            this in '\uf900'..'\ufaff' ||
            this in '\uff66'..'\uff9d' ||
            this in MASK_SYMBOLS
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }

    private fun String.hasClosingQuoteAtTrailingEdge(closingQuote: Char): Boolean {
        val visible = trim()
        val closingIndex = visible.lastIndexOf(closingQuote)
        if (closingIndex < 0) return false
        if (closingQuote == '"' && closingIndex == 0) return false
        return visible.substring(closingIndex + 1).all { it.isTrailingEdgeCharacter() }
    }

    private fun bestAttachment(
        fragment: PositionedLine,
        main: PositionedLine,
        mainIndex: Int,
        allowLeading: Boolean,
        allowTrailing: Boolean
    ): DetachedAttachment? {
        if (!allowLeading && !allowTrailing) return null

        val fragmentHeight = (fragment.bottom - fragment.top).coerceAtLeast(1)
        val mainHeight = (main.bottom - main.top).coerceAtLeast(1)
        val verticalOverlap = (
            minOf(fragment.bottom, main.bottom) - maxOf(fragment.top, main.top)
            ).coerceAtLeast(0)
        val minimumHeight = minOf(fragmentHeight, mainHeight).coerceAtLeast(1)
        val centerDifference = kotlin.math.abs(
            (fragment.top + fragment.bottom) / 2f - (main.top + main.bottom) / 2f
        )
        val maximumHeight = maxOf(fragmentHeight, mainHeight)
        val sharesVisualRow = verticalOverlap >= minimumHeight * DETACHED_MIN_VERTICAL_OVERLAP_RATIO ||
            centerDifference <= maximumHeight * DETACHED_MAX_CENTER_DIFFERENCE_RATIO
        if (!sharesVisualRow) return null

        val fragmentCenterX = (fragment.left + fragment.right) / 2f
        val mainCenterX = (main.left + main.right) / 2f
        val maximumGap = maxOf(
            DETACHED_MIN_HORIZONTAL_GAP.toFloat(),
            mainHeight * DETACHED_MAX_HORIZONTAL_GAP_HEIGHT_RATIO
        )
        val candidates = buildList {
            if (allowLeading && fragmentCenterX < mainCenterX) {
                val gap = (main.left - fragment.right).coerceAtLeast(0).toFloat()
                if (gap <= maximumGap && fragmentCenterX <= main.left + mainHeight * 0.5f) {
                    add(DetachedAttachment(mainIndex, EdgeSide.LEADING, gap + centerDifference * 0.5f))
                }
            }
            if (allowTrailing && fragmentCenterX > mainCenterX) {
                val gap = (fragment.left - main.right).coerceAtLeast(0).toFloat()
                if (gap <= maximumGap && fragmentCenterX >= main.right - mainHeight * 0.5f) {
                    add(DetachedAttachment(mainIndex, EdgeSide.TRAILING, gap + centerDifference * 0.5f))
                }
            }
        }
        return candidates.minByOrNull { it.score }
    }

    private fun isTrustedLeadingEdge(edge: String): Boolean {
        val symbols = edge.filterNot(Char::isWhitespace)
        if (symbols.isEmpty() || symbols.any { it !in LEADING_EDGE_SYMBOLS }) return false
        val visualLength = symbols.sumOf(::visualSymbolLength)
        return symbols.any { it in DASH_SYMBOLS || it in OPENING_QUOTE_SYMBOLS } ||
            visualLength >= MIN_LEADING_PAUSE_DOTS
    }

    private fun isTrustedTrailingEdge(edge: String): Boolean {
        val symbols = edge.filterNot(Char::isWhitespace)
        return symbols.isNotEmpty() && symbols.all { it in TRAILING_EDGE_SYMBOLS }
    }

    private fun canonicalEdgeKey(edge: String): String {
        return buildString {
            edge.forEach { symbol ->
                when (symbol) {
                    '.', '．', '·', '・', '･' -> append('.')
                    '‥' -> append("..")
                    '…', '⋯' -> append("...")
                    '—', '―', '─', '━', '－', '-' -> append('-')
                    '！' -> append('!')
                    '？' -> append('?')
                    '，', '、' -> append(',')
                    '“', '”' -> append('"')
                    else -> if (!symbol.isWhitespace()) append(symbol)
                }
            }
        }
    }

    private fun visualSymbolLength(symbol: Char): Int = when (symbol) {
        '‥' -> 2
        '…', '⋯' -> 3
        else -> 1
    }

    private fun String.hasJapaneseOrCjkText(): Boolean {
        return any { it.hasJapaneseOrCjkText() }
    }

    private fun comparisonKey(text: String): String = text.filterNot(Char::isWhitespace)

    private fun Char.isLeadingEdgeCharacter(): Boolean = isWhitespace() || this in LEADING_EDGE_SYMBOLS

    private fun Char.isTrailingEdgeCharacter(): Boolean = isWhitespace() || this in TRAILING_EDGE_SYMBOLS

    private data class EdgeParts(
        val leading: String,
        val body: String,
        val trailing: String
    )

    private data class DetachedAttachment(
        val mainIndex: Int,
        val side: EdgeSide,
        val score: Float
    )

    private enum class EdgeSide {
        LEADING,
        TRAILING
    }

    private val PAUSE_SYMBOLS = setOf('.', '．', '·', '・', '･', '…', '‥', '⋯')
    private val DASH_SYMBOLS = setOf('—', '―', '─', '━', '－', '-')
    private val OPENING_QUOTE_SYMBOLS = setOf('「', '『', '“', '"')
    private val CLOSING_QUOTE_SYMBOLS = setOf('」', '』', '”', '"')
    private val QUOTE_COUNTERPARTS = mapOf(
        '「' to '」',
        '『' to '』',
        '“' to '”',
        '"' to '"'
    )
    private val ALL_QUOTE_SYMBOLS = OPENING_QUOTE_SYMBOLS + CLOSING_QUOTE_SYMBOLS
    private val CANONICAL_QUOTE_SYMBOLS = setOf('「', '『', '」', '』', '"')
    private val LEADING_EDGE_SYMBOLS = PAUSE_SYMBOLS + DASH_SYMBOLS + OPENING_QUOTE_SYMBOLS
    private val TRAILING_EDGE_SYMBOLS = PAUSE_SYMBOLS + DASH_SYMBOLS +
        CLOSING_QUOTE_SYMBOLS +
        setOf('。', '!', '！', '?', '？', ',', '，', '、')
    private val DETACHED_EDGE_SYMBOLS = LEADING_EDGE_SYMBOLS + TRAILING_EDGE_SYMBOLS
    private val MASK_SYMBOLS = setOf('■', '□', '▇', '█')
    private const val MIN_LEADING_PAUSE_DOTS = 2
    private const val DETACHED_MIN_VERTICAL_OVERLAP_RATIO = 0.25f
    private const val DETACHED_MAX_CENTER_DIFFERENCE_RATIO = 0.55f
    private const val DETACHED_MAX_HORIZONTAL_GAP_HEIGHT_RATIO = 1.5f
    private const val DETACHED_MIN_HORIZONTAL_GAP = 6
}
