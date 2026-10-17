package com.fgogotran.crop

import com.fgogotran.overlay.FgoReferenceRect

internal data class CropRenderRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: CropRenderRect) = CropRenderRect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom)
    )
}

internal data class CropRowGeometry(
    val source: CropRenderRect,
    val area: CropRenderRect,
    val paintedBounds: CropRenderRect
)

/** Ink bounds are relative to the baseline; preferredBaseline retains the existing row anchor. */
internal data class CropRowMetrics(val inkBounds: CropRenderRect, val preferredBaseline: Float)

internal data class CropRowFit(
    val textSize: Float,
    val baseline: Float,
    val inkBounds: CropRenderRect,
    val paintedBounds: CropRenderRect,
    val shadowPx: Float
)

/** Reference-pixel limits only: source bounds and heights are already crop-local physical pixels. */
internal class CropRenderSizing(private val referenceScale: Float) {
    init {
        require(referenceScale.isFinite() && referenceScale > 0f)
    }

    fun px(referencePixels: Float): Float = referencePixels * referenceScale

    private fun boundedPx(value: Float, minimum: Float, maximum: Float): Float =
        value.coerceIn(px(minimum), px(maximum))

    val minimumRowTextSize: Float get() = px(10f)

    fun sourceTextSize(sourceHeightPx: Float): Float = boundedPx(sourceHeightPx * 1.1f, 10f, 72f)

    fun minimumSourceTextSize(sourceHeightPx: Float, maximumTextSizePx: Float): Float =
        (sourceHeightPx * 0.82f).coerceIn(minOf(minimumRowTextSize, maximumTextSizePx), maximumTextSizePx)

    /** Compute once per Crop. Only rows whose painted horizontal spans overlap compete vertically. */
    fun safeRowAreas(rows: List<CropRowGeometry>, width: Int, height: Int): List<CropRenderRect> {
        val areas = rows.map { row ->
            // The source centre remains inside its area even for a crop-edge row.
            CropRenderRect(
                0f, minOf(row.area.top, row.source.top).coerceAtLeast(0f),
                width.toFloat(), maxOf(row.area.bottom, row.source.bottom).coerceAtMost(height.toFloat())
            )
        }.toMutableList()
        for (first in rows.indices) for (second in first + 1 until rows.size) {
            val a = rows[first]
            val b = rows[second]
            if (a.paintedBounds.width <= 0f || b.paintedBounds.width <= 0f ||
                a.paintedBounds.right <= b.paintedBounds.left || b.paintedBounds.right <= a.paintedBounds.left
            ) continue
            val distance = kotlin.math.abs(a.source.centerY - b.source.centerY)
            // Same-baseline labels/values are columns, not a pair of stacked rows.
            val separateColumns = a.source.right <= b.source.left || b.source.right <= a.source.left
            if (distance == 0f || separateColumns && distance <= minOf(a.source.height, b.source.height) * 0.25f) continue
            val upper = if (a.source.centerY < b.source.centerY) first else second
            val lower = if (upper == first) second else first
            val above = rows[upper]
            val below = rows[lower]
            val gap = minOf(px(2f), distance * 0.25f)
            var boundary = (above.source.centerY + below.source.centerY) / 2f
            // Keep already-separated text, notably English, in its existing position when possible.
            val freeTop = maxOf(above.paintedBounds.bottom + gap / 2f, above.source.centerY + gap)
            val freeBottom = minOf(below.paintedBounds.top - gap / 2f, below.source.centerY - gap)
            if (freeTop <= freeBottom) boundary = boundary.coerceIn(freeTop, freeBottom)
            areas[upper] = areas[upper].copy(bottom = minOf(areas[upper].bottom, boundary - gap / 2f))
            areas[lower] = areas[lower].copy(top = maxOf(areas[lower].top, boundary + gap / 2f))
        }
        return areas
    }

    fun paintedBounds(
        ink: CropRenderRect,
        x: Float,
        baseline: Float,
        shadow: Float,
        margin: Float = px(0.5f)
    ): CropRenderRect {
        if (ink.width == 0f && ink.height == 0f) return CropRenderRect(x, baseline, x, baseline)
        return CropRenderRect(
            x + ink.left - shadow - margin, baseline + ink.top - shadow - margin,
            x + ink.right + shadow * 2f + margin, baseline + ink.bottom + shadow * 2f + margin
        )
    }

