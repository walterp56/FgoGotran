package com.fgogotran.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object MediaProjectionCapture {
    private const val tag = "MediaProjectionCapture"

    private const val MAX_FAILURES_BEFORE_DISABLE = 3

    /**
     * A broken MediaProjection pipe keeps handing back frames that contain no
     * picture at all (fully transparent / black). Such frames must never reach OCR,
     * but a genuinely dark game scene must not disable projection either, so the
     * failure is only counted after several consecutive unusable frames.
     */
    private const val BLANK_FRAME_STREAK_BEFORE_FAILURE = 3
    private const val BLANK_FRAME_MIN_LUMINANCE = 24
    private const val BLANK_FRAME_MIN_VISIBLE_RATIO = 0.02f
    private const val BLANK_FRAME_SAMPLE_COLUMNS = 16
    private const val BLANK_FRAME_SAMPLE_ROWS = 9

    private val stateLock = Any()

    private var nextSessionId = 0L
    private var currentSessionId = 0L
    private var virtualDisplay: VirtualDisplay? = null
    private var captureTarget: CaptureTarget? = null

    @Volatile
    private var successLogged = false

    @Volatile
    private var missingLogged = false

    @Volatile
    private var sessionFailureCount = 0

    @Volatile
    private var disabledForSession = false

    @Volatile
    private var consecutiveBlankFrames = 0

    /**
     * Invoked when this run loses MediaProjection for good, so the owner can record a
     * diagnostic entry and stop waiting for projection frames.
     */
    @Volatile
    var onRunDisabled: ((String) -> Unit)? = null

    fun isAvailable(): Boolean = synchronized(stateLock) { captureTarget != null }

    fun isUsable(): Boolean = !disabledForSession && isAvailable()

    /** Called once per runner service run so a new service start gets a fresh set of chances. */
    fun resetForNewRun() {
        synchronized(stateLock) {
            sessionFailureCount = 0
            disabledForSession = false
        }
        consecutiveBlankFrames = 0
    }

    /** Records a start attempt that failed before any frame could be captured. */
    fun noteStartFailure(reason: String) {
        recordFailure(reason)
    }

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int): Boolean {
        if (disabledForSession) {
            FgoLogger.debug(tag, "MediaProjection disabled for this run; skipping start")
            return false
        }

        stop()

        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeDensityDpi = densityDpi.coerceAtLeast(1)
        val reader = createImageReader(safeWidth, safeHeight)
        if (reader == null) {
            recordFailure("image_reader_create")
            return false
        }
        val target = CaptureTarget(reader, safeWidth, safeHeight, safeDensityDpi)

        val sessionId = synchronized(stateLock) {
            nextSessionId += 1
            currentSessionId = nextSessionId
            currentSessionId
        }

        val display = try {
            projection.createVirtualDisplay(
                "FgoGotranCapture",
                safeWidth,
                safeHeight,
                safeDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } catch (t: Throwable) {
            FgoLogger.warn(tag, "MediaProjection virtual display creation failed", t)
            null
        }

        if (display == null) {
            reader.close()
            releaseSession(sessionId)
            recordFailure("virtual_display_create")
            return false
        }

        val installed = synchronized(stateLock) {
            if (currentSessionId == sessionId) {
                virtualDisplay = display
                captureTarget = target
                true
            } else {
                false
            }
        }
        if (!installed) {
            display.release()
            reader.close()
            return false
        }

        successLogged = false
        missingLogged = false
        consecutiveBlankFrames = 0
        FgoLogger.info(tag, "MediaProjection capture started: ${safeWidth}x${safeHeight}")
        return true
    }

    suspend fun capture(): Bitmap? {
        if (disabledForSession) {
            return null
        }

        val target = acquireCaptureTarget()
        if (target == null) {
            if (!missingLogged) {
                missingLogged = true
                FgoLogger.debug(tag, "MediaProjection capture source unavailable; falling back to accessibility screenshot")
            }
            return null
        }

        var image: Image? = null
        try {
            var acquireFailureReason: String? = null
            image = withTimeoutOrNull(750L) {
                withContext(Dispatchers.IO) {
                    var acquired: Image? = null
                    while (acquired == null && acquireFailureReason == null) {
                        val attempt = runCatching { target.reader.acquireLatestImage() }
                        acquired = attempt.getOrNull()
                        if (acquired == null) {
                            if (attempt.isFailure) {
                                acquireFailureReason = "acquire_exception"
                            } else {
                                delay(16L)
                            }
                        }
                    }
                    acquired
                }
            }
            if (image == null) {
                recordFailure(acquireFailureReason ?: "timeout")
                return null
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(pixelStride * target.width)
            val rowPadding = rowStride - pixelStride * target.width
            val bitmapWidth = target.width + rowPadding / pixelStride

            val padded = Bitmap.createBitmap(bitmapWidth, target.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val result = if (bitmapWidth == target.width) {
                padded
            } else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, target.width, target.height)
                if (cropped !== padded) padded.recycle()
                cropped
            }

            if (!isCurrentCaptureTarget(target)) {
                result.recycle()
                return null
            }
            if (!hasVisibleContent(result)) {
                consecutiveBlankFrames += 1
                FgoLogger.warn(
                    tag,
                    "MediaProjection delivered a blank frame (streak=$consecutiveBlankFrames); " +
                        "using accessibility screenshot"
                )
                if (consecutiveBlankFrames >= BLANK_FRAME_STREAK_BEFORE_FAILURE) {
                    consecutiveBlankFrames = 0
                    recordFailure("blank_frame")
                }
                result.recycle()
                return null
            }
            consecutiveBlankFrames = 0
            if (!successLogged) {
                successLogged = true
                FgoLogger.info(tag, "MediaProjection captured first frame: ${target.width}x${target.height}")
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FgoLogger.warn(tag, "MediaProjection frame conversion failed", e)
            recordFailure("conversion")
            return null
        } finally {
            image?.close()
            releaseCaptureTarget(target)
        }
    }

    fun stop() {
        val sessionId = synchronized(stateLock) { currentSessionId }
        if (sessionId != 0L) {
            releaseSession(sessionId)
        }
    }

    private fun recordFailure(reason: String) {
        var reachedLimit = false
        var count = 0
        synchronized(stateLock) {
            if (disabledForSession) return
            sessionFailureCount += 1
            count = sessionFailureCount
            if (sessionFailureCount >= MAX_FAILURES_BEFORE_DISABLE) {
                disabledForSession = true
                reachedLimit = true
            }
        }
        FgoLogger.warn(tag, "MediaProjection capture failure #$count: $reason")
        if (reachedLimit) {
            FgoLogger.error(
                tag,
                "MediaProjection disabled for this run after $MAX_FAILURES_BEFORE_DISABLE failures; using accessibility screenshot"
            )
            stop()
            notifyRunDisabled(reason)
        }
    }

    private fun notifyRunDisabled(reason: String) {
        val hook = onRunDisabled ?: return
        runCatching { hook(reason) }
            .onFailure { FgoLogger.warn(tag, "MediaProjection disable notification failed", it) }
    }

    /**
     * Sampled brightness check. A frame whose pixels are essentially all black or
     * fully transparent carries no OCR text and signals a broken projection pipe
     * (typical on emulators with GPU/Vulkan capture bugs).
     */
    private fun hasVisibleContent(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        var visibleSamples = 0
        var totalSamples = 0
        for (row in 0 until BLANK_FRAME_SAMPLE_ROWS) {
            val y = ((row + 0.5f) * bitmap.height / BLANK_FRAME_SAMPLE_ROWS)
                .toInt()
                .coerceIn(0, bitmap.height - 1)
            for (column in 0 until BLANK_FRAME_SAMPLE_COLUMNS) {
                val x = ((column + 0.5f) * bitmap.width / BLANK_FRAME_SAMPLE_COLUMNS)
                    .toInt()
                    .coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = ((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)
                totalSamples += 1
                if (luminance >= BLANK_FRAME_MIN_LUMINANCE) visibleSamples += 1
            }
        }
        if (totalSamples == 0) return false
        return visibleSamples.toFloat() / totalSamples.toFloat() >= BLANK_FRAME_MIN_VISIBLE_RATIO
    }

    private fun createImageReader(width: Int, height: Int): ImageReader? {
        return try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            FgoLogger.warn(tag, "MediaProjection ImageReader creation failed: ${width}x${height}", t)
            null
        }
    }

    private fun acquireCaptureTarget(): CaptureTarget? {
        return synchronized(stateLock) {
            captureTarget
                ?.takeUnless { it.retired || it.closed }
                ?.also { it.activeCaptures += 1 }
        }
    }

    private fun releaseCaptureTarget(target: CaptureTarget) {
        val readerToClose = synchronized(stateLock) {
            target.activeCaptures = (target.activeCaptures - 1).coerceAtLeast(0)
            readerToCloseLocked(target)
        }
        readerToClose?.close()
    }

    private fun isCurrentCaptureTarget(target: CaptureTarget): Boolean {
        return synchronized(stateLock) {
            captureTarget === target && !target.retired && !target.closed
        }
    }

    private fun releaseSession(sessionId: Long) {
        val resources = synchronized(stateLock) {
            if (currentSessionId != sessionId) return

            val detached = SessionResources(
                display = virtualDisplay,
                readerToClose = captureTarget?.let(::retireCaptureTargetLocked)
            )
            currentSessionId = 0L
            virtualDisplay = null
            captureTarget = null
            successLogged = false
            missingLogged = false
            detached
        }

        runCatching { resources.display?.release() }
            .onFailure { FgoLogger.warn(tag, "MediaProjection display release failed", it) }
        runCatching { resources.readerToClose?.close() }
            .onFailure { FgoLogger.warn(tag, "MediaProjection ImageReader close failed", it) }
    }

    private fun retireCaptureTargetLocked(target: CaptureTarget): ImageReader? {
        target.retired = true
        return readerToCloseLocked(target)
    }

    private fun readerToCloseLocked(target: CaptureTarget): ImageReader? {
        if (!target.retired || target.activeCaptures > 0 || target.closed) return null
        target.closed = true
        return target.reader
    }

    // Retired readers stay open until any screenshot already using them has finished.
    private class CaptureTarget(
        val reader: ImageReader,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        var activeCaptures: Int = 0,
        var retired: Boolean = false,
        var closed: Boolean = false
    )

    private data class SessionResources(
        val display: VirtualDisplay?,
        val readerToClose: ImageReader?
    )
}
