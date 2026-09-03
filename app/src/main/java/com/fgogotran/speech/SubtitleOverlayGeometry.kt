package com.fgogotran.speech

internal data class SubtitleOverlayPosition(
    val x: Int,
    val y: Int
)

/** Pure geometry helpers so overlay placement can be verified without Android UI classes. */
internal object SubtitleOverlayGeometry {
    fun maximumTextWidth(
        screenWidth: Int,
        insetLeft: Int,
        insetRight: Int,
        coverage: Float
    ): Int {
        val availableWidth = (screenWidth - insetLeft - insetRight).coerceAtLeast(1)
        return (availableWidth * coverage.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
    }

    fun defaultPosition(
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        topMargin: Int
    ): SubtitleOverlayPosition {
        val availableWidth = (screenWidth - insetLeft - insetRight).coerceAtLeast(0)
        val centeredX = insetLeft + (availableWidth - viewWidth).coerceAtLeast(0) / 2
        return clampPosition(
            x = centeredX,
            y = insetTop + topMargin.coerceAtLeast(0),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            insetLeft = insetLeft,
            insetTop = insetTop,
            insetRight = insetRight,
            insetBottom = insetBottom
        )
    }

    fun clampPosition(
        x: Int,
        y: Int,
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int
    ): SubtitleOverlayPosition {
        val minX = insetLeft.coerceIn(0, screenWidth.coerceAtLeast(0))
        val minY = insetTop.coerceIn(0, screenHeight.coerceAtLeast(0))
        val maxX = (screenWidth - insetRight.coerceAtLeast(0) - viewWidth.coerceAtLeast(0))
            .coerceAtLeast(minX)
        val maxY = (screenHeight - insetBottom.coerceAtLeast(0) - viewHeight.coerceAtLeast(0))
            .coerceAtLeast(minY)
        return SubtitleOverlayPosition(
            x = x.coerceIn(minX, maxX),
            y = y.coerceIn(minY, maxY)
        )
    }
}
