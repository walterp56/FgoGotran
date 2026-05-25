package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.util.FgoLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Samples text and background colors from screenshot bitmaps.
 *
 * ## Why sampling is needed
 * FGO uses different text/background colors depending on the scene:
 * - White text on semi-transparent dark blue (standard dialogue)
 * - Gold text on dark background (Noble Phantasm names)
 * - Red text (damage numbers, enemy names)
 *
 * Rather than using fixed colors, we sample from the actual pixels to match
 * the game's rendering exactly.
 *
 * ## Sampling strategy
 * - **Text color**: samples the center row of each OCR text bounding box,
 *   takes the brightest 20% of pixels (FGO renders light text on dark backgrounds)
 * - **Background color**: samples a row near the top inside each panel
 *   (avoids centered text while remaining on the UI surface)
 */
@Singleton
class ColorSampler @Inject constructor() {

    private val tag = "ColorSampler"

    /**
     * Samples the text color from within the OCR bounding boxes.
     *
     * Algorithm:
     * 1. For each line, sample pixels along the horizontal center-line of its bounding box
     *    (skipping 10% margin on each side to avoid edge artifacts)
     * 2. Sort all sampled pixels by luminance using BT.601 weights
     *    (0.299R + 0.587G + 0.114B — models human perception)
     * 3. Take the top 20% brightest pixels
     * 4. Use the median R, G, B of that top cluster as the text color
     *
     * The 20% threshold works because FGO renders light-colored text on dark
     * semi-transparent backgrounds. The brightest pixels are almost always the
     * text itself, not the background showing through.
     */
    fun sampleTextColor(bitmap: Bitmap, lines: List<OcrTextLine>): Int {
        if (lines.isEmpty()) return Color.WHITE

        val samples = mutableListOf<FloatArray>()

        for (line in lines) {
            val box = line.boundingBox
            // Sample along the horizontal center of the text bounding box
            val cy = box.centerY()
            // Skip 10% margin on each side to avoid sampling background at text edges
            val margin = (box.width() * 0.1f).toInt().coerceAtLeast(1)
            for (x in box.left + margin until box.right - margin step 3) {
                if (x in 0 until bitmap.width && cy in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, cy)
                    samples.add(floatArrayOf(
                        Color.red(pixel).toFloat(),
                        Color.green(pixel).toFloat(),
                        Color.blue(pixel).toFloat()
                    ))
                }
            }
        }

        if (samples.isEmpty()) {
            FgoLogger.warn(tag, "No valid pixel samples for text color, using fallback WHITE")
            return Color.WHITE
        }

        // Sort by perceived brightness (BT.601 luminance)
        val sorted = samples.sortedBy { rgb ->
            0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
        }

        // Top 20% brightest pixels = text color cluster
        val textCluster = sorted.takeLast((sorted.size * 0.2f).toInt().coerceAtLeast(1))
        val medianR = textCluster.map { it[0] }.sorted().let { it[it.size / 2] }.toInt()
        val medianG = textCluster.map { it[1] }.sorted().let { it[it.size / 2] }.toInt()
        val medianB = textCluster.map { it[2] }.sorted().let { it[it.size / 2] }.toInt()

        val color = Color.rgb(medianR, medianG, medianB)
        FgoLogger.debug(tag, "Sampled text color from ${samples.size} pixels → #${Integer.toHexString(color)}")
        return color
    }

    /**
     * Samples the background color from near the top inside the panel rectangle.
     *
     * Render bounds now describe the stable panel surface. Sampling above those
     * bounds would read scene artwork instead of the panel background.
     *
     * @param bitmap the screenshot
     * @param regionRect the classified region's enclosing rectangle
     * @return the median background color, or a default dark blue if sampling fails
     */
    fun sampleBackgroundColor(bitmap: Bitmap, regionRect: Rect): Int {
        // Sample inside the top edge, above centered text.
        val inset = (regionRect.height() / 8).coerceIn(5, 14)
        val sampleY = (regionRect.top + inset).coerceIn(0, bitmap.height - 1)
        val samples = mutableListOf<Int>()

        for (x in regionRect.left..regionRect.right step 5) {
            if (x in 0 until bitmap.width && sampleY in 0 until bitmap.height) {
                samples.add(bitmap.getPixel(x, sampleY))
            }
        }

        if (samples.isEmpty()) {
            FgoLogger.warn(tag, "No valid pixel samples for background color, using fallback")
            // Semi-transparent dark blue — matches FGO's typical dialogue box
            return Color.argb(200, 10, 10, 40)
        }

        // Use median of each channel for robustness against single-pixel noise
        val sortedR = samples.map { Color.red(it) }.sorted()
        val sortedG = samples.map { Color.green(it) }.sorted()
        val sortedB = samples.map { Color.blue(it) }.sorted()
        val mid = samples.size / 2

        val color = Color.rgb(sortedR[mid], sortedG[mid], sortedB[mid])
        FgoLogger.debug(tag, "Sampled background color from ${samples.size} pixels → #${Integer.toHexString(color)}")
        return color
    }
}
