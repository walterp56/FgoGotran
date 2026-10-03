package com.fgogotran.ocr

import kotlin.math.abs

/** Geometry only: no recognition, character offsets, or changes to the ruby insertion format. */
internal object DialogueRubyLayout {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(1)
        val height: Int get() = (bottom - top).coerceAtLeast(1)
        val centerY: Float get() = (top + bottom) / 2f
    }

    /**
     * Prefer the larger coherent rows below small annotations. Counting boxes alone lets many
     * tiny ruby fragments outweigh the main row. Width/height support also limits tall outliers.
     * Without an upper/lower pair, retain the existing percentile behaviour.
     */
    fun heightReference(boxes: List<Box>): Int {
        if (boxes.isEmpty()) return 0
        val heights = boxes.map { it.height }.sorted()
        val fallback = heights[(heights.size * 3 / 4).coerceAtMost(heights.lastIndex)]
        val mainCandidates = boxes.filter { main ->
            boxes.any { upper ->
                upper.height <= main.height * RUBY_HEIGHT_RATIO &&
                    main.top >= upper.bottom - main.height * OVERLAP_TOLERANCE_RATIO &&
                    main.top - upper.bottom <= main.height * MAX_UPPER_GAP_RATIO &&
                    minOf(main.right, upper.right) - maxOf(main.left, upper.left) >=
                        minOf(main.width, upper.width) * MIN_HORIZONTAL_OVERLAP_RATIO
            }
        }.sortedBy { it.height }
        if (mainCandidates.isEmpty()) return fallback

        val bestGroup = mainCandidates.map { anchor ->
            mainCandidates.filter { abs(it.height - anchor.height) <= anchor.height * HEIGHT_CLUSTER_TOLERANCE }
        }.maxWithOrNull(
            compareBy<List<Box>> { it.size }.thenBy { group ->
                group.sumOf { (it.width.toDouble() / it.height).coerceAtMost(MAX_ROW_WEIGHT) }
            }
        ).orEmpty()
        val mainHeights = bestGroup.map { it.height }.sorted()
        return mainHeights[mainHeights.size / 2]
    }

    /** Group upper bands first, then order each band left-to-right despite OCR Y jitter. */
    fun orderedRows(boxes: List<Box>, heightReference: Int): List<List<Int>> {
        val rows = mutableListOf<MutableList<Int>>()
        boxes.indices.sortedWith(compareBy({ boxes[it].centerY }, { boxes[it].left })).forEach { index ->
            val box = boxes[index]
            val row = rows.lastOrNull()
            val first = row?.firstOrNull()?.let(boxes::get)
            val tolerance = if (first == null) 0f else minOf(
                heightReference * ROW_MAIN_HEIGHT_TOLERANCE,
                maxOf(first.height, box.height) * ROW_RUBY_HEIGHT_TOLERANCE
            )
            if (row != null && first != null && abs(box.centerY - first.centerY) <= tolerance) {
                row += index
            } else {
                rows += mutableListOf(index)
            }
        }
        return rows.map { row -> row.sortedWith(compareBy({ boxes[it].left }, { boxes[it].top })) }
    }

    private const val RUBY_HEIGHT_RATIO = 0.72f
    private const val OVERLAP_TOLERANCE_RATIO = 0.20f
    private const val MAX_UPPER_GAP_RATIO = 0.90f
    private const val MIN_HORIZONTAL_OVERLAP_RATIO = 0.20f
    private const val HEIGHT_CLUSTER_TOLERANCE = 0.20f
    private const val MAX_ROW_WEIGHT = 12.0
    private const val ROW_MAIN_HEIGHT_TOLERANCE = 0.35f
    private const val ROW_RUBY_HEIGHT_TOLERANCE = 0.60f
}
