package com.fgogotran.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object MediaProjectionCapture {
    private const val tag = "MediaProjectionCapture"

    @Volatile
    private var successLogged = false

    @Volatile
    private var missingLogged = false

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    @Volatile
    private var densityDpi = 0

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int) {
        stop()
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val reader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)
        mediaProjection = projection
        imageReader = reader
        this.width = safeWidth
        this.height = safeHeight
        this.densityDpi = densityDpi.coerceAtLeast(1)

        val display = try {
            projection.createVirtualDisplay(
                "FgoGotranCapture",
                safeWidth,
                safeHeight,
                this.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } catch (t: Throwable) {
            null
        }

        virtualDisplay = display
        if (display == null) {
            reader.close()
            imageReader = null
            projection.stop()
            mediaProjection = null
            FgoLogger.warn(tag, "MediaProjection virtual display creation failed")
        } else {
            successLogged = false
            missingLogged = false
            FgoLogger.info(tag, "MediaProjection capture started: ${safeWidth}x${safeHeight}")
        }
    }

    suspend fun capture(): Bitmap? {
        val reader = imageReader
        if (reader == null) {
            if (!missingLogged) {
                missingLogged = true
                FgoLogger.debug(tag, "MediaProjection capture source unavailable; falling back to accessibility screenshot")
            }
            return null
        }
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null

        val image = withTimeoutOrNull(750L) {
            withContext(Dispatchers.IO) {
                var acquired: Image? = null
                while (acquired == null) {
                    acquired = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (acquired == null) delay(16L)
                }
                acquired
            }
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(pixelStride * w)
            val rowPadding = rowStride - pixelStride * w
            val bitmapWidth = w + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(bitmapWidth, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val result = if (bitmapWidth == w) {
                padded
            } else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
                if (cropped !== padded) padded.recycle()
                cropped
            }
            if (!successLogged) {
                successLogged = true
                FgoLogger.info(tag, "MediaProjection captured first frame: ${w}x${h}")
            }
            result
        } finally {
            image.close()
        }
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        width = 0
        height = 0
        densityDpi = 0
    }
}
