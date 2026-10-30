package com.fgogotran.ocr

import com.fgogotran.text.EmphasisRange
import kotlin.math.abs

/** Annotation evidence from the original pixels; never sent through the text recognizer. */
internal data class OcrEmphasisDot(
    val centerX: Float, val mainTop: Float, val mainBottom: Float,
    /** Whole main-ink band from a verified aligned dot run, independent of DB box expansion. */
    val verifiedRowTop: Float? = null, val verifiedRowBottom: Float? = null
)

internal object EmphasisRangeMapper {
    fun map(text: String, positions: List<OcrCharacterPosition>, dots: List<OcrEmphasisDot>,
            top: Int, bottom: Int): List<EmphasisRange> {
        if (dots.isEmpty() || positions.isEmpty() || positions.joinToString("") { it.text } != text) return emptyList()
        val boxHeight = (bottom - top).toFloat()
        if (boxHeight <= 0 || positions.zipWithNext().any { (a,b) -> b.centerX <= a.centerX }) return emptyList()
        val marked = mutableSetOf<Int>()
        for (dot in dots) {
            val evidenceHeight = dot.mainBottom - dot.mainTop
            val verified = dot.verifiedRowTop != null && dot.verifiedRowBottom != null
            val referenceTop = dot.verifiedRowTop ?: top.toFloat()
            val referenceBottom = dot.verifiedRowBottom ?: bottom.toFloat()
            val height = referenceBottom - referenceTop
            if (height <= 0f) continue
            if (verified) {
                // DB may include padding or one unmasked upper dot. Match the known ink row
                // to this recognized line, not each short kana's ink to that expanded box.
                val overlap = minOf(bottom.toFloat(), referenceBottom) - maxOf(top.toFloat(), referenceTop)
                if (overlap < height * .65f ||
                    abs((top + bottom) / 2f - (referenceTop + referenceBottom) / 2f) > maxOf(boxHeight,height)*.35f) continue
            }
            // A detached stroke belonging to 主/文 can resemble a dot. Its lower evidence starts
            // inside the recognized glyph, whereas a real annotation sits above the whole row.
            if (!verified && (evidenceHeight < height * .75f || abs(dot.mainTop-referenceTop) > height * .18f ||
                abs(dot.mainBottom-referenceBottom) > height * .25f)) continue
            val candidates = positions.indices.filter { index ->
                val position = positions[index]
                if (position.text.codePoints().noneMatch(Character::isLetterOrDigit)) false else {
                    val previous = positions.getOrNull(index - 1)?.centerX
                    val next = positions.getOrNull(index + 1)?.centerX
                    val cell = listOfNotNull(previous?.let { position.centerX-it }, next?.let { it-position.centerX })
                        .minOrNull() ?: height.toFloat()
                    abs(dot.centerX-position.centerX) <= minOf(height * .3f, cell * .3f)
                }
            }
            if (candidates.size == 1) marked += candidates.single()
        }
        var offset = 0
        val ranges = mutableListOf<EmphasisRange>()
        positions.forEachIndexed { index, token ->
            val end = offset + token.text.length
            if (index in marked) {
                val last = ranges.lastOrNull()
                if (last?.endExclusive == offset) ranges[ranges.lastIndex] = last.copy(endExclusive=end)
                else ranges += EmphasisRange(offset,end)
            }
            offset=end
        }
        return ranges
    }
}
