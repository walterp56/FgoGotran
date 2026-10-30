package com.fgogotran.ocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/** Pixel-only metadata analysis; the recognizer's bitmap and recognized text stay untouched. */
internal object MlKitEmphasisInput {
    private const val MAX_SIDE = 1920

    fun dots(bitmap: Bitmap): List<OcrEmphasisDot> {
        // ML Kit normally receives a 2x crop. Bound this scan independently of that enlargement.
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val sample = if (width == bitmap.width && height == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            val geometry = DialogueAnnotationCleaner.clean(pixels, width, height).emphasisDots
            val sx = bitmap.width.toFloat() / width
            val sy = bitmap.height.toFloat() / height
            geometry.map { it.copy(centerX = it.centerX * sx, mainTop = it.mainTop * sy,
                mainBottom = it.mainBottom * sy, verifiedRowTop = it.verifiedRowTop?.times(sy),
                verifiedRowBottom = it.verifiedRowBottom?.times(sy)) }
        } finally { if (sample !== bitmap && !sample.isRecycled) sample.recycle() }
    }
}
