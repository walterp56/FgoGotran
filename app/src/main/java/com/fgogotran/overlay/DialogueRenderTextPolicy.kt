package com.fgogotran.overlay

import kotlin.math.abs

/** Render-only cleanup for line breaks introduced by translation rather than the source dialogue. */
internal object DialogueRenderTextPolicy {
    private val hardLineBreakWithPadding = Regex("[ \\t\\u3000]*(?:\\r\\n|\\r|\\n)+[ \\t\\u3000]*")

    data class LineBounds(val top: Int, val bottom: Int)

    fun prepare(
        sourceText: String,
        translatedText: String,
        sourceLineBounds: List<LineBounds> = emptyList()
    ): String {
        if (!translatedText.hasHardLineBreak() ||
            !isSingleSourceLine(sourceText, sourceLineBounds)
        ) {
            return translatedText
        }
        return hardLineBreakWithPadding.replace(translatedText, "").trim()
    }

    private fun isSingleSourceLine(sourceText: String, sourceLineBounds: List<LineBounds>): Boolean {
        if (meaningfulLineCount(sourceText) == 1) return true
        return visualRowCount(sourceLineBounds) == 1
    }

    private fun meaningfulLineCount(text: String): Int = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .count { it.isNotBlank() }

    private fun visualRowCount(sourceLines: List<LineBounds>): Int {
        val rows = mutableListOf<LineBounds>()
        sourceLines
            .filter { it.bottom > it.top }
            .sortedBy { (it.top + it.bottom) / 2f }
            .forEach { line ->
                val rowIndex = rows.indexOfFirst { row -> sameVisualRow(row, line) }
                if (rowIndex < 0) {
                    rows += line
                } else {
                    val row = rows[rowIndex]
                    rows[rowIndex] = LineBounds(
                        top = minOf(row.top, line.top),
                        bottom = maxOf(row.bottom, line.bottom)
                    )
                }
            }
        return rows.size
    }

    private fun sameVisualRow(first: LineBounds, second: LineBounds): Boolean {
        val firstHeight = first.bottom - first.top
        val secondHeight = second.bottom - second.top
        val centerDistance = abs(
            (first.top + first.bottom) / 2f - (second.top + second.bottom) / 2f
        )
        return centerDistance <= minOf(firstHeight, secondHeight) * ROW_CENTER_TOLERANCE_RATIO
    }

    private fun String.hasHardLineBreak(): Boolean = '\n' in this || '\r' in this

    private const val ROW_CENTER_TOLERANCE_RATIO = 0.35f
}