    /** Largest measured fit, never larger than the current preferred size. No accepted oversize floor. */
    fun fitRow(
        area: CropRenderRect,
        x: Float,
        preferredSize: Float,
        measure: (Float) -> CropRowMetrics
    ): CropRowFit? {
        if (area.width <= 0f || area.height <= 0f || preferredSize <= 0f) return null
        val shadow = minOf(px(2f), area.height / 8f)
        val margin = minOf(px(0.5f), area.height / 16f)
        fun atSize(size: Float): CropRowFit? {
            val metrics = measure(size)
            val ink = metrics.inkBounds
            val empty = ink.width == 0f && ink.height == 0f
            val firstBaseline = area.top - ink.top + (if (empty) 0f else shadow + margin)
            val lastBaseline = area.bottom - ink.bottom - (if (empty) 0f else shadow * 2f + margin)
            if (firstBaseline > lastBaseline) return null
            val baseline = metrics.preferredBaseline.coerceIn(firstBaseline, lastBaseline)
            val painted = paintedBounds(ink, x, baseline, shadow, margin)
            if (painted.left < area.left || painted.right > area.right) return null
            return CropRowFit(size, baseline, ink, painted, shadow)
        }
        atSize(preferredSize)?.let { return it }
        var low = minOf(px(0.1f), preferredSize)
        var high = preferredSize
        var best = atSize(low) ?: return null
        repeat(14) {
            val size = (low + high) / 2f
            val fit = atSize(size)
            if (fit != null) {
                best = fit
                low = size
            } else {
                high = size
            }
        }
        return best
    }

    fun panelPadding(width: Int, height: Int): Float =
        boundedPx(minOf(width, height) * 0.06f, 6f, 24f)
            .coerceAtMost((minOf(width, height) - 1f).coerceAtLeast(0f) / 2f)

    fun maximumPanelTextSize(maxWidthPx: Float, maxHeightPx: Float): Float =
        maxOf(px(12f), minOf(maxHeightPx * 0.92f, maxWidthPx * 0.72f, px(180f)))

    fun cornerRadius(widthPx: Float, heightPx: Float): Float =
        boundedPx(minOf(widthPx, heightPx) * 0.12f, 4f, 9f)

    private fun horizontalInset(width: Int): Float =
        boundedPx(width * 0.03f, 5f, 24f).coerceAtMost((width - 1f).coerceAtLeast(0f) / 2f)

    private fun verticalInset(height: Int): Float =
        boundedPx(height * 0.035f, 3f, 14f).coerceAtMost((height - 1f).coerceAtLeast(0f) / 2f)

    fun textArea(source: CropRenderRect, width: Int, height: Int): CropRenderRect {
        val insetX = horizontalInset(width)
        val insetY = verticalInset(height)
        val sourceHeight = source.height.coerceAtLeast(1f)
        val sourcePadX = boundedPx(sourceHeight * 0.24f, 4f, 16f)
        val sourcePadY = boundedPx(sourceHeight * 0.14f, 3f, 9f)
        val desiredHeight = (sourceHeight + sourcePadY * 2f).coerceAtLeast(px(18f))
        val leftLimit = (width - insetX - 1f).coerceAtLeast(insetX)
        val topLimit = (height - insetY - desiredHeight).coerceAtLeast(insetY)
        val left = (source.left - sourcePadX).coerceIn(insetX, leftLimit)
        val top = (source.top - sourcePadY).coerceIn(insetY, topLimit)
        return CropRenderRect(
            left,
            top,
            (source.right + sourcePadX).coerceAtMost(width - insetX).coerceAtLeast(left + 1f),
            (top + desiredHeight).coerceAtMost(height - insetY).coerceAtLeast(top + 1f)
        )
    }

    fun maximumTextWidth(area: CropRenderRect, width: Int): Float =
        maxOf(area.width, width - horizontalInset(width) - area.left).coerceAtLeast(1f)

    fun clearBox(
        source: CropRenderRect,
        area: CropRenderRect,
        renderedWidthPx: Float,
        lineHeightPx: Float,
        width: Int,
        height: Int
    ): CropRenderRect {
        val sourceHeight = source.height.coerceAtLeast(1f)
        val sourcePadX = boundedPx(sourceHeight * 0.24f, 4f, 16f)
        val sourcePadY = boundedPx(sourceHeight * 0.14f, 3f, 9f)
        val textPadX = boundedPx(lineHeightPx * 0.16f, 4f, 12f)
        val textPadY = boundedPx(lineHeightPx * 0.08f, 3f, 8f)
        val left = minOf(source.left - sourcePadX, area.left - textPadX).coerceIn(0f, width - 1f)
        val top = minOf(source.top - sourcePadY, area.top - textPadY).coerceIn(0f, height - 1f)
        return CropRenderRect(
            left,
            top,
            maxOf(source.right + sourcePadX, area.left + renderedWidthPx + textPadX)
                .coerceAtMost(width.toFloat()).coerceAtLeast(left + 1f),
            maxOf(source.bottom + sourcePadY, area.top + lineHeightPx + textPadY)
                .coerceAtMost(height.toFloat()).coerceAtLeast(top + 1f)
        )
    }
}

/** Keep UI status panels on screen even when the requested crop is small or at a screen edge. */
internal fun cropStatusPanelBounds(
    requested: FgoReferenceRect,
    screenWidth: Int,
    screenHeight: Int,
    minimumWidth: Int,
    minimumHeight: Int
): FgoReferenceRect {
    val width = maxOf(requested.width, minimumWidth, 1).coerceAtMost(screenWidth.coerceAtLeast(1))
    val height = maxOf(requested.height, minimumHeight, 1).coerceAtMost(screenHeight.coerceAtLeast(1))
    val left = requested.left.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
    val top = requested.top.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
    return FgoReferenceRect(left, top, left + width, top + height)
}
