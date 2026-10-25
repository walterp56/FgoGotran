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

/** Compact mode saves decoration space, never label size or a 48 dp touch target. */
internal enum class ArcMenuItemStyle(
    val iconSizeDp: Float,
    val labelGapDp: Float,
    val verticalPaddingDp: Float,
    private val minimumHeightDp: Float
) {
    STANDARD(20f, 3f, 6f, 54f),
    COMPACT(18f, 2f, 4f, 48f);

    fun heightDp(labelHeightDp: Float): Float =
        maxOf(minimumHeightDp, iconSizeDp + labelGapDp + labelHeightDp + 2f * verticalPaddingDp + 2f)
}

internal object ArcMenuGeometry {
    private const val MAX_CAPACITY = 5
    private const val GAP_DP = 4f
    private const val PREFERRED_RADIUS_DP = 92f

    fun layout(
        bounds: ArcMenuRect,
        anchorX: Float,
        anchorY: Float,
        pillWidth: Float,
        pillHeight: Float,
        navigationSize: Float,
        allowFlattening: Boolean = false
    ): ArcMenuLayout? {
        val openRight = bounds.right - anchorX >= anchorX - bounds.left
        for (capacity in MAX_CAPACITY downTo 1) {
            val angles = if (capacity == 1) listOf(-45f, 45f) else
                List(capacity + 1) { -90f + it * 180f / capacity }
            val directedAngles = angles.map { if (openRight) it else it + 180f }
            place(bounds, anchorX, anchorY, pillWidth, pillHeight, navigationSize, directedAngles,
                allowFlattening)?.let { return it }
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
        angles: List<Float>,
        allowFlattening: Boolean = false
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
        fun rectangles(radiusX: Float, radiusY: Float = radiusX) = vectors.mapIndexed { index, (x, y) ->
            val (width, height) = sizes[index]
            ArcMenuRect(x * radiusX - width / 2f, y * radiusY - height / 2f,
                x * radiusX + width / 2f, y * radiusY + height / 2f)
        }
        fun fits(rects: List<ArcMenuRect>) =
            rects.maxOf { it.right } - rects.minOf { it.left } <= bounds.width &&
                rects.maxOf { it.bottom } - rects.minOf { it.top } <= bounds.height

        val preferred = rectangles(maxOf(PREFERRED_RADIUS_DP, minimumRadius))
        val circular = rectangles(minimumRadius)
        val rects = when {
            fits(preferred) -> preferred
            fits(circular) -> circular
            !allowFlattening -> return null
            else -> {
                // Constrain the vertical radius to the available height. Equal-x pairs must
                // still separate vertically; other pairs can use spare horizontal space.
                if (sizes.any { it.second > bounds.height }) return null
                var maximumY = Float.POSITIVE_INFINITY
                var minimumY = 0f
                for (first in angles.indices) for (second in first + 1 until angles.size) {
                    val dx = abs(vectors[first].first - vectors[second].first)
                    val dy = abs(vectors[first].second - vectors[second].second)
                    if (dy > 0.0001f) {
                        val halfHeights = (sizes[first].second + sizes[second].second) / 2f
                        maximumY = minOf(maximumY, (bounds.height - halfHeights) / dy)
                        if (dx <= 0.0001f) minimumY = maxOf(minimumY, (halfHeights + GAP_DP) / dy)
                    }
                }
                if (maximumY < minimumY || maximumY >= minimumRadius) return null
                fun flattened(radiusY: Float): List<ArcMenuRect>? {
                    var radiusX = radiusY
                    for (first in angles.indices) for (second in first + 1 until angles.size) {
                        val dx = abs(vectors[first].first - vectors[second].first)
                        val dy = abs(vectors[first].second - vectors[second].second)
                        val vertical = if (dy > 0.0001f)
                            ((sizes[first].second + sizes[second].second) / 2f + GAP_DP) / dy else Float.POSITIVE_INFINITY
                        if (radiusY >= vertical) continue
                        if (dx <= 0.0001f) return null
                        radiusX = maxOf(radiusX, ((sizes[first].first + sizes[second].first) / 2f + GAP_DP) / dx)
                    }
                    return rectangles(radiusX, radiusY).takeIf(::fits)
                }
                // Prefer a low oval; a narrower window may need more height to avoid extra pages.
                flattened(maxOf(minimumY, minOf(PREFERRED_RADIUS_DP, maximumY)))
                    ?: flattened(maximumY) ?: return null
            }
        }
        val x = anchorX.coerceIn(bounds.left - rects.minOf { it.left }, bounds.right - rects.maxOf { it.right })
        val y = anchorY.coerceIn(bounds.top - rects.minOf { it.top }, bounds.bottom - rects.maxOf { it.bottom })
        val placed = rects.map { ArcMenuRect(it.left + x, it.top + y, it.right + x, it.bottom + y) }
        return ArcMenuLayout(angles.size - 1, placed.dropLast(1), placed.last())
    }

    /** Preserve group/action order while balancing overflow (five at capacity four becomes 3 + 2). */
    fun pageIndices(capacity: Int, groupSizes: List<Int> = listOf(4, 4)): List<List<Int>> {
        require(capacity in 1..MAX_CAPACITY)
        require(groupSizes.isNotEmpty() && groupSizes.all { it > 0 })
        var offset = 0
        return groupSizes.flatMap { size ->
            val pageCount = (size - 1) / capacity + 1
            val smallerPageSize = size / pageCount
            val largerPages = size % pageCount
            List(pageCount) { page ->
                val pageSize = smallerPageSize + if (page < largerPages) 1 else 0
                List(pageSize) { offset++ }
            }
        }
    }
}
