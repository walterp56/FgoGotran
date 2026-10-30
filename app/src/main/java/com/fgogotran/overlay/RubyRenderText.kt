package com.fgogotran.overlay

import com.fgogotran.text.EmphasisText
import com.fgogotran.text.EmphasisRange
import java.text.BreakIterator
import java.util.Locale

/** Display-only interpretation of the existing API/history notation. Never changes stored text. */
internal object RubyRenderText {
    /** Bilingual source text keeps the notation inline; translation decorates the base. */
    enum class Mode { ABOVE_BASE, INLINE }

    data class Annotation(val start: Int, val end: Int, val reading: String, val emphasis: Boolean = false)
    data class Marker(val start: Int, val end: Int)

    data class Text(
        val body: String,
        val annotations: List<Annotation> = emptyList(),
        val markers: List<Marker> = emptyList()
    ) {
        /** Reattach annotations after wrapping/spacing/ellipsis, without putting them in body width. */
        fun annotate(displayBody: String): String {
            if (annotations.isEmpty()) return displayBody
            val source = meaningfulOffsets(body)
            val target = meaningfulOffsets(displayBody)
            // Render cleanup changes spacing and punctuation, not the order of words. Ellipsis
            // may remove a suffix. Refuse to move an annotation onto unrelated text.
            if (target.size > source.size || target.indices.any {
                    body.codePointAt(source[it]) != displayBody.codePointAt(target[it])
                }) return displayBody
            fun mapped(annotation: Annotation): EmphasisRange? {
                val first = source.indexOfFirst { it >= annotation.start }
                val last = source.indexOfLast { it < annotation.end }
                if (first < 0 || last < first || last >= target.size) return null
                return EmphasisRange(target[first], target[last] + Character.charCount(displayBody.codePointAt(target[last])))
            }
            val insertions = annotations.filterNot { it.emphasis }.mapNotNull { annotation ->
                mapped(annotation)?.let { it.endExclusive to "〈${annotation.reading}〉" }
            }
            val withRuby = StringBuilder(displayBody).apply {
                insertions.asReversed().forEach { (offset, reading) -> insert(offset, reading) }
            }.toString()
            val dots = annotations.filter { it.emphasis }.mapNotNull(::mapped).map { range ->
                EmphasisRange(range.start + insertions.filter { it.first <= range.start }.sumOf { it.second.length },
                    range.endExclusive + insertions.filter { it.first < range.endExclusive }.sumOf { it.second.length })
            }
            return EmphasisText.encode(withRuby, dots)
        }

        fun annotateLines(lines: List<String>): List<String> = EmphasisText.lines(annotate(lines.joinToString("\n")))
    }

