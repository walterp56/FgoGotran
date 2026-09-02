package com.fgogotran.translation

/**
 * Shared FGO-style punctuation rules used after OCR and translation.
 *
 * This object deliberately works on punctuation only. It never compares or
 * rewrites words, and it never participates in translation retry decisions.
 */
object FgoDialogueSymbols {
    const val PAUSE_ELLIPSIS = "……"
    const val LONG_DASH_RUN = "───"

    val longPausePattern = Regex("[·・･]{2,}|\\.{2,}|…+|‥+|⋯+")
    val trailingDashRunPattern = Regex("[—―─━－\\-]{2,}\\s*$")

    private val alternateEllipsisPattern = Regex("[‥⋯]+")
    private val repeatedMiddleDotPattern = Regex("[·・･]{2,}")
    private val asciiPauseDotRunPattern = Regex("\\.+")
    private val leadingAsciiDashBeforeTextPattern =
        Regex("(?m)(^|[「『（(\\[\\s　])-+(?=[\\u3400-\\u9FFF■□▇█])")
    private val leadingOcrHyphenBeforeJapanesePattern =
        Regex(
            """(?m)(^|[「『（(\[])([ \t　]*)[-－]+(?=[\u3040-\u30FF\u31F0-\u31FF\u3400-\u9FFF\uF900-\uFAFF\uFF66-\uFF9D■□▇█])"""
        )

    fun containsLongPause(text: String): Boolean {
        return longPausePattern.containsMatchIn(text)
    }

    fun startsWithLongPause(text: String): Boolean {
        return longPausePattern.find(text)?.range?.first == 0
    }

    fun endsWithLongPause(text: String): Boolean {
        val match = longPausePattern.findAll(text).lastOrNull() ?: return false
        return match.range.last == text.lastIndex
    }

    /**
     * Converts common OCR/model pause substitutes without changing the length
     * of a genuine Unicode ellipsis run. FGO uses intentional odd as well as
     * even `…` counts, so those runs must remain exact.
     */
    fun normalizePauseDots(text: String): String {
        return alternateEllipsisPattern
            .replace(text) { match -> "…".repeat(match.value.length) }
            .let { normalized ->
                repeatedMiddleDotPattern.replace(normalized) { match ->
                    "…".repeat(match.value.length)
                }
            }
            .let { normalized ->
                asciiPauseDotRunPattern.replace(normalized) { match ->
                    when {
                        !normalized.hasCjkTextAround(match.range.first, match.range.last + 1) -> {
                            match.value
                        }

                        match.value.length == 3 -> PAUSE_ELLIPSIS
                        match.value.length == 6 -> PAUSE_ELLIPSIS.repeat(2)
                        else -> match.value
                    }
                }
            }
    }

    /**
     * Applies local, conservative FGO-style cleanup to completed translated
     * text. Pure emotional clusters are never reordered or collapsed.
     */
    fun normalizeTranslatedPunctuation(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return trimmed

        val repaired = repairTerminalBracketNoise(trimmed)
        val terminalIndex = repaired.indexOfLast { !it.isWhitespace() }
        val normalized = StringBuilder(repaired.length)
        var index = 0
        while (index < repaired.length) {
            val symbol = repaired[index]
            when (symbol) {
                '.' -> {
                    val runEnd = repaired.indexAfterRun(index, '.')
                    val runLength = runEnd - index
                    when {
                        runLength in ASCII_ELLIPSIS_RUNS &&
                            repaired.hasCjkTextAround(index, runEnd) -> {
                            repeat(runLength / 3) { normalized.append(PAUSE_ELLIPSIS) }
                        }

                        runLength == 1 && repaired.shouldNormalizePeriod(index) -> {
                            normalized.append('。')
                        }

                        else -> normalized.append(repaired, index, runEnd)
                    }
                    index = runEnd
                }

                '．' -> {
                    val runEnd = repaired.indexAfterRun(index, '．')
                    val runLength = runEnd - index
                    when {
                        runLength in ASCII_ELLIPSIS_RUNS &&
                            repaired.hasCjkTextAround(index, runEnd) -> {
                            repeat(runLength / 3) { normalized.append(PAUSE_ELLIPSIS) }
                        }

                        runLength == 1 && repaired.shouldNormalizePeriod(index) -> {
                            normalized.append('。')
                        }

                        else -> normalized.append(repaired, index, runEnd)
                    }
                    index = runEnd
                }

                '…' -> {
                    val runEnd = repaired.indexAfterRun(index, '…')
                    normalized.append(repaired, index, runEnd)
                    index = runEnd
                }

                '‥', '⋯' -> {
                    val runEnd = repaired.indexAfterMatchingRun(index) { it == '‥' || it == '⋯' }
                    repeat(runEnd - index) { normalized.append('…') }
                    index = runEnd
                }

                ',', '，', '、' -> {
                    val isTerminalCommaRun = repaired
                        .substring(index, terminalIndex + 1)
                        .all { it in COMMA_SYMBOLS }
                    normalized.append(
                        if (
                            symbol == ',' &&
                            !isTerminalCommaRun &&
                            repaired.hasCjkTextAround(index, index + 1)
                        ) {
                            '，'
                        } else {
                            symbol
                        }
                    )
                    index++
                }

                '!', '?' -> {
                    val useFgoWidth = repaired.hasPrecedingCjkText(index) ||
                        repaired.hasImmediatelyPrecedingCjkClosingDelimiter(index)
                    normalized.append(
                        if (useFgoWidth) {
                            if (symbol == '!') '！' else '？'
                        } else {
                            symbol
                        }
                    )
                    index++
                }

                else -> {
                    normalized.append(symbol)
                    index++
                }
            }
        }

        return cleanMalformedTerminalTail(normalized.toString())
    }

