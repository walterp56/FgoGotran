package com.fgogotran.overlay

/** Finds the largest fitting text size without the visible slack caused by coarse 2 px steps. */
internal object PreciseTextSizeSearch {
    data class Result<T>(
        val textSize: Float,
        val value: T
    )

    fun <T> largestFitting(
        minimumTextSize: Float,
        maximumTextSize: Float,
        precision: Float,
        evaluate: (Float) -> T?
    ): Result<T>? {
        if (!minimumTextSize.isFinite() ||
            !maximumTextSize.isFinite() ||
            minimumTextSize > maximumTextSize
        ) {
            return null
        }

        evaluate(maximumTextSize)?.let { value ->
            return Result(maximumTextSize, value)
        }

        var lowerSize = minimumTextSize
        var lowerValue = evaluate(lowerSize) ?: return null
        var upperSize = maximumTextSize
        val safePrecision = precision.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_PRECISION
        var iteration = 0

        while (upperSize - lowerSize > safePrecision && iteration < MAX_ITERATIONS) {
            val candidateSize = (lowerSize + upperSize) / 2f
            val candidateValue = evaluate(candidateSize)
            if (candidateValue != null) {
                lowerSize = candidateSize
                lowerValue = candidateValue
            } else {
                upperSize = candidateSize
            }
            iteration++
        }

        return Result(lowerSize, lowerValue)
    }

    private const val DEFAULT_PRECISION = 0.25f
    private const val MAX_ITERATIONS = 16
}
