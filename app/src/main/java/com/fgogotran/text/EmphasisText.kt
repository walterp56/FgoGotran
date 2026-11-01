package com.fgogotran.text

/** UTF-16 offsets in [EmphasizedText.text], never pixel positions or translated word counts. */
data class EmphasisRange(val start: Int, val endExclusive: Int)

data class EmphasizedText(val text: String, val ranges: List<EmphasisRange> = emptyList())

/**
 * Request-local annotation codec. Private delimiters exist only inside the translation pipeline;
 * the provider sees ⟦…⟧ and public results expose plain text plus ranges. Literal delimiters are
 * escaped, so ordinary story brackets can never silently become display instructions.
 */
object EmphasisText {
    const val CACHE_VERSION = "emphasis-v1"
    const val WIRE_OPEN = '⟦'
    const val WIRE_CLOSE = '⟧'
    private const val OPEN = '\uE100'
    private const val CLOSE = '\uE101'
    private const val ESCAPE = '\uE102'

    fun literal(text: String): String = if (OPEN !in text && CLOSE !in text && ESCAPE !in text) text else buildString {
        text.forEach { if (it == OPEN || it == CLOSE || it == ESCAPE) append(ESCAPE); append(it) }
    }

    fun encode(text: String, ranges: List<EmphasisRange>): String {
        // A real occurrence of the wire syntax wins over decoration for this field.
        if (WIRE_OPEN in text || WIRE_CLOSE in text) return literal(text)
        return pack(text, ranges)
    }

    private fun pack(text: String, ranges: List<EmphasisRange>): String {
        val safe = ranges.filter { it.start >= 0 && it.endExclusive <= text.length &&
            it.start < it.endExclusive && boundary(text, it.start) && boundary(text, it.endExclusive) }
            .sortedBy { it.start }.fold(mutableListOf<EmphasisRange>()) { result, range ->
                val last = result.lastOrNull()
                if (last != null && range.start <= last.endExclusive) {
                    result[result.lastIndex] = last.copy(endExclusive = maxOf(last.endExclusive, range.endExclusive))
                } else result += range
                result
            }
        if (safe.isEmpty()) return literal(text)
        return buildString {
            var cursor = 0
            safe.forEach { range ->
                append(literal(text.substring(cursor, range.start)))
                append(OPEN); append(literal(text.substring(range.start, range.endExclusive))); append(CLOSE)
                cursor = range.endExclusive
            }
            append(literal(text.substring(cursor)))
        }
    }

    fun decode(value: String): EmphasizedText {
        if (OPEN !in value && CLOSE !in value && ESCAPE !in value) return EmphasizedText(value)
        val body = StringBuilder()
        val ranges = mutableListOf<EmphasisRange>()
        var start = -1
        var invalid = false
        var index = 0
        while (index < value.length) {
            when (val char = value[index++]) {
                ESCAPE -> if (index < value.length) body.append(value[index++]) else body.append(char)
                OPEN -> if (start >= 0) invalid = true else start = body.length
                CLOSE -> if (start < 0) invalid = true else {
                    if (start < body.length) ranges += EmphasisRange(start, body.length) else invalid = true
                    start = -1
                }
                else -> body.append(char)
            }
        }
        return EmphasizedText(body.toString(), if (invalid || start >= 0) emptyList() else ranges)
    }

    /** Offsets to delete from a styled source without flattening Android spans. */
    fun controlOffsets(value: String): List<Int> = buildList {
        var i = 0
        while (i < value.length) {
            when (value[i]) {
                ESCAPE -> { add(i); i += 2 }
                OPEN, CLOSE -> { add(i); i++ }
                else -> i++
            }
        }
    }

    fun has(value: String): Boolean = OPEN in value && decode(value).ranges.isNotEmpty()
    fun plain(value: String): String = decode(value).text

    fun wire(value: String): String {
        if (OPEN !in value && CLOSE !in value && ESCAPE !in value) return value
        val parsed = decode(value)
        if (parsed.ranges.isEmpty()) return parsed.text
        return buildString {
            var cursor = 0
            parsed.ranges.forEach {
                append(parsed.text, cursor, it.start); append(WIRE_OPEN)
                append(parsed.text, it.start, it.endExclusive); append(WIRE_CLOSE)
                cursor = it.endExclusive
            }
            append(parsed.text, cursor, parsed.text.length)
        }
    }