    /**
     * Reconciles only high-confidence source punctuation. The source terminal
     * unit replaces the translated terminal unit atomically, preserving its
     * exact `!?` order/count and ellipsis length. Balanced outer source
     * wrappers are then restored without inspecting or changing their text.
     * Internal translated punctuation remains untouched apart from local
     * malformed-tail cleanup.
     */
    fun reconcileSourcePunctuation(sourceText: String, translatedText: String): String {
        var result = normalizeTranslatedPunctuation(translatedText)
            .let(::normalizeDashRuns)
        if (result.isBlank()) return result

        result = restoreLeadingSourcePause(sourceText, result)
        result = reconcileTrustedTerminal(sourceText, result)
        return restoreSourceOuterWrappers(sourceText, result)
    }

    private fun reconcileTrustedTerminal(sourceText: String, translatedText: String): String {
        val sourceTerminal = trustedTerminalSpan(sourceText)?.canonical
            ?: return translatedText
        val targetTerminal = terminalSpan(translatedText)
        if (targetTerminal != null) {
            return translatedText.replaceRange(
                targetTerminal.start,
                targetTerminal.endExclusive,
                sourceTerminal
            )
        }

        val insertionIndex = trailingClosingSuffixStart(translatedText)
        return translatedText.substring(0, insertionIndex) +
            sourceTerminal +
            translatedText.substring(insertionIndex)
    }

    /**
     * A compact, high-confidence punctuation signature for OCR scene
     * stability. It intentionally excludes internal punctuation, so ordinary
     * OCR punctuation jitter does not cause scene churn.
     */
    fun sourcePunctuationStabilitySignature(text: String): String {
        val wrapperLayers = outerWrapperLayers(text)
        val wrappers = wrapperLayers.layers
            .joinToString(separator = "") { "${it.opening}${it.closing}" }
        val leading = leadingPauseSpan(text)?.canonical.orEmpty()
        val terminal = trustedTerminalSpan(text)?.canonical.orEmpty()
        if (wrappers.isEmpty() && leading.isEmpty() && terminal.isEmpty()) return ""
        val wrapperPart = if (wrappers.isEmpty()) {
            ""
        } else {
            val terminalPlacement = if (
                wrapperLayers.trailingPunctuationStart < wrapperLayers.visibleEndExclusive
            ) {
                "out"
            } else {
                "in"
            }
            "W:$wrappers@$terminalPlacement|"
        }
        return "${wrapperPart}L:$leading|T:$terminal"
    }

