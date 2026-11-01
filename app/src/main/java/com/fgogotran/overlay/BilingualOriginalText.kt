package com.fgogotran.overlay

/** Japanese originals keep their source rows and fit independently of translated dialogue. */
internal object BilingualOriginalText {
    const val PREFERRED_TEXT_SIZE = 26f
    private const val MINIMUM_TEXT_PIXELS = 1f
    private val horizontalWhitespace = Regex("[ \\t]+")

    fun candidates(
        source: String,
        maximumLines: Int,
        reflow: (List<String>) -> List<String>
    ): List<String> {
        require(maximumLines > 0)
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').lines()
            .map { it.replace(horizontalWhitespace, " ").trim() }
            .filter(String::isNotBlank)
        // Explicit rows within the panel's limit must never compete with a flattened alternative.
        val candidates = if (lines.size <= maximumLines) listOf(lines.joinToString("\n")) else reflow(lines)
        return candidates.map(String::trim).filter(String::isNotBlank).distinct()
    }

    fun <T> largestFitting(
        viewportScale: Float,
        evaluate: (Float) -> T?
    ): PreciseTextSizeSearch.Result<T>? {
        if (!viewportScale.isFinite() || viewportScale <= 0f) return null
        val preferred = PREFERRED_TEXT_SIZE * viewportScale
        evaluate(preferred)?.let { return PreciseTextSizeSearch.Result(preferred, it) }
        val minimum = minOf(preferred, MINIMUM_TEXT_PIXELS)
        var upper = preferred
        while (upper > minimum) {
            val lower = (upper * 0.8f).coerceAtLeast(minimum)
            // Find a real fitting bracket before refining: tiny glyphs can have empty ink bounds.
            if (evaluate(lower) != null) {
                return PreciseTextSizeSearch.largestFitting(
                    lower, upper, minOf(0.25f * viewportScale, preferred * 0.005f), evaluate
                )
            }
            upper = lower
        }
        return null
    }
}
