package com.fgogotran.overlay

import kotlin.math.roundToInt

/** Platform-independent equivalent of FgoViewportLayout's centered reference mapping. */
data class FgoReferenceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object FgoViewportGeometry {
    fun viewport(width: Int, height: Int): FgoReferenceRect {
        if (width <= 0 || height <= 0) return FgoReferenceRect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        val scale = minOf(width / 1920f, height / 1080f)
        val left = (width - 1920f * scale) / 2f
        val top = (height - 1080f * scale) / 2f
        return FgoReferenceRect(left.roundToInt(), top.roundToInt(), (width - left).roundToInt(), (height - top).roundToInt())
    }

    fun map(rect: FgoReferenceRect, width: Int, height: Int): FgoReferenceRect {
        val scale = minOf(width.coerceAtLeast(0) / 1920f, height.coerceAtLeast(0) / 1080f)
        val left = (width - 1920f * scale) / 2f
        val top = (height - 1080f * scale) / 2f
        return FgoReferenceRect(
            (left + rect.left * scale).roundToInt(), (top + rect.top * scale).roundToInt(),
            (left + rect.right * scale).roundToInt(), (top + rect.bottom * scale).roundToInt()
        )
    }
}