    /** Called only after JSON field extraction, and only the current source can enable parsing. */
    fun fromResponse(source: String, response: String, annotationRequest: Boolean = has(source)): String {
        if (!has(source)) {
            val sourceLiteral = plain(source)
            val unexpectedControls = annotationRequest && WIRE_OPEN !in sourceLiteral && WIRE_CLOSE !in sourceLiteral
            return literal(if (unexpectedControls) response.filterNot { it == WIRE_OPEN || it == WIRE_CLOSE } else response)
        }
        val internal = buildString {
            response.forEach { char ->
                when (char) {
                    WIRE_OPEN -> append(OPEN)
                    WIRE_CLOSE -> append(CLOSE)
                    else -> append(literal(char.toString()))
                }
            }
        }
        val parsed = decode(internal)
        // Malformed decoration is optional: keep all words, no retry and no Japanese fallback.
        return pack(parsed.text, parsed.ranges)
    }

    fun lines(value: String): List<String> {
        val parsed = decode(value)
        var offset = 0
        return parsed.text.split('\n').map { line ->
            val start = offset; offset += line.length + 1
            pack(line, parsed.ranges.mapNotNull {
                val a = maxOf(start,it.start); val b = minOf(start+line.length,it.endExclusive)
                if (a < b) EmphasisRange(a-start,b-start) else null
            })
        }
    }

    fun transform(value: String, operation: (String) -> String): String {
        if (OPEN !in value && ESCAPE !in value && CLOSE !in value) return operation(value)
        val parsed = decode(value)
        val changed = operation(parsed.text)
        return pack(changed, remap(parsed.text, changed, parsed.ranges))
    }

    /** Conservative monotonic edit alignment; ambiguous interior boundaries lose decoration only. */
    fun remap(before: String, after: String, ranges: List<EmphasisRange>): List<EmphasisRange> {
        if (before == after) return ranges
        if (ranges.isEmpty() || before.length.toLong() * after.length > 1_000_000) return emptyList()
        val n = before.length; val m = after.length
        val costs = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) costs[i][0] = i
        for (j in 0..m) costs[0][j] = j
        for (i in 1..n) for (j in 1..m) costs[i][j] = minOf(
            costs[i - 1][j] + 1, costs[i][j - 1] + 1,
            costs[i - 1][j - 1] + if (before[i - 1] == after[j - 1]) 0 else 1)
        val low = IntArray(n + 1) { Int.MAX_VALUE }
        val high = IntArray(n + 1) { -1 }
        val seen = Array(n + 1) { BooleanArray(m + 1) }
        val pending = java.util.ArrayDeque<Pair<Int, Int>>()
        pending.add(n to m)
        while (pending.isNotEmpty()) {
            val (i, j) = pending.removeLast()
            if (seen[i][j]) continue
            seen[i][j] = true; low[i] = minOf(low[i], j); high[i] = maxOf(high[i], j)
            if (i > 0 && j > 0 && costs[i][j] == costs[i - 1][j - 1] +
                if (before[i - 1] == after[j - 1]) 0 else 1) pending.add(i - 1 to j - 1)
            if (i > 0 && costs[i][j] == costs[i - 1][j] + 1) pending.add(i - 1 to j)
            if (j > 0 && costs[i][j] == costs[i][j - 1] + 1) pending.add(i to j - 1)
        }
        return ranges.mapNotNull { range ->
            if (range.start !in 0..n || range.endExclusive !in 0..n) return@mapNotNull null
            val start = high[range.start]; val end = low[range.endExclusive]
            if (start < 0 || end <= start || !boundary(after, start) || !boundary(after, end)) null
            else if ((range.start != 0 && low[range.start] != high[range.start]) ||
                (range.endExclusive != n && low[range.endExclusive] != high[range.endExclusive])) null
            else EmphasisRange(start, end)
        }
    }

    private fun boundary(text: String, offset: Int): Boolean = offset == 0 || offset == text.length ||
        (!Character.isLowSurrogate(text[offset]) && Character.getType(text[offset]).toInt() !in
            setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt()))
}
