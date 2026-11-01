package com.fgogotran.overlay

/** Fits ruby before its fixed visible endpoint without changing any body-text geometry. */
internal object RubyEndAlignment {
    data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class Placement(val textSize: Float, val drawX: Float, val ink: InkBounds)

    /** Joint dialogue fitting rejects the whole size instead of resizing an individual ruby. */
    fun exact(textSize: Float, endpoint: Float, minimumLeft: Float, maximumHeight: Float, ink: InkBounds): Placement? {
        if (!textSize.isFinite() || textSize <= 0f || !endpoint.isFinite() || !minimumLeft.isFinite() ||
            !maximumHeight.isFinite() || !ink.width.isFinite() || !ink.height.isFinite() ||
            ink.width <= 0f || ink.height <= 0f ||
            ink.width > endpoint - minimumLeft || ink.height > maximumHeight) return null
        return Placement(textSize, endpoint - ink.right, ink)
    }

    fun fit(
        preferredTextSize: Float,
        endpoint: Float,
        minimumLeft: Float,
        maximumHeight: Float,
        measure: (Float) -> InkBounds
    ): Placement? {
        val availableWidth = endpoint - minimumLeft
        if (!preferredTextSize.isFinite() || !availableWidth.isFinite() || !maximumHeight.isFinite() ||
            preferredTextSize < 1f || availableWidth < 1f || maximumHeight < 1f
        ) return null

        var size = preferredTextSize
        repeat(8) { attempt ->
            val ink = measure(size)
            if (ink.width <= 0f || ink.height <= 0f) return null
            if (ink.width <= availableWidth && ink.height <= maximumHeight) {
                // Ink bounds, rather than advance width, account for font side bearings.
                return Placement(size, endpoint - ink.right, ink)
            }
            val scale = minOf(availableWidth / ink.width, maximumHeight / ink.height, 1f)
            // Re-measure at the smaller size: integer glyph bounds do not scale continuously.
            size = size * scale - if (attempt > 0) 0.125f else 0f
            if (size < 1f) return null
        }
        return null
    }
}
