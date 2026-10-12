package com.fgogotran.overlay

import kotlin.math.roundToInt

/** Platform-independent equivalent of FgoViewportLayout's centered reference mapping. */
data class FgoReferenceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object FgoViewportGeometry {
    /** Physical pixels per reference pixel; game rendering must not depend on Android density. */
    fun referenceScale(width: Int, height: Int): Float =
        minOf(width.coerceAtLeast(0) / 1920f, height.coerceAtLeast(0) / 1080f)

    fun viewport(width: Int, height: Int): FgoReferenceRect {
        if (width <= 0 || height <= 0) return FgoReferenceRect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        val scale = referenceScale(width, height)
        val left = (width - 1920f * scale) / 2f
        val top = (height - 1080f * scale) / 2f
        return FgoReferenceRect(left.roundToInt(), top.roundToInt(), (width - left).roundToInt(), (height - top).roundToInt())
    }

    fun map(rect: FgoReferenceRect, width: Int, height: Int): FgoReferenceRect {
        val scale = referenceScale(width, height)
        val left = (width - 1920f * scale) / 2f
        val top = (height - 1080f * scale) / 2f
        return FgoReferenceRect(
            (left + rect.left * scale).roundToInt(), (top + rect.top * scale).roundToInt(),
            (left + rect.right * scale).roundToInt(), (top + rect.bottom * scale).roundToInt()
        )
    }
}
