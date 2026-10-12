package com.fgogotran.crop

import com.fgogotran.overlay.FgoReferenceRect

internal data class CropRenderRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

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
        (sourceHeightPx * 0.82f).coerceIn(minimumRowTextSize, maximumTextSizePx)

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
