package com.fgogotran.util

/**
 * Bulk pixel buffers owned by one immutable screenshot, never by the service or a detector.
 * Callers prepare only the ROIs they need; contained reads reuse the original buffer.
 * Access is sequential (including across coroutine dispatcher switches).
 */
internal class FramePixelReader(
    val width: Int,
    val height: Int,
    private val copyPixels: (IntArray, Bounds) -> Unit
) {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val isEmpty: Boolean get() = width == 0 || height == 0

        fun contains(other: Bounds): Boolean = left <= other.left && top <= other.top &&
            right >= other.right && bottom >= other.bottom

        fun clamp(width: Int, height: Int) = Bounds(
            left.coerceIn(0, width), top.coerceIn(0, height),
            right.coerceIn(0, width), bottom.coerceIn(0, height)
        )
    }

    class Region internal constructor(val bounds: Bounds, private val pixels: IntArray) {
        private val stride = bounds.width
        fun getPixel(x: Int, y: Int): Int =
            pixels[(y - bounds.top) * stride + x - bounds.left]
    }

    private val regions = mutableListOf<Region>()

    init {
        require(width >= 0 && height >= 0)
    }

    fun read(bounds: Bounds): Region {
        val clipped = bounds.clamp(width, height)
        regions.firstOrNull { it.bounds.contains(clipped) }?.let { return it }
        if (clipped.isEmpty) return Region(clipped, IntArray(0))

        val pixels = IntArray(clipped.width * clipped.height)
        copyPixels(pixels, clipped)
        val region = Region(clipped, pixels)
        // A later full choice ROI replaces the narrow discovery strips, not adds to them.
        regions.removeAll { clipped.contains(it.bounds) }
        regions.add(region)
        return region
    }

    fun clear() = regions.clear()
}
