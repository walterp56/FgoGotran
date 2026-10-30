package com.fgogotran.ocr

import com.fgogotran.text.EmphasisRange
import java.text.BreakIterator
import java.util.Locale

/** Only actual ML Kit Symbol boxes are accepted here; element-width estimates are not anchors. */
internal object MlKitEmphasis {
    data class Symbol(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    fun ranges(
        text: String, left: Int, top: Int, right: Int, bottom: Int,
        symbols: List<Symbol>, dots: List<OcrEmphasisDot>
    ): List<EmphasisRange> {
        if (dots.isEmpty() || text.isEmpty() || bottom <= top || right <= left) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        val anchors = symbols.filter { it.right > it.left && it.bottom > it.top &&
            it.centerX >= left && it.centerX <= right && it.centerY >= top && it.centerY <= bottom }
            .distinct().sortedBy { it.centerX }.mapNotNull { symbol ->
                val value = symbol.text.filterNot(::space)
                if (value.isEmpty()) return@mapNotNull null
                iterator.setText(value); iterator.first()
                // A whole element/word cannot stand in for per-character Symbol geometry.
                if (iterator.next() != value.length) return emptyList()
                OcrCharacterPosition(value, symbol.centerX)
            }
        val offsets = text.indices.filterNot { space(text[it]) }
        val compact = offsets.joinToString("") { text[it].toString() }
        if (anchors.joinToString("") { it.text } != compact) return emptyList()
        return EmphasisRangeMapper.map(compact, anchors, dots, top, bottom).map {
            EmphasisRange(offsets[it.start], offsets[it.endExclusive - 1] + 1)
        }
    }

    private fun space(value: Char): Boolean = value.isWhitespace() || Character.isSpaceChar(value)
}
