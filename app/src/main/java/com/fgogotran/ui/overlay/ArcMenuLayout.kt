package com.fgogotran.ui.overlay

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Android dp geometry, independent of screenshot resolution and FGO's reference scale. */
internal data class ArcMenuRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
internal data class ArcMenuLayout(
    val capacity: Int,
    val actions: List<ArcMenuRect>,
    val navigation: ArcMenuRect
)

internal object ArcMenuGeometry {
    private const val GAP_DP = 4f
    private const val PREFERRED_RADIUS_DP = 92f

    fun layout(
        bounds: ArcMenuRect,
        anchorX: Float,
        anchorY: Float,
        pillWidth: Float,
        pillHeight: Float,
        navigationSize: Float
    ): ArcMenuLayout? {
        val openRight = bounds.right - anchorX >= anchorX - bounds.left
        for (capacity in 4 downTo 1) {
            val angles = if (capacity == 1) listOf(-45f, 45f) else
                List(capacity + 1) { -90f + it * 180f / capacity }
            val directedAngles = angles.map { if (openRight) it else it + 180f }
            place(bounds, anchorX, anchorY, pillWidth, pillHeight, navigationSize, directedAngles)?.let { return it }
        }
        // A very short landscape window can still hold one action and navigation on the arc's horizontal diameter.
        return place(bounds, anchorX, anchorY, pillWidth, pillHeight, navigationSize, listOf(180f, 0f))
    }

    private fun place(
        bounds: ArcMenuRect,
        anchorX: Float,
        anchorY: Float,
        pillWidth: Float,
        pillHeight: Float,
        navigationSize: Float,
        angles: List<Float>
    ): ArcMenuLayout? {
        val vectors = angles.map { angle ->
            val radians = angle * PI.toFloat() / 180f
            cos(radians) to sin(radians)
        }
        val sizes = angles.indices.map { index ->
            if (index == angles.lastIndex) navigationSize to navigationSize else pillWidth to pillHeight
        }
        var minimumRadius = 0f
        for (first in angles.indices) for (second in first + 1 until angles.size) {
            val dx = abs(vectors[first].first - vectors[second].first)
            val dy = abs(vectors[first].second - vectors[second].second)
            // A zero delta cannot provide separation on that axis.
            val horizontal = if (dx > 0.0001f)
                ((sizes[first].first + sizes[second].first) / 2f + GAP_DP) / dx else Float.POSITIVE_INFINITY
            val vertical = if (dy > 0.0001f)
                ((sizes[first].second + sizes[second].second) / 2f + GAP_DP) / dy else Float.POSITIVE_INFINITY
            minimumRadius = maxOf(minimumRadius, minOf(horizontal, vertical))
        }
        fun rectangles(radius: Float) = vectors.mapIndexed { index, (x, y) ->
            val (width, height) = sizes[index]
            ArcMenuRect(x * radius - width / 2f, y * radius - height / 2f,
                x * radius + width / 2f, y * radius + height / 2f)
        }
        fun fits(rects: List<ArcMenuRect>) =
            rects.maxOf { it.right } - rects.minOf { it.left } <= bounds.width &&
                rects.maxOf { it.bottom } - rects.minOf { it.top } <= bounds.height

        val preferred = rectangles(maxOf(PREFERRED_RADIUS_DP, minimumRadius))
        val rects = if (fits(preferred)) preferred else rectangles(minimumRadius)
        if (!fits(rects)) return null
        val x = anchorX.coerceIn(bounds.left - rects.minOf { it.left }, bounds.right - rects.maxOf { it.right })
        val y = anchorY.coerceIn(bounds.top - rects.minOf { it.top }, bounds.bottom - rects.maxOf { it.bottom })
        val placed = rects.map { ArcMenuRect(it.left + x, it.top + y, it.right + x, it.bottom + y) }
        return ArcMenuLayout(angles.size - 1, placed.dropLast(1), placed.last())
    }

    /** Keep the two existing groups separate, even if their last subpage is not full. */
    fun pageIndices(capacity: Int): List<List<Int>> {
        require(capacity in 1..4)
        return (0..3).toList().chunked(capacity) + (4..7).toList().chunked(capacity)
    }
}