    /**
     * Restores balanced delimiters that wrap the complete source dialogue.
     * Compatible target quote styles are replaced rather than nested; a
     * different semantic wrapper (for example parentheses inside a quote) is
     * retained inside the source wrapper.
     */
    private fun restoreSourceOuterWrappers(sourceText: String, translatedText: String): String {
        val source = outerWrapperLayers(sourceText)
        if (source.layers.isEmpty()) return translatedText

        val target = outerWrapperLayers(translatedText)
        val mergedLayers = mutableListOf<OuterWrapper>()
        var targetIndex = 0
        source.layers.forEach { sourceLayer ->
            val targetLayer = target.layers.getOrNull(targetIndex)
            if (targetLayer != null && sourceLayer.isCompatibleWith(targetLayer)) {
                mergedLayers += sourceLayer.withoutPositions()
                targetIndex++
            } else {
                mergedLayers += sourceLayer.withoutPositions()
            }
        }
        target.layers.drop(targetIndex).forEach { mergedLayers += it.withoutPositions() }

        val prefix = translatedText.substring(0, target.visibleStart)
        var body = translatedText.substring(target.bodyStart, target.bodyEndExclusive)
        var trailingPunctuation = translatedText.substring(
            target.trailingPunctuationStart,
            target.visibleEndExclusive
        )
        val sourceTrailingPunctuation = sourceText.substring(
            source.trailingPunctuationStart,
            source.visibleEndExclusive
        )
        if (sourceTrailingPunctuation.isNotEmpty()) {
            if (trailingPunctuation.isEmpty()) {
                terminalSpan(body)?.let { terminal ->
                    trailingPunctuation = body.substring(terminal.start, terminal.endExclusive)
                    body = body.removeRange(terminal.start, terminal.endExclusive)
                }
            }
            val outerSource = source.layers.first()
            val sourceInnerText = sourceText.substring(
                outerSource.openingIndex + 1,
                outerSource.closingIndex
            )
            body = restoreLeadingSourcePause(sourceInnerText, body)
            body = reconcileTrustedTerminal(sourceInnerText, body)
        } else if (
            trailingPunctuation.isNotEmpty() &&
            trustedTerminalSpan(sourceText) != null
        ) {
            // The source terminal is inside its wrapper, so a conflicting
            // translated tail outside a quote must be moved back inside.
            body = reconcileTrustedTerminal(sourceText, body)
            trailingPunctuation = ""
        }
        val suffix = translatedText.substring(target.visibleEndExclusive)
        return buildString(translatedText.length + source.layers.size * 2) {
            append(prefix)
            mergedLayers.forEach { append(it.opening) }
            append(body)
            mergedLayers.asReversed().forEach { append(it.closing) }
            append(trailingPunctuation)
            append(suffix)
        }
    }

    private fun outerWrapperLayers(text: String): OuterWrapperLayers {
        val visibleStart = text.indexOfFirst { !it.isWhitespace() }
        val visibleEndExclusive = text.indexOfLast { !it.isWhitespace() } + 1
        if (visibleStart < 0 || visibleEndExclusive <= visibleStart) {
            return OuterWrapperLayers(
                layers = emptyList(),
                visibleStart = 0,
                visibleEndExclusive = 0,
                bodyStart = 0,
                bodyEndExclusive = 0,
                trailingPunctuationStart = 0
            )
        }

        val scan = scanBrackets(text)
        val layers = mutableListOf<OuterWrapper>()
        var bodyStart = visibleStart
        var bodyEndExclusive = visibleEndExclusive
        var trailingPunctuationStart = visibleEndExclusive
        while (bodyEndExclusive - bodyStart >= 2) {
            val opening = text[bodyStart]
            val closingIndex = if (layers.isEmpty()) {
                text.outerClosingIndex(
                    openingIndex = bodyStart,
                    visibleEndExclusive = visibleEndExclusive,
                    scan = scan
                )
            } else {
                bodyEndExclusive - 1
            }
            if (closingIndex <= bodyStart) break
            val closing = text[closingIndex]
            val type = when {
                opening == '"' && closing == '"' -> BracketType.DOUBLE_QUOTE
                opening == '\'' && closing == '\'' -> BracketType.SINGLE_QUOTE
                opening.openingBracketType() != null &&
                    opening.openingBracketType() == closing.closingBracketType() &&
                    scan.closeForOpen[bodyStart] == closingIndex -> opening.openingBracketType()
                else -> null
            } ?: break

            layers += OuterWrapper(
                opening = opening,
                closing = closing,
                type = type,
                openingIndex = bodyStart,
                closingIndex = closingIndex
            )
            if (layers.size == 1) trailingPunctuationStart = closingIndex + 1
            bodyStart++
            bodyEndExclusive = closingIndex
        }

        return OuterWrapperLayers(
            layers = layers,
            visibleStart = visibleStart,
            visibleEndExclusive = visibleEndExclusive,
            bodyStart = bodyStart,
            bodyEndExclusive = bodyEndExclusive,
            trailingPunctuationStart = trailingPunctuationStart
        )
    }

    private fun String.outerClosingIndex(
        openingIndex: Int,
        visibleEndExclusive: Int,
        scan: BracketScan
    ): Int {
        val opening = this[openingIndex]
        val closingIndex = when (opening) {
            '"', '\'' -> {
                var candidate = visibleEndExclusive - 1
                while (candidate > openingIndex && this[candidate] != opening) candidate--
                candidate
            }

            else -> scan.closeForOpen.getOrElse(openingIndex) { -1 }
        }
        if (closingIndex <= openingIndex) return -1
        return closingIndex.takeIf { index ->
            substring(index + 1, visibleEndExclusive)
                .all { it.isTerminalPunctuationCandidate() }
        } ?: -1
    }

