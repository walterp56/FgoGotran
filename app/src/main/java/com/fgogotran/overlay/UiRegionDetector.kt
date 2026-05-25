package com.fgogotran.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects FGO dialog UI regions using OpenCV template matching.
 *
 * Current implementation:
 * - scans bottom 40% of screen
 * - grayscale template matching
 * - realtime optimized
 * - returns Android Rect
 *
 * IMPORTANT:
 * Put your template image here:
 *
 * app/src/main/assets/templates/dialog_corner.png
 *
 * Recommended template:
 * - small dialog border corner
 * - NOT text area
 * - around 50x50 ~ 150x150 px
 */
@Singleton
class UiRegionDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val tag = "UiRegionDetector"

    private var dialogTemplate: Mat? = null

    init {

        try {
            if (!ensureOpenCvLoaded()) {
                FgoLogger.warn(tag, "OpenCV native library unavailable; using fallback dialog region")
            } else {

                val input = openFirstAvailableTemplate()

                val bitmap =
                    android.graphics.BitmapFactory
                        .decodeStream(input)

                val mat = Mat()

                Utils.bitmapToMat(bitmap, mat)

                Imgproc.cvtColor(
                    mat,
                    mat,
                    Imgproc.COLOR_RGBA2GRAY
                )

                dialogTemplate = mat

                FgoLogger.info(
                    tag,
                    "Template loaded: ${mat.cols()}x${mat.rows()}"
                )
            }

        } catch (e: Throwable) {

            FgoLogger.warn(
                tag,
                "Failed to load template; using fallback dialog region",
                e
            )
        }
    }

    private fun ensureOpenCvLoaded(): Boolean {
        return runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) ||
            runCatching {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
                true
            }.getOrDefault(false)
    }

    private fun openFirstAvailableTemplate() =
        listOf(
            "templates/dialog_corner.png",
            "templates/dialog.png",
            "templates/dialog.jpg"
        ).firstNotNullOf { path ->
            runCatching { context.assets.open(path) }.getOrNull()
        }

    /**
     * Detects FGO dialog box.
     *
     * Returns:
     * - Rect if detected
     * - null otherwise
     */
    fun detectDialog(bitmap: Bitmap): Rect? {

        val template = dialogTemplate ?: return fallbackDialogRect(bitmap)

        return try {

            // Convert screenshot -> grayscale
            val src = Mat()

            Utils.bitmapToMat(bitmap, src)

            Imgproc.cvtColor(
                src,
                src,
                Imgproc.COLOR_RGBA2GRAY
            )

            // Restrict scan to bottom 40%
            val roiY =
                (src.rows() * 0.6).toInt()

            val roi = src.submat(
                roiY,
                src.rows(),
                0,
                src.cols()
            )

            // Match template
            val resultCols =
                roi.cols() - template.cols() + 1

            val resultRows =
                roi.rows() - template.rows() + 1

            if (resultCols <= 0 || resultRows <= 0) {

                FgoLogger.warn(
                    tag,
                    "Template larger than ROI"
                )

                return null
            }

            val result = Mat(
                resultRows,
                resultCols,
                CvType.CV_32FC1
            )

            Imgproc.matchTemplate(
                roi,
                template,
                result,
                Imgproc.TM_CCOEFF_NORMED
            )

            val mmr = Core.minMaxLoc(result)

            val confidence = mmr.maxVal

            FgoLogger.debug(
                tag,
                "Template confidence=$confidence"
            )

            // Threshold
            if (confidence < 0.82) {

                src.release()
                roi.release()
                result.release()

                return fallbackDialogRect(bitmap)
            }

            val x = mmr.maxLoc.x.toInt()
            val y = mmr.maxLoc.y.toInt() + roiY

            // Estimated dialog size
            val dialogWidth =
                (bitmap.width * 0.9).toInt()

            val dialogHeight =
                (bitmap.height * 0.22).toInt()

            val rect = Rect(
                x,
                y,
                (x + dialogWidth).coerceAtMost(bitmap.width),
                (y + dialogHeight).coerceAtMost(bitmap.height)
            )

            FgoLogger.debug(
                tag,
                "Dialog detected: $rect"
            )

            // Cleanup
            src.release()
            roi.release()
            result.release()

            rect

        } catch (e: Throwable) {

            FgoLogger.warn(
                tag,
                "detectDialog failed; using fallback dialog region",
                e
            )

            fallbackDialogRect(bitmap)
        }
    }

    private fun fallbackDialogRect(bitmap: Bitmap): Rect {
        val left = (bitmap.width * 0.05f).toInt()
        val top = (bitmap.height * 0.77f).toInt()
        val right = (bitmap.width * 0.95f).toInt()
        val bottom = (bitmap.height * 0.98f).toInt()
        return Rect(left, top, right, bottom)
    }
}