    private val marker = Regex("〈([^〈〉\\r\\n]+)〉")
    private val cache = object : LinkedHashMap<String, Text>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Text>?): Boolean = size > 128
    }

    fun parse(value: String, mode: Mode = Mode.ABOVE_BASE): Text {
        if (mode == Mode.INLINE) return Text(value)
        if (('〈' !in value || '〉' !in value) && !EmphasisText.has(value)) return Text(EmphasisText.plain(value))
        return synchronized(cache) { cache.getOrPut(value) { parseUncached(value) } }
    }

    fun body(value: String, mode: Mode = Mode.ABOVE_BASE): String = parse(value, mode).body
    fun hasRuby(value: String): Boolean = parse(value).annotations.isNotEmpty()

    /** Ellipsis may drop a reading, but must not leave half of its inline notation. */
    fun inlinePrefix(value: String, maximumCharacters: Int): String {
        var end = maximumCharacters.coerceIn(0, value.length)
        parse(value).markers.firstOrNull { end > it.start && end < it.end }?.let { end = it.start }
        if (end > 0 && end < value.length &&
            Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) {
            end--
        }
        return value.take(end)
    }

    /** Keep annotations out of punctuation/word-spacing transformations, then restore their text. */
    fun transform(value: String, transformBody: (String) -> String): String {
        val parsed = parse(value)
        return if (parsed.annotations.isEmpty()) transformBody(value) else parsed.annotate(transformBody(parsed.body))
    }

    private fun parseUncached(encoded: String): Text {
        val emphasized = EmphasisText.decode(encoded)
        val value = emphasized.text
        val body = StringBuilder()
        val annotations = mutableListOf<Annotation>()
        val markers = mutableListOf<Marker>()
        var cursor = 0
        marker.findAll(value).forEach { match ->
            body.append(value, cursor, match.range.first)
            val reading = match.groupValues[1].trim()
            val end = body.length
            val previous = if (end > 0) Character.codePointBefore(body, end) else -1
            val unmatchedOpening = body.lastIndexOf("〈") > body.lastIndexOf("〉")
            if (reading.isEmpty() || previous < 0 || !isWordPart(previous) || unmatchedOpening) {
                body.append(match.value)
            } else {
                val start = inferBaseStart(body.toString(), end, annotations.lastOrNull()?.end ?: 0)
                annotations += Annotation(start, end, reading)
                markers += Marker(match.range.first, match.range.last + 1)
            }
            cursor = match.range.last + 1
        }
        body.append(value, cursor, value.length)
        val visible = body.toString()
        fun shifted(offset: Int): Int = offset - markers.filter { it.end <= offset }.sumOf { it.end - it.start }
        val ranges = emphasized.ranges.filter { range -> markers.none { range.start < it.end && range.endExclusive > it.start } }
            .map { EmphasisRange(shifted(it.start), shifted(it.endExclusive)) }
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(visible) }
        var start = iterator.first()
        var end = iterator.next()
        val dots = mutableListOf<Annotation>()
        while (end != BreakIterator.DONE) {
            if (ranges.any { start >= it.start && end <= it.endExclusive } &&
                visible.substring(start,end).codePoints().anyMatch(Character::isLetterOrDigit) &&
                annotations.none { start < it.end && end > it.start }) dots += Annotation(start,end,"•",emphasis=true)
            start=end; end=iterator.next()
        }
        return Text(visible, (annotations + dots).sortedBy { it.start }, markers)
    }

    private fun inferBaseStart(text: String, end: Int, floor: Int): Int {
        var runStart = end
        while (runStart > floor) {
            val cp = Character.codePointBefore(text, runStart)
            if (!isWordPart(cp) && cp != '-'.code && cp != '\''.code) break
            runStart -= Character.charCount(cp)
        }
        val run = text.substring(runStart, end)
        val japanese = run.any { it in '\u3040'..'\u30ff' }
        val cjk = run.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val locale = when { japanese -> Locale.JAPANESE; cjk -> Locale.CHINESE; else -> Locale.ENGLISH }
        val words = BreakIterator.getWordInstance(locale).apply { setText(run) }
        var start = words.preceding(run.length).coerceAtLeast(0)
        // Preserve possessive phrases such as 私の原型 rather than centering only over 原型.
        if (japanese && start > 0 && run[start - 1] == 'の') {
            start = words.preceding(start - 1).coerceAtLeast(0)
        }
        if (cjk && !japanese) {
            // Some platform dictionaries leave an entire Han sentence as one word. Bound that
            // fallback at common grammatical separators; this remains a visual approximation.
            val separators = "的之是在到向與与和及"
            val separator = (run.length - 2 downTo start).firstOrNull { run[it] in separators }
            if (separator != null) start = separator + 1
            if (run.length - start > 8) start = run.length - 4
        }
        return (runStart + start).coerceIn(floor, end - 1)
    }

    private fun isWordPart(cp: Int): Boolean = Character.isLetterOrDigit(cp) ||
        Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt())

    private fun meaningfulOffsets(text: String): List<Int> = buildList {
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            if (isWordPart(cp)) add(offset)
            offset += Character.charCount(cp)
        }
    }
}