    private fun OuterWrapper.isCompatibleWith(other: OuterWrapper): Boolean {
        return type == other.type || (type.isQuoteType() && other.type.isQuoteType())
    }

    private fun OuterWrapper.withoutPositions(): OuterWrapper {
        return copy(openingIndex = -1, closingIndex = -1)
    }

    private fun BracketType.isQuoteType(): Boolean {
        return this == BracketType.CORNER ||
            this == BracketType.DOUBLE_CORNER ||
            this == BracketType.DOUBLE_QUOTE ||
            this == BracketType.SINGLE_QUOTE
    }

    /** Returns terminal punctuation plus any trailing closing delimiters. */
    fun terminalDisplaySuffix(text: String): String {
        val trimmedEnd = text.indexOfLast { !it.isWhitespace() } + 1
        if (trimmedEnd <= 0) return ""
        val span = terminalSpan(text)
        if (span != null) return text.substring(span.start, trimmedEnd)
        val visibleText = text.substring(0, trimmedEnd)
        val suffixStart = trailingClosingSuffixStart(visibleText)
        return if (suffixStart < trimmedEnd) text.substring(suffixStart, trimmedEnd) else ""
    }

    /**
     * Normalizes visual interruption bars while protecting repeated `ー` and
     * ASCII tokens such as A--B, --help, and URL path segments.
     */
    fun normalizeDashRuns(text: String): String {
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] !in DASH_RUN_SYMBOLS) {
                output.append(text[index])
                index++
                continue
            }

            val runEnd = text.indexAfterMatchingRun(index) { it in DASH_RUN_SYMBOLS }
            val run = text.substring(index, runEnd)
            if (run.length >= 2 && text.shouldNormalizeDashRun(index, runEnd, run)) {
                output.append(LONG_DASH_RUN)
            } else {
                output.append(run)
            }
            index = runEnd
        }
        return output.toString()
    }

    /**
     * Repairs the common OCR result where a leading dramatic dash is read as
     * an ASCII/full-width hyphen. The Japanese/mask look-ahead deliberately
     * excludes negative numbers and ordinary Latin hyphenated text.
     */
    fun normalizeLeadingOcrDash(text: String): String {
        return leadingOcrHyphenBeforeJapanesePattern.replace(text) {
            "${it.groupValues[1]}${it.groupValues[2]}$LONG_DASH_RUN"
        }
    }

    fun normalizeForRender(text: String): String {
        return normalizeTranslatedPunctuation(text)
            .replace('－', '-')
            .replace('―', '—')
            .let(::normalizeDashRuns)
            .replace(leadingAsciiDashBeforeTextPattern) {
                "${it.groupValues[1]}$LONG_DASH_RUN"
            }
            .let(::normalizePauseDots)
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    fun isDashRunChar(char: Char): Boolean {
        // `ー` is lexical (the katakana prolonged-sound mark), including when
        // repeated for shouted FGO dialogue. It must never be treated as a dash.
        return char in DASH_RUN_SYMBOLS
    }

    private fun restoreLeadingSourcePause(sourceText: String, translatedText: String): String {
        val sourcePause = leadingPauseSpan(sourceText) ?: return translatedText
        val targetPause = leadingPauseSpan(translatedText)
        if (targetPause != null) {
            if (
                targetPause.canonical.all { it == '…' } &&
                sourcePause.canonical.all { it == '…' } &&
                targetPause.canonical.length < sourcePause.canonical.length
            ) {
                return translatedText.replaceRange(
                    targetPause.start,
                    targetPause.endExclusive,
                    sourcePause.canonical
                )
            }
            return translatedText
        }

        val insertionIndex = leadingOpeningPrefixEnd(translatedText)
        return translatedText.substring(0, insertionIndex) +
            sourcePause.canonical +
            translatedText.substring(insertionIndex)
    }

    private fun leadingPauseSpan(text: String): PunctuationSpan? {
        val start = leadingOpeningPrefixEnd(text)
        if (start >= text.length) return null
        val match = longPausePattern.find(text, start)?.takeIf { it.range.first == start } ?: return null
        val raw = match.value
        val canonical = canonicalizePauseRun(raw)
        return PunctuationSpan(start, match.range.last + 1, raw, canonical)
    }

    private fun leadingOpeningPrefixEnd(text: String): Int {
        val outerWrappers = outerWrapperLayers(text)
        if (outerWrappers.layers.isNotEmpty()) return outerWrappers.bodyStart

        var index = 0
        while (index < text.length && text[index].isWhitespace()) index++
        while (index < text.length && text[index] in OPENING_DELIMITERS) {
            index++
            while (index < text.length && text[index].isHorizontalWhitespace()) index++
        }
        return index
    }

    private fun trustedTerminalSpan(text: String): PunctuationSpan? {
        val span = terminalSpan(text) ?: return null
        if (span.raw.isLowConfidenceSourceTail()) return null
        if (text.hasSuspiciousTerminalClosingDelimiter()) return null
        val canonical = canonicalizeSourceTerminal(span.raw)
        if (canonical.isBlank()) return null
        return span.copy(canonical = canonical)
    }

    private fun terminalSpan(text: String): PunctuationSpan? {
        var visibleEnd = text.indexOfLast { !it.isWhitespace() } + 1
        if (visibleEnd <= 0) return null

        val outerClosingIndices = outerWrapperLayers(text).layers
            .mapTo(mutableSetOf()) { it.closingIndex }
        var punctuationEnd = visibleEnd
        while (
            punctuationEnd > 0 &&
            (
                text[punctuationEnd - 1] in CLOSING_DELIMITERS ||
                    punctuationEnd - 1 in outerClosingIndices
                )
        ) {
            punctuationEnd--
            while (punctuationEnd > 0 && text[punctuationEnd - 1].isHorizontalWhitespace()) {
                punctuationEnd--
            }
        }

        var punctuationStart = punctuationEnd
        while (
            punctuationStart > 0 &&
            text[punctuationStart - 1].isTerminalPunctuationCandidate()
        ) {
            punctuationStart--
        }
        if (punctuationStart == punctuationEnd) return null

        val raw = text.substring(punctuationStart, punctuationEnd)
        return PunctuationSpan(
            start = punctuationStart,
            endExclusive = punctuationEnd,
            raw = raw,
            canonical = raw
        )
    }

    private fun trailingClosingSuffixStart(text: String): Int {
        val outerClosingIndices = outerWrapperLayers(text).layers
            .mapTo(mutableSetOf()) { it.closingIndex }
        var index = text.indexOfLast { !it.isWhitespace() } + 1
        while (
            index > 0 &&
            (text[index - 1] in CLOSING_DELIMITERS || index - 1 in outerClosingIndices)
        ) {
            index--
            while (index > 0 && text[index - 1].isHorizontalWhitespace()) index--
        }
        return index
    }

    private fun canonicalizeSourceTerminal(raw: String): String {
        val output = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val symbol = raw[index]
            when (symbol) {
                '!', '?' -> {
                    output.append(if (symbol == '!') '！' else '？')
                    index++
                }

                '.' -> {
                    val end = raw.indexAfterRun(index, '.')
                    val length = end - index
                    when (length) {
                        1 -> output.append('。')
                        3 -> output.append(PAUSE_ELLIPSIS)
                        6 -> output.append(PAUSE_ELLIPSIS.repeat(2))
                        else -> output.append(raw, index, end)
                    }
                    index = end
                }

                '．' -> {
                    val end = raw.indexAfterRun(index, '．')
                    val length = end - index
                    when (length) {
                        1 -> output.append('。')
                        3 -> output.append(PAUSE_ELLIPSIS)
                        6 -> output.append(PAUSE_ELLIPSIS.repeat(2))
                        else -> output.append(raw, index, end)
                    }
                    index = end
                }

                ',', '，' -> {
                    output.append('、')
                    index++
                }

                '‥', '⋯' -> {
                    output.append('…')
                    index++
                }

                '·', '・', '･' -> {
                    val end = raw.indexAfterMatchingRun(index) { it == '·' || it == '・' || it == '･' }
                    if (end - index >= 2) {
                        repeat(end - index) { output.append('…') }
                    } else {
                        output.append(symbol)
                    }
                    index = end
                }

                else -> {
                    output.append(symbol)
                    index++
                }
            }
        }
        return normalizeDashRuns(output.toString())
    }

    private fun canonicalizePauseRun(raw: String): String {
        return when {
            raw.all { it == '.' } && raw.length == 3 -> PAUSE_ELLIPSIS
            raw.all { it == '.' } && raw.length == 6 -> PAUSE_ELLIPSIS.repeat(2)
            raw.all { it == '·' || it == '・' || it == '･' } -> "…".repeat(raw.length)
            raw.all { it == '‥' || it == '⋯' } -> "…".repeat(raw.length)
            else -> raw
        }
    }

    private fun String.isLowConfidenceSourceTail(): Boolean {
        if (isBlank()) return true
        val hasComma = any { it in COMMA_SYMBOLS }
        val hasPeriod = any { it in PERIOD_SYMBOLS }
        val hasEmotion = any { it in EMOTIONAL_SYMBOLS }
        val hasPauseOrDecoration = any { it in PAUSE_OR_DECORATIVE_SYMBOLS }
        if (all { it in WEAK_TERMINAL_SYMBOLS } && length > 1 && hasComma && hasPeriod) {
            return true
        }
        return hasEmotion && (hasComma || hasPeriod) && !hasPauseOrDecoration
    }

    private fun repairTerminalBracketNoise(text: String): String {
        if (text.none { it in OPENING_DELIMITERS || it in CLOSING_DELIMITERS }) return text

        val scan = scanBrackets(text)
        val completedPairBefore = BooleanArray(text.length)
        var hasCompletedPair = false
        for (index in text.indices) {
            completedPairBefore[index] = hasCompletedPair
            if (scan.openForClose[index] >= 0) hasCompletedPair = true
        }

        val removableClosers = scan.unmatchedClosers.filterTo(mutableSetOf()) { index ->
            val preceding = text.previousNonWhitespace(index)
            val tail = text.substring(index + 1)
            val tailIsOnlyPunctuation = tail.all { char ->
                char.isWhitespace() ||
                    char.isTerminalPunctuationCandidate() ||
                    char in CLOSING_DELIMITERS
            }
            val followsCompletedStructure = completedPairBefore[index] &&
                preceding != null &&
                (preceding.isTerminalPunctuationCandidate() || preceding in CLOSING_DELIMITERS)
            val parenthesisBeforeEmotionalTail = text[index] in setOf(')', '）') &&
                tail.any { it in EMOTIONAL_SYMBOLS }
            tailIsOnlyPunctuation && (followsCompletedStructure || parenthesisBeforeEmotionalTail)
        }

        val cjkParenthesisPairs = mutableSetOf<Int>()
        scan.openForClose.forEachIndexed { closeIndex, openIndex ->
            if (openIndex < 0) return@forEachIndexed
            val type = text[openIndex].openingBracketType()
            if (
                type == BracketType.PARENTHESIS &&
                text.substring(openIndex + 1, closeIndex).any { it.isCjkTextCharacter() }
            ) {
                cjkParenthesisPairs += openIndex
                cjkParenthesisPairs += closeIndex
            }
        }

        if (removableClosers.isEmpty() && cjkParenthesisPairs.isEmpty()) return text
        return buildString(text.length) {
            text.forEachIndexed { index, char ->
                if (index in removableClosers) return@forEachIndexed
                if (index in cjkParenthesisPairs && scan.openForClose[index] >= 0) {
                    while (isNotEmpty() && last().isHorizontalWhitespace()) deleteCharAt(lastIndex)
                }
                when {
                    index in cjkParenthesisPairs && char.openingBracketType() == BracketType.PARENTHESIS -> {
                        append('（')
                    }

                    index in cjkParenthesisPairs && char.closingBracketType() == BracketType.PARENTHESIS -> {
                        append('）')
                    }

                    else -> append(char)
                }
            }
        }
    }

    private fun cleanMalformedTerminalTail(text: String): String {
        val span = terminalSpan(text) ?: return text
        val raw = span.raw
        val hasEmotion = raw.any { it in EMOTIONAL_SYMBOLS }
        val hasWeak = raw.any { it in WEAK_TERMINAL_SYMBOLS }
        val hasPauseOrDecoration = raw.any { it in PAUSE_OR_DECORATIVE_SYMBOLS }
        val cleaned = when {
            hasEmotion && hasWeak && !hasPauseOrDecoration -> {
                raw.filterNot { it in WEAK_TERMINAL_SYMBOLS }
            }

            raw.all { it in WEAK_TERMINAL_SYMBOLS } && raw.length > 1 -> {
                val containsComma = raw.any { it in COMMA_SYMBOLS }
                val containsPeriod = raw.any { it in PERIOD_SYMBOLS }
                when {
                    containsComma && containsPeriod -> "。"
                    containsComma -> raw.first { it in COMMA_SYMBOLS }.toString()
                    raw.all { it == '.' } -> raw
                    containsPeriod -> "。"
                    else -> raw
                }
            }

            else -> raw
        }
        if (cleaned == raw) return text
        return text.replaceRange(span.start, span.endExclusive, cleaned)
    }

    private fun String.hasSuspiciousTerminalClosingDelimiter(): Boolean {
        val scan = scanBrackets(this)
        return scan.unmatchedClosers.any { index ->
            substring(index + 1).all { char ->
                char.isWhitespace() ||
                    char.isTerminalPunctuationCandidate() ||
                    char in CLOSING_DELIMITERS
            }
        }
    }

    private fun scanBrackets(text: String): BracketScan {
        val stack = mutableListOf<BracketEntry>()
        val openForClose = IntArray(text.length) { -1 }
        val closeForOpen = IntArray(text.length) { -1 }
        val unmatchedClosers = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            val openingType = char.openingBracketType()
            if (openingType != null) {
                stack += BracketEntry(index, openingType)
                return@forEachIndexed
            }

            val closingType = char.closingBracketType() ?: return@forEachIndexed
            val opening = stack.lastOrNull()
            if (opening != null && opening.type == closingType) {
                stack.removeAt(stack.lastIndex)
                openForClose[index] = opening.index
                closeForOpen[opening.index] = index
            } else {
                unmatchedClosers += index
            }
        }
        return BracketScan(openForClose, closeForOpen, unmatchedClosers)
    }

    private fun String.shouldNormalizeDashRun(start: Int, endExclusive: Int, run: String): Boolean {
        val before = getOrNull(start - 1)
        val after = getOrNull(endExclusive)
        if (run.all { it == '-' }) {
            if (start == 0 && after?.isAsciiLetterOrDigit() == true) return false
            if (before?.isAsciiLetterOrDigit() == true && after?.isAsciiLetterOrDigit() == true) {
                return false
            }
        }
        if (
            run.any { it == '一' } &&
            (before?.isLetterOrDigit() == true || after?.isLetterOrDigit() == true)
        ) {
            return false
        }
        return true
    }

    private fun String.indexAfterRun(startIndex: Int, symbol: Char): Int {
        var index = startIndex
        while (index < length && this[index] == symbol) index++
        return index
    }

    private inline fun String.indexAfterMatchingRun(
        startIndex: Int,
        predicate: (Char) -> Boolean
    ): Int {
        var index = startIndex
        while (index < length && predicate(this[index])) index++
        return index
    }

    private fun String.hasPrecedingCjkText(punctuationIndex: Int): Boolean {
        var index = punctuationIndex - 1
        while (index >= 0) {
            val current = this[index]
            when {
                current == '\n' || current == '\r' -> return false
                current.isWhitespace() || current in CJK_CONTEXT_SKIPPED_SYMBOLS -> index--
                else -> return current.isCjkTextCharacter()
            }
        }
        return false
    }

    private fun String.hasFollowingCjkText(punctuationEndIndex: Int): Boolean {
        var index = punctuationEndIndex
        while (index < length) {
            val current = this[index]
            when {
                current == '\n' || current == '\r' -> return false
                current.isWhitespace() || current in CJK_CONTEXT_SKIPPED_SYMBOLS -> index++
                else -> return current.isCjkTextCharacter()
            }
        }
        return false
    }

    private fun String.hasCjkTextAround(startIndex: Int, endIndex: Int): Boolean {
        return hasPrecedingCjkText(startIndex) || hasFollowingCjkText(endIndex)
    }

    private fun String.hasImmediatelyPrecedingCjkClosingDelimiter(index: Int): Boolean {
        var previous = index - 1
        while (previous >= 0 && this[previous].isHorizontalWhitespace()) previous--
        return previous >= 0 && this[previous] in CJK_CLOSING_DELIMITERS
    }

    private fun String.shouldNormalizePeriod(periodIndex: Int): Boolean {
        if (
            !hasPrecedingCjkText(periodIndex) &&
            !hasImmediatelyPrecedingCjkClosingDelimiter(periodIndex)
        ) {
            return false
        }
        val next = getOrNull(periodIndex + 1) ?: return true
        return !next.isAsciiLetterOrDigit()
    }

    private fun String.previousNonWhitespace(beforeIndex: Int): Char? {
        var index = beforeIndex - 1
        while (index >= 0 && this[index].isWhitespace()) index--
        return getOrNull(index)
    }

    private fun Char.openingBracketType(): BracketType? {
        return when (this) {
            '(', '（' -> BracketType.PARENTHESIS
            '[', '［' -> BracketType.SQUARE
            '{', '｛' -> BracketType.BRACE
            '【' -> BracketType.BLACK_SQUARE
            '「' -> BracketType.CORNER
            '『' -> BracketType.DOUBLE_CORNER
            '《' -> BracketType.BOOK
            '〈' -> BracketType.ANGLE
            '“' -> BracketType.DOUBLE_QUOTE
            '‘' -> BracketType.SINGLE_QUOTE
            else -> null
        }
    }

    private fun Char.closingBracketType(): BracketType? {
        return when (this) {
            ')', '）' -> BracketType.PARENTHESIS
            ']', '］' -> BracketType.SQUARE
            '}', '｝' -> BracketType.BRACE
            '】' -> BracketType.BLACK_SQUARE
            '」' -> BracketType.CORNER
            '』' -> BracketType.DOUBLE_CORNER
            '》' -> BracketType.BOOK
            '〉' -> BracketType.ANGLE
            '”' -> BracketType.DOUBLE_QUOTE
            '’' -> BracketType.SINGLE_QUOTE
            else -> null
        }
    }

    private fun Char.isTerminalPunctuationCandidate(): Boolean {
        return this in TERMINAL_PUNCTUATION_SYMBOLS
    }

    private fun Char.isHorizontalWhitespace(): Boolean {
        return this == ' ' || this == '\t' || this == '　'
    }

    private fun Char.isCjkTextCharacter(): Boolean {
        return this in '\u3400'..'\u4DBF' ||
            this in '\u4E00'..'\u9FFF' ||
            this in '\uF900'..'\uFAFF' ||
            this in '\u3040'..'\u30FF' ||
            this in '\u31F0'..'\u31FF'
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }

    private data class PunctuationSpan(
        val start: Int,
        val endExclusive: Int,
        val raw: String,
        val canonical: String
    )

    private data class BracketEntry(val index: Int, val type: BracketType)

    private data class BracketScan(
        val openForClose: IntArray,
        val closeForOpen: IntArray,
        val unmatchedClosers: List<Int>
    )

    private data class OuterWrapper(
        val opening: Char,
        val closing: Char,
        val type: BracketType,
        val openingIndex: Int,
        val closingIndex: Int
    )

    private data class OuterWrapperLayers(
        val layers: List<OuterWrapper>,
        val visibleStart: Int,
        val visibleEndExclusive: Int,
        val bodyStart: Int,
        val bodyEndExclusive: Int,
        val trailingPunctuationStart: Int
    )

    private enum class BracketType {
        PARENTHESIS,
        SQUARE,
        BRACE,
        BLACK_SQUARE,
        CORNER,
        DOUBLE_CORNER,
        BOOK,
        ANGLE,
        DOUBLE_QUOTE,
        SINGLE_QUOTE
    }

    private val COMMA_SYMBOLS = setOf('、', '，', ',')
    private val PERIOD_SYMBOLS = setOf('。', '．', '.')
    private val WEAK_TERMINAL_SYMBOLS = COMMA_SYMBOLS + PERIOD_SYMBOLS
    private val EMOTIONAL_SYMBOLS = setOf('!', '！', '?', '？')
    private val PAUSE_OR_DECORATIVE_SYMBOLS = setOf(
        '…', '‥', '⋯', '·', '・', '･', '—', '―', '─', '━', '－', '-',
        '〜', '～', '~', '♪', '♡', '♥', '☆', '★'
    )
    private val ASCII_ELLIPSIS_RUNS = setOf(3, 6)
    private val DASH_RUN_SYMBOLS = setOf('—', '―', '─', '━', '－', '-', '一')
    private val OPENING_DELIMITERS = setOf(
        '(', '（', '[', '［', '{', '｛', '【', '「', '『', '《', '〈', '“', '‘'
    )
    private val CLOSING_DELIMITERS = setOf(
        ')', '）', ']', '］', '}', '｝', '】', '」', '』', '》', '〉', '”', '’'
    )
    private val CJK_CLOSING_DELIMITERS = setOf(
        '）', '］', '｝', '】', '」', '』', '》', '〉', '”', '’'
    )
    private val TERMINAL_PUNCTUATION_SYMBOLS = setOf(
        '。', '．', '.', '、', '，', ',', '！', '!', '？', '?',
        '…', '‥', '⋯', '·', '・', '･', '—', '―', '─', '━', '－', '-',
        '〜', '～', '~', '：', ':', '；', ';', '♪', '♡', '♥', '☆', '★'
    )
    private val CJK_CONTEXT_SKIPPED_SYMBOLS = setOf(
        '!', '?', '！', '？', '.', '．', '。', ',', '，', '、', ':', '：', ';', '；',
        '…', '‥', '⋯', '―', '—', '─', '～', '〜',
        '」', '』', '）', '】', '》', '〉', '］', '｝', '”', '’', '"', '\''
    )
}
