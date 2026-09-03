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

    private val stateLock = Any()

    private var nextSessionId = 0L
    private var currentSessionId = 0L
    private var virtualDisplay: VirtualDisplay? = null
    private var captureTarget: CaptureTarget? = null

    @Volatile
    private var successLogged = false

    @Volatile
    private var missingLogged = false

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int): Boolean {
        stop()

        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeDensityDpi = densityDpi.coerceAtLeast(1)
        val reader = createImageReader(safeWidth, safeHeight) ?: return false
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
        FgoLogger.info(tag, "MediaProjection capture started: ${safeWidth}x${safeHeight}")
        return true
    }

    fun resize(width: Int, height: Int, densityDpi: Int): Boolean {
        return resizeSession(
            expectedSessionId = null,
            width = width,
            height = height,
            densityDpi = densityDpi
        )
    }

    suspend fun capture(): Bitmap? {
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
            image = withTimeoutOrNull(750L) {
                withContext(Dispatchers.IO) {
                    var acquired: Image? = null
                    while (acquired == null) {
                        acquired = runCatching { target.reader.acquireLatestImage() }.getOrNull()
                        if (acquired == null) delay(16L)
                    }
                    acquired
                }
            } ?: return null

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
            if (!successLogged) {
                successLogged = true
                FgoLogger.info(tag, "MediaProjection captured first frame: ${target.width}x${target.height}")
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FgoLogger.warn(tag, "MediaProjection frame conversion failed", e)
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

    private fun resizeSession(
        expectedSessionId: Long?,
        width: Int,
        height: Int,
        densityDpi: Int
    ): Boolean {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeDensityDpi = densityDpi.coerceAtLeast(1)
        val snapshot = synchronized(stateLock) {
            val sessionId = currentSessionId
            val display = virtualDisplay
            val target = captureTarget
            when {
                sessionId == 0L || display == null || target == null -> null
                expectedSessionId != null && sessionId != expectedSessionId -> null
                else -> ResizeSnapshot(sessionId, display, target)
            }
        } ?: return false

        if (snapshot.target.width == safeWidth &&
            snapshot.target.height == safeHeight &&
            snapshot.target.densityDpi == safeDensityDpi
        ) {
            return true
        }

        val replacementReader = createImageReader(safeWidth, safeHeight) ?: return false
        val replacementTarget = CaptureTarget(
            reader = replacementReader,
            width = safeWidth,
            height = safeHeight,
            densityDpi = safeDensityDpi
        )
        var retiredReader: ImageReader? = null
        var resizeFailure: Throwable? = null

        val resized = synchronized(stateLock) {
            if (currentSessionId != snapshot.sessionId ||
                virtualDisplay !== snapshot.display ||
                captureTarget !== snapshot.target
            ) {
                false
            } else {
                try {
                    snapshot.display.resize(safeWidth, safeHeight, safeDensityDpi)
                    snapshot.display.setSurface(replacementReader.surface)
                    captureTarget = replacementTarget
                    retiredReader = retireCaptureTargetLocked(snapshot.target)
                    successLogged = false
                    missingLogged = false
                    true
                } catch (t: Throwable) {
                    resizeFailure = t
                    false
                }
            }
        }

        if (!resized) {
            replacementReader.close()
            resizeFailure?.let { failure ->
                FgoLogger.warn(
                    tag,
                    "MediaProjection resize failed; ending the capture session",
                    failure
                )
                releaseSession(snapshot.sessionId)
            }
            return false
        }

        retiredReader?.close()
        FgoLogger.info(tag, "MediaProjection capture resized: ${safeWidth}x${safeHeight}")
        return true
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

    private data class ResizeSnapshot(
        val sessionId: Long,
        val display: VirtualDisplay,
        val target: CaptureTarget
    )

    private data class SessionResources(
        val display: VirtualDisplay?,
        val readerToClose: ImageReader?
    )
}
