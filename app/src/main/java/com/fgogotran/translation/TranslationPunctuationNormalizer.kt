package com.fgogotran.translation

import com.fgogotran.text.EmphasisText
import kotlin.math.ceil
import kotlin.math.max

/** Output-only policy. OCR text, ruby syntax and scene stability signatures are not rewritten. */
internal object TranslationPunctuationNormalizer {
    const val VERSION = "target-punctuation-v1"
    private val pauses = Regex("…+|[‥⋯]+|[.．]{2,}|[·・･•]{2,}")
    private val dashes = Regex("[─━—―－-]{2,}|[─━—―]")
    private val technical = Regex("https?://[^\\s<>]+|\\b(?:[A-Za-z]\\.){2,}|\\b(?:Dr|Mr|Mrs|Ms|Prof|St)\\.(?=\\s|$|[!?])|[\\p{L}\\p{N}]+(?:[.．][\\p{L}\\p{N}]+)+|--[a-z][A-Za-z-]*|[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)+")
    private val tail = Regex("[。．.,，、!?！？…‥⋯─━—―－~〜～♪♡♥☆★:：;；-]+(?=[『』「」“”\"'’）)】》]*$)")
    private val opening = "『「“\"（("
    private val closing = "』」”\"）)"

    /** A reversible opaque view: punctuation inside annotations/technical literals is never visited. */
    private class Guard(private val original: String) {
        private var nextCode = 0xE200
        private val values = mutableListOf<Pair<String, String>>()
        val wordHints = mutableMapOf<Char, Char>()
        private fun hold(value: String, word: Boolean = false): String {
            while (nextCode.toChar() in original) nextCode++
            val key = (nextCode++).toChar().toString()
            values += key to value
            if (word) value.lastOrNull { it.isLetterOrDigit() }?.let { wordHints[key.single()] = it }
            return key
        }
        fun mask(): String {
            val brackets = mapOf('<' to '>', '[' to ']', '〈' to '〉')
            val result = StringBuilder()
            var i = 0
            while (i < original.length) {
                val endChar = brackets[original[i]]
                if (endChar == null) { result.append(original[i++]); continue }
                var j = i + 1
                var depth = 1
                while (j < original.length && original[j] != '\n' && depth > 0) {
                    if (original[j] == original[i]) depth++
                    if (original[j] == endChar) depth--
                    j++
                }
                // Incomplete notation stays verbatim through the end of its line.
                result.append(hold(original.substring(i, j)))
                i = j
            }
            return technical.replace(result.toString()) { hold(it.value, word = true) }
        }
        fun restore(value: String): String = values.asReversed().fold(value) { text, (key, literal) -> text.replace(key, literal) }
    }

    private fun english(locale: String) = locale.substringBefore('-') == "en"
    private fun dotCount(run: String): Int = run.fold(0) { total, c -> total + when (c) { '…', '⋯' -> 3; '‥' -> 2; else -> 1 } }
    private fun pause(run: String, en: Boolean, source: Boolean = false): String {
        val points = dotCount(run)
        return if (en) {
            val count = if (source || run.any { it in "…‥⋯" }) ceil(points / 2.0).toInt() else points
            ".".repeat(max(3, count))
        } else "…".repeat(max(2, ceil(points / 3.0).toInt()))
    }
    private fun dash(run: String, en: Boolean, source: Boolean = false): String =
        if (en) "—".repeat(max(1, if (source) ceil(run.length / 3.0).toInt() else if (run.all { it == '-' || it == '－' }) ceil(run.length / 3.0).toInt() else run.length))
        else "─".repeat(max(3, run.length))

    /** Used in cache material before input normalizers have expanded or collapsed runs. */
    fun sourceSignature(source: String): String {
        val masked = Guard(EmphasisText.plain(source)).mask()
        return (pauses.findAll(masked).map { "p${dotCount(it.value)}" } +
            dashes.findAll(masked).map { "d${it.value.length}" }).joinToString(",")
    }

    fun quotes(text: String): String = EmphasisText.transform(text) { plain ->
        val guard = Guard(plain)
        guard.restore(guard.mask().map {
            when (it) { '«', '「', '《' -> '『'; '»', '」', '》' -> '』'; else -> it }
        }.joinToString(""))
    }

    fun normalize(text: String, locale: String, source: String? = null): String = EmphasisText.transform(text) { plain ->
        if (plain.isBlank()) return@transform plain
        val en = english(locale)
        val guard = Guard(plain)
        var value = guard.mask().replace("\r\n", "\n").replace('\r', '\n')
        val sourceView = source?.let { Guard(EmphasisText.plain(it)).mask().replace("\r\n", "\n") }
        val ownPauses = pauses.findAll(value).map { pause(it.value, en) }.toList()
        val sourcePauses = sourceView?.let { pauses.findAll(it).map { match -> pause(match.value, en, source = true) }.toList() }.orEmpty()
        val ownDashes = dashes.findAll(value).map { dash(it.value, en) }.toList()
        val sourceDashes = sourceView?.let { dashes.findAll(it).map { match -> dash(match.value, en, source = true) }.toList() }.orEmpty()
        var index = 0
        value = pauses.replace(value) {
            val own = ownPauses[index++]
            // Only a single matching run is unambiguous across reordered translated words.
            if (ownPauses.size == 1 && sourcePauses.size == 1) longer(own, sourcePauses.single()) else own
        }
        index = 0
        value = dashes.replace(value) {
            val own = ownDashes[index++]
            if (ownDashes.size == 1 && sourceDashes.size == 1) longer(own, sourceDashes.single()) else own
        }
        if (sourceView != null) value = restoreEdges(sourceView, value, en)
        value = styleAndClean(value, en, guard.wordHints)
        guard.restore(value)
    }

