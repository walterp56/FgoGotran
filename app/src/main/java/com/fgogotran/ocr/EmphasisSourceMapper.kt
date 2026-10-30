package com.fgogotran.ocr

import com.fgogotran.text.EmphasisRange

/** Reattach known main-row ranges after source ruby formatting; ambiguous text is never guessed. */
internal object EmphasisSourceMapper {
    data class Line(val text: String, val ranges: List<EmphasisRange>)

    fun map(formatted: String, lines: List<Line>): List<EmphasisRange> {
        if (lines.none { it.ranges.isNotEmpty() }) return emptyList()
        val readings = Regex("〈[^〉]*〉").findAll(formatted).map { it.range }.toList()
        val offsets = formatted.indices.filter { i -> !formatted[i].isWhitespace() && readings.none { i in it } }
        val flat = offsets.joinToString("") { formatted[it].toString() }
        return lines.flatMap { line ->
            val inputOffsets = line.text.indices.filter { !line.text[it].isWhitespace() }
            val input = inputOffsets.joinToString("") { line.text[it].toString() }
            val start = flat.indexOf(input)
            if (input.isEmpty() || start < 0 || flat.indexOf(input,start+1) >= 0) emptyList() else
                line.ranges.mapNotNull { range ->
                    val first = inputOffsets.indexOfFirst { it >= range.start }
                    val last = inputOffsets.indexOfLast { it < range.endExclusive }
                    if (first < 0 || last < first) null else EmphasisRange(offsets[start+first], offsets[start+last]+1)
                }
        }.distinct().sortedBy { it.start }
    }
}
