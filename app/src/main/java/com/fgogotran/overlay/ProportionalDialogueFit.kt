package com.fgogotran.overlay

/** Whole-dialogue fitting; a success must already include every body glyph and ruby annotation. */
internal object ProportionalDialogueFit {
    const val RUBY_RATIO = 0.40f
    // A one-pixel ruby is the physical raster limit, rather than the old 22/24 px body limit.
    const val MINIMUM_BODY_PIXELS = 1f / RUBY_RATIO

    data class Sizes(val body: Float, val ruby: Float, val original: Float, val drawingScale: Float)
    data class Result<T>(val sizes: Sizes, val value: T)

    fun sizes(preferredBody: Float, preferredOriginal: Float, viewportScale: Float, factor: Float) =
        Sizes(preferredBody * factor, preferredBody * factor * RUBY_RATIO,
            preferredOriginal * factor, viewportScale * factor)

    fun <T> largest(
        preferredBody: Float,
        preferredOriginal: Float = 0f,
        viewportScale: Float,
        evaluate: (Sizes) -> T?
    ): Result<T>? {
        if (!preferredBody.isFinite() || preferredBody <= 0f || !viewportScale.isFinite() || viewportScale <= 0f) return null
        fun at(size: Float): T? = evaluate(sizes(preferredBody, preferredOriginal, viewportScale, size / preferredBody))
        at(preferredBody)?.let { return Result(sizes(preferredBody, preferredOriginal, viewportScale, 1f), it) }
        val minimum = minOf(preferredBody, MINIMUM_BODY_PIXELS)
        var upper = preferredBody
        while (upper > minimum) {
            val lower = (upper * 0.8f).coerceAtLeast(minimum)
            val value = at(lower)
            if (value != null) {
                val fit = PreciseTextSizeSearch.largestFitting(lower, upper,
                    minOf(0.25f * viewportScale, preferredBody * 0.005f), ::at)
                    ?: return null
                return Result(sizes(preferredBody, preferredOriginal, viewportScale, fit.textSize / preferredBody), fit.value)
            }
            upper = lower
        }
        return null
    }
}
