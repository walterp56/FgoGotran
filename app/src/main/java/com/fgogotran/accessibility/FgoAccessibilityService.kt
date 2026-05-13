package com.fgogotran.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.overlay.RegionClassifier
import com.fgogotran.overlay.TextRegion
import com.fgogotran.overlay.TranslationOverlay
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Accessibility service for FGO region detection.
 *
 * Debug mode: takes live screenshots on each FGO window change,
 * runs OCR, classifies regions (dialogue/name/choice), and displays
 * colored borders on the screenshot overlay for visual verification.
 */
@AndroidEntryPoint
class FgoAccessibilityService : AccessibilityService() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var regionClassifier: RegionClassifier
    @Inject lateinit var translationOverlay: TranslationOverlay

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false
    private var screenWidth = 0
    private var screenHeight = 0

    companion object {
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"
        private const val DEBOUNCE_MS = 350L

        private val _serviceStarted = mutableStateOf(false)
        val serviceStarted: State<Boolean> = _serviceStarted

        @Volatile
        var instance: FgoAccessibilityService? = null
            private set(value) {
                field = value
                _serviceStarted.value = value != null
            }

    }

    private val tag = "Accessibility"

    // ─── Service lifecycle ────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initScreenSize()
        translationOverlay.init(this, screenWidth, screenHeight)
        FgoLogger.info(tag, "Service connected, screen=${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowChange(event)
        }
    }

    override fun onInterrupt() {
        FgoLogger.info(tag, "Service interrupted")
        translationOverlay.hideAll()
        serviceScope.cancel()
    }

    override fun onDestroy() {
        FgoLogger.info(tag, "Service destroyed")
        instance = null
        serviceScope.cancel()
        translationOverlay.destroy()
        super.onDestroy()
    }

    // ─── Event handling ───────────────────────────────────────────────

    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName == FGO_PACKAGE) {
            FgoLogger.debug(tag, "FGO entered foreground")
            translationOverlay.showIndicator()
            if (!isProcessing) {
                serviceScope.launch {
                    delay(DEBOUNCE_MS)
                    processScreen()
                }
            }
        } else if (packageName == "com.fgogotran") {
            // ignore own windows
        } else {
            FgoLogger.info(tag, "Non-FGO package: $packageName, hiding overlay")
            translationOverlay.hideAll()
        }
    }

    // ─── OCR + region detection + overlay ─────────────────────────────

    private suspend fun processScreen() {
        if (isProcessing) return

        isProcessing = true

        try {
            // 1. Screenshot
            val screenshot = takeScreenshotCompat() ?: run {
                FgoLogger.warn(tag, "Screenshot null")
                return
            }
            val copy = screenshot.copy(Bitmap.Config.ARGB_8888, false)
            screenshot.recycle()

            // 2. OCR
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(copy)
            }
            if (ocrResult.lines.isEmpty()) {
                FgoLogger.debug(tag, "OCR: 0 lines, skipping")
                return
            }

            // 3. Region classification
            val regions = withContext(Dispatchers.Default) {
                regionClassifier.classify(ocrResult.lines, copy.width, copy.height)
            }
            if (regions.isEmpty()) {
                FgoLogger.debug(tag, "No regions classified")
                return
            }

            // 4. Draw colored borders per region type
            val canvas = Canvas(copy)
            val red = Paint().apply {
                color = Color.argb(255, 255, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = 20f
            }
            val green = Paint().apply {
                color = Color.argb(255, 0, 255, 0)
                style = Paint.Style.STROKE
                strokeWidth = 16f
            }
            val blue = Paint().apply {
                color = Color.argb(255, 0, 100, 255)
                style = Paint.Style.STROKE
                strokeWidth = 16f
            }

            var dialogueCount = 0
            var nameCount = 0
            var choiceCount = 0

            for (region in regions) {
                val paint = when (region.region) {
                    TextRegion.DIALOGUE_BOX -> { dialogueCount++; red }
                    TextRegion.NAME_LABEL -> { nameCount++; green }
                    TextRegion.CHOICE_BUTTON -> { choiceCount++; blue }
                }
                val text = region.lines.joinToString(" ") { it.text }
                FgoLogger.info(tag, "  ${region.region}: bbox=${region.boundingBox} text=\"$text\"")
                canvas.drawRect(region.boundingBox, paint)
            }

            FgoLogger.info(tag, "Result: dialog=$dialogueCount name=$nameCount choices=$choiceCount")

            // 5. Show
            withContext(Dispatchers.Main) {
                translationOverlay.updateImage(copy)
            }

        } catch (e: Exception) {
            FgoLogger.error(tag, "Error: ${e.message}", e)
        } finally {
            isProcessing = false
        }
    }

    // ─── Screenshot helper ────────────────────────────────────────────

//    private suspend fun takeScreenshotCompat(): Bitmap? {
//        return suspendCoroutine { cont ->
//            takeScreenshot(
//                Display.DEFAULT_DISPLAY,
//                mainExecutor,
//                object : TakeScreenshotCallback {
//                    override fun onSuccess(result: ScreenshotResult) {
//                        val bitmap = Bitmap.wrapHardwareBuffer(
//                            result.hardwareBuffer, result.colorSpace
//                        )
//                        cont.resume(bitmap)
//                    }
//                    override fun onFailure(errorCode: Int) {
//                        FgoLogger.warn(tag, "Screenshot failed, errorCode=$errorCode")
//                        cont.resume(null)
//                    }
//                }
//            )
//        }
//    }

    private fun initScreenSize() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }
}
