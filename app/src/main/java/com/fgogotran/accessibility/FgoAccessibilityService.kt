package com.fgogotran.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.overlay.ClassifiedRegion
import com.fgogotran.overlay.ColorSampler
import com.fgogotran.overlay.OverlayRenderer
import com.fgogotran.overlay.RegionClassifier
import com.fgogotran.overlay.RenderInstruction
import com.fgogotran.overlay.TextRegion
import com.fgogotran.overlay.TranslationOverlay
import com.fgogotran.overlay.UiRegionDetector
import com.fgogotran.story.StoryDetector
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Realtime FGO capture -> OCR -> translation -> overlay pipeline.
 */
@AndroidEntryPoint
class FgoAccessibilityService : AccessibilityService() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var regionClassifier: RegionClassifier
    @Inject lateinit var translationOverlay: TranslationOverlay
    @Inject lateinit var uiRegionDetector: UiRegionDetector
    @Inject lateinit var translator: Translator
    @Inject lateinit var overlayRenderer: OverlayRenderer
    @Inject lateinit var colorSampler: ColorSampler
    @Inject lateinit var storyDetector: StoryDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var isFgoForeground = false
    private var lastAutoTriggerAt = 0L
    private var lastAutoScanAt = 0L
    private var lastRenderedSourceText = ""

    companion object {
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"
        private const val APP_PACKAGE = "com.fgogotran"
        private const val DETECTION_INTERVAL = 700L
        private const val AUTO_SCAN_INTERVAL = 1_000L
        private const val AUTO_TRIGGER_COOLDOWN = 1_200L
        private const val CAPTURE_SETTLE_DELAY = 150L
        private const val TAP_REPLAY_SETTLE_DELAY = 500L

        private val _serviceStarted = mutableStateOf(false)
        val serviceStarted: State<Boolean>
            get() = _serviceStarted

        @Volatile
        var instance: FgoAccessibilityService? = null
            private set(value) {
                field = value
                _serviceStarted.value = value != null
            }
    }

    private val tag = "Accessibility"
    private val supportedFgoPackages = setOf(
        FGO_PACKAGE,
        "com.aniplex.fategrandorder.en",
        "com.bilibili.fatego",
        "com.komoe.fgo"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initScreenSize()
        translationOverlay.init(this, screenWidth, screenHeight) { x, y ->
            handleTranslatedOverlayTap(x, y)
        }
        startDetectionLoop()
        FgoLogger.info(tag, "Service connected: ${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val activePackageName = rootInActiveWindow?.packageName?.toString()
        val isFgoEvent = packageName.isSupportedFgoPackage()
        val isFgoActive = activePackageName?.isSupportedFgoPackage() == true

        when {
            packageName == APP_PACKAGE -> {
                if (isFgoActive) {
                    isFgoForeground = true
                    translationOverlay.showIndicator()
                }
                // Our overlays emit window/touch events when they appear or redraw. Treat them as UI noise.
            }
            isFgoEvent || isFgoActive -> {
                if (!isFgoForeground) {
                    FgoLogger.info(
                        tag,
                        "FGO foreground detected: event=$packageName active=$activePackageName"
                    )
                }
                isFgoForeground = true
                translationOverlay.showIndicator()
                if (event.isTranslationTriggerEvent() && canAutoTrigger()) {
                    FgoLogger.debug(
                        tag,
                        "FGO interaction detected; requesting translation (event=$packageName active=$activePackageName)"
                    )
                    TranslationTrigger.requestTranslation()
                }
            }
            else -> {
                if (isFgoForeground) {
                    FgoLogger.info(tag, "FGO foreground lost: event=$packageName active=$activePackageName")
                }
                isFgoForeground = false
                translationOverlay.hideAll()
            }
        }
    }

    override fun onInterrupt() {
        FgoLogger.warn(tag, "Service interrupted")
        translationOverlay.hideAll()
        serviceScope.cancel()
    }

    override fun onDestroy() {
        instance = null
        translationOverlay.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (isFgoForeground && !isProcessing) {
                        val manualRequest = TranslationTrigger.consumeRequest()
                        when {
                            manualRequest -> processScreen(forceRefresh = true)
                            shouldAutoScan() -> processScreen(forceRefresh = false)
                        }
                    }
                } catch (e: Exception) {
                    FgoLogger.error(tag, "Detection loop failed", e)
                }
                delay(DETECTION_INTERVAL)
            }
        }
    }

    private fun handleTranslatedOverlayTap(x: Float, y: Float) {
        if (isProcessing) {
            FgoLogger.debug(tag, "Ignoring overlay tap while processing")
            return
        }

        serviceScope.launch {
            FgoLogger.debug(tag, "Overlay tap will be replayed to FGO at $x,$y")
            translationOverlay.hide()
            delay(CAPTURE_SETTLE_DELAY)
            val dispatched = dispatchTapToFgo(x, y)
            if (!dispatched) {
                FgoLogger.warn(tag, "Failed to dispatch overlay tap to FGO")
            }
            delay(TAP_REPLAY_SETTLE_DELAY)
            TranslationTrigger.requestTranslation()
        }
    }

    private suspend fun processScreen(forceRefresh: Boolean) {
        if (isProcessing) return
        isProcessing = true

        var screenshot: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            if (translationOverlay.isShowing()) {
                if (!forceRefresh) return
                FgoLogger.debug(tag, "Hiding translation overlay before capture")
                translationOverlay.hide()
                delay(CAPTURE_SETTLE_DELAY)
            }

            screenshot = takeScreenshotCompat() ?: return
            val source = screenshot
            val currentScreenWidth = source.width
            val currentScreenHeight = source.height

            val dialogRect = withContext(Dispatchers.Default) {
                uiRegionDetector.detectDialog(source)
            }

            if (dialogRect == null) {
                FgoLogger.debug(tag, "No dialog detected")
                translationOverlay.hide()
                return
            }

            cropped = Bitmap.createBitmap(
                source,
                dialogRect.left,
                dialogRect.top,
                dialogRect.width(),
                dialogRect.height()
            )

            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(cropped)
            }

            val absoluteLines = ocrResult.lines.toScreenCoordinates(dialogRect)
            if (absoluteLines.isEmpty()) {
                translationOverlay.hide()
                return
            }

            val classifiedRegions = regionClassifier
                .classify(absoluteLines, currentScreenWidth, currentScreenHeight)
                .ifEmpty {
                    listOf(
                        ClassifiedRegion(
                            region = TextRegion.DIALOGUE_BOX,
                            lines = absoluteLines,
                            boundingBox = dialogRect
                        )
                    )
                }
                .prioritizeUserRequestedRegions()

            val storyResult = storyDetector.detect(absoluteLines, currentScreenWidth, currentScreenHeight)
            FgoLogger.debug(tag, "Story detection: ${storyResult.isStoryScene}, ${storyResult.reason}")

            val sourceFingerprint = classifiedRegions
                .joinToString("\n\n") { region ->
                    region.lines.joinToString("\n") { it.text }.trim()
                }
                .trim()
            if (!forceRefresh && sourceFingerprint == lastRenderedSourceText) {
                FgoLogger.debug(tag, "Dialogue unchanged; keeping current overlay")
                return
            }

            val instructions = classifiedRegions.mapNotNull { region ->
                val sourceText = region.lines.joinToString("\n") { it.text }.trim()
                if (sourceText.isBlank()) return@mapNotNull null

                val translated = translator.translate(sourceText).translatedText
                RenderInstruction(
                    region = region,
                    translatedText = translated,
                    textColor = colorSampler.sampleTextColor(source, region.lines),
                    backgroundColor = colorSampler.sampleBackgroundColor(source, region.boundingBox)
                )
            }

            if (instructions.isEmpty()) {
                translationOverlay.hide()
                return
            }

            val rendered = overlayRenderer.render(
                bitmap = source,
                instructions = instructions,
                screenWidth = currentScreenWidth,
                screenHeight = currentScreenHeight
            )
            lastRenderedSourceText = sourceFingerprint
            translationOverlay.updateImage(rendered)
        } catch (e: Exception) {
            FgoLogger.error(tag, "processScreen failed", e)
        } finally {
            cropped?.recycle()
            screenshot?.recycle()
            isProcessing = false
        }
    }

    private fun List<ClassifiedRegion>.prioritizeUserRequestedRegions(): List<ClassifiedRegion> {
        val choiceRegions = filter { it.region == TextRegion.CHOICE_BUTTON }
        if (choiceRegions.isNotEmpty()) return choiceRegions

        val dialogueRegions = filter {
            it.region == TextRegion.DIALOGUE_BOX || it.region == TextRegion.NAME_LABEL
        }
        return dialogueRegions.ifEmpty { this }
    }

    private fun List<OcrTextLine>.toScreenCoordinates(offset: Rect): List<OcrTextLine> {
        return map { line ->
            OcrTextLine(
                text = line.text,
                boundingBox = Rect(
                    line.boundingBox.left + offset.left,
                    line.boundingBox.top + offset.top,
                    line.boundingBox.right + offset.left,
                    line.boundingBox.bottom + offset.top
                ),
                confidence = line.confidence
            )
        }
    }

    @Suppress("NewApi")
    private suspend fun takeScreenshotCompat(): Bitmap? {
        return suspendCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace
                        )
                        val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        FgoLogger.warn(tag, "Screenshot failed: $errorCode")
                        cont.resume(null)
                    }
                }
            )
        }
    }

    @Suppress("NewApi")
    private suspend fun dispatchTapToFgo(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()

        return suspendCoroutine { cont ->
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        FgoLogger.debug(tag, "Overlay tap replay completed")
                        cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        FgoLogger.warn(tag, "Overlay tap replay cancelled")
                        cont.resume(false)
                    }
                },
                null
            )
        }
    }

    private fun initScreenSize() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun String.isSupportedFgoPackage(): Boolean {
        return this in supportedFgoPackages || startsWith("$FGO_PACKAGE.")
    }

    private fun AccessibilityEvent.isTranslationTriggerEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun canAutoTrigger(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoTriggerAt < AUTO_TRIGGER_COOLDOWN) return false
        lastAutoTriggerAt = now
        return true
    }

    private fun shouldAutoScan(): Boolean {
        if (translationOverlay.isShowing()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoScanAt < AUTO_SCAN_INTERVAL) return false
        lastAutoScanAt = now
        return true
    }
}