    private fun longer(a: String, b: String) = if (a.length >= b.length) a else b
    private fun prefixEnd(text: String): Int {
        var i = 0
        while (i < text.length && (text[i].isWhitespace() || text[i] in opening)) i++
        return i
    }

    private fun restoreEdges(source: String, target: String, en: Boolean): String {
        var result = target
        val start = prefixEnd(source)
        val sourcePause = pauses.find(source, start)?.takeIf { it.range.first == start }
        val sourceDash = dashes.find(source, start)?.takeIf { it.range.first == start }
        val targetStart = prefixEnd(result)
        val targetPause = pauses.find(result, targetStart)?.takeIf { it.range.first == targetStart }
        val targetDash = dashes.find(result, targetStart)?.takeIf { it.range.first == targetStart }
        if (sourcePause != null && targetPause != null) {
            result = result.replaceRange(targetPause.range, longer(targetPause.value, pause(sourcePause.value, en, true)))
        } else if (sourcePause != null) {
            val standalone = source.substring(sourcePause.range.last + 1).startsWith("\n")
            result = result.substring(0, targetStart) + pause(sourcePause.value, en, true) +
                (if (standalone && !result.substring(targetStart).startsWith("\n")) "\n" else "") + result.substring(targetStart)
        } else if (sourceDash != null && targetDash != null) {
            result = result.replaceRange(targetDash.range, longer(targetDash.value, dash(sourceDash.value, en, true)))
        } else if (sourceDash != null) {
            result = result.substring(0, targetStart) + dash(sourceDash.value, en, true) + result.substring(targetStart)
        }
        val srcTail = tail.find(source.trim())
        if (srcTail != null) {
            val raw = srcTail.value
            // OCR tails like .! or ,? do not justify overwriting translated punctuation.
            val trusted = !(raw.any { it in ".．,，、。" } && raw.any { it in "!?！？" } && !pauses.containsMatchIn(raw))
            if (trusted) {
                val suffix = styleAndClean(dashes.replace(pauses.replace(raw) { pause(it.value, en, true) }) { dash(it.value, en, true) }, en)
                val dstTail = tail.find(result.trimEnd())
                val merged = if (dstTail == null) suffix else {
                    // Restore source emotion without shortening a longer translated pause/line.
                    val oldPause = pauses.find(dstTail.value)?.value
                    val newPause = pauses.find(suffix)?.value
                    val oldDash = dashes.find(dstTail.value)?.value
                    val newDash = dashes.find(suffix)?.value
                    var adjusted = suffix
                    if (oldPause != null && newPause == null) adjusted = oldPause + adjusted.trimStart('.', '．', '。', ',', '，', '、')
                    if (oldDash != null && newDash == null) adjusted = oldDash + adjusted.trimStart('.', '．', '。', ',', '，', '、')
                    if (oldPause != null && newPause != null) adjusted = pauses.replace(adjusted) { longer(it.value, oldPause) }
                    if (oldDash != null && newDash != null && oldDash.length > newDash.length) adjusted = adjusted.replace(newDash, oldDash)
                    adjusted
                }
                if (dstTail != null) result = result.replaceRange(dstTail.range, merged)
                else {
                    var end = result.length
                    while (end > 0 && result[end - 1] in closing) end--
                    result = result.substring(0, end) + merged + result.substring(end)
                }
            }
        }
        // Restore only complete outer speech quotes; annotation delimiters are opaque.
        val src = source.trim()
        val pairs = mapOf('「' to '」', '『' to '』', '“' to '”', '"' to '"')
        val close = src.firstOrNull()?.let(pairs::get)
        if (close != null && src.lastOrNull() == close && result.firstOrNull() !in pairs.keys) {
            result = (if (en) "\"" else src.first().toString()) + result + (if (en) "\"" else close.toString())
        }
        return result
    }

    private fun styleAndClean(text: String, en: Boolean, wordHints: Map<Char, Char> = emptyMap()): String {
        val styled = buildString {
            var previousWord: Char? = null
            text.forEachIndexed { i, c ->
                if (c == '\n') previousWord = null
                val previous = previousWord
                val cjk = previous != null && (previous in '\u3400'..'\u9fff' || previous in '\u3040'..'\u30ff')
                append(when {
                    !en && c in "«「《" -> '『'
                    !en && c in "»」》" -> '』'
                    en && c in "！？，。；：" -> when (c) { '！' -> '!'; '？' -> '?'; '，' -> ','; '。' -> '.'; '；' -> ';'; else -> ':' }
                    !en && (cjk || previous == null && text.any { it in "…─" }) && c == '!' -> '！'
                    !en && (cjk || previous == null && text.any { it in "…─" }) && c == '?' -> '？'
                    !en && cjk && c == ',' -> '，'
                    // Interior name separators are already opaque; period only at a sentence boundary.
                    !en && cjk && c in ".．" && (i == text.lastIndex || text[i + 1] in "\n『』「」“”\"!?！？,，。") -> '。'
                    else -> c
                })
                if (c.isLetterOrDigit()) previousWord = c
                wordHints[c]?.let { previousWord = it }
            }
        }
        return styled.lines().joinToString("\n") { line ->
            val match = tail.find(line) ?: return@joinToString line
            val run = match.value
            val hasPause = pauses.containsMatchIn(run) || dashes.containsMatchIn(run)
            val emotions = run.filter { it in "!?！？" }
            val cleaned = when {
                emotions.isNotEmpty() && !hasPause -> emotions
                !hasPause && run.length > 1 && run.all { it in ".．。,，、" } ->
                    if (run.any { it in ".．。" }) (if (en) "." else "。") else run.first().toString()
                else -> run
            }
            line.replaceRange(match.range, cleaned)
        }.trimEnd()
    }
}
