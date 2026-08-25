package com.fgogotran.ocr

/** Scale already applied to the bitmap supplied to [OcrEngine]. */
enum class OcrInputScale(val factor: Int) {
    X1(1),
    X2(2)
}

/**
 * Pure scale calculations kept separate from Android bitmap operations so the
 * double-scale guard and coordinate normalization can be unit tested.
 */
internal object OcrScalePolicy {
    internal const val MAX_PREPARED_PIXEL_COUNT = 8_000_000L

    fun requestedAdditionalScale(
        preferredScale: OcrInputScale,
        inputScale: OcrInputScale
    ): Int {
        if (preferredScale.factor <= inputScale.factor) return 1
        if (preferredScale.factor % inputScale.factor != 0) return 1
        return preferredScale.factor / inputScale.factor
    }

    fun additionalScale(
        preferredScale: OcrInputScale,
        inputScale: OcrInputScale,
        width: Int,
        height: Int,
        maxPreparedPixels: Long = MAX_PREPARED_PIXEL_COUNT
    ): Int {
        require(width > 0 && height > 0) { "OCR bitmap dimensions must be positive" }
        require(maxPreparedPixels > 0) { "OCR pixel budget must be positive" }

        val requestedScale = requestedAdditionalScale(preferredScale, inputScale)
        if (requestedScale == 1) return 1

        val preparedPixels = width.toLong() * height.toLong() *
                requestedScale.toLong() * requestedScale.toLong()
        return if (preparedPixels <= maxPreparedPixels) requestedScale else 1
    }

    fun scaleDownStart(value: Int, scale: Int, maximum: Int): Int {
        require(scale > 0) { "Scale must be positive" }
        require(maximum >= 0) { "Maximum must not be negative" }
        val clamped = value.toLong().coerceIn(0L, maximum.toLong() * scale.toLong())
        return (clamped / scale.toLong()).toInt()
    }

    fun scaleDownEnd(value: Int, scale: Int, maximum: Int): Int {
        require(scale > 0) { "Scale must be positive" }
        require(maximum >= 0) { "Maximum must not be negative" }
        val clamped = value.toLong().coerceIn(0L, maximum.toLong() * scale.toLong())
        return ((clamped + scale - 1L) / scale.toLong()).toInt()
    }
}
