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
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.overlay.BackgroundDetector
import com.fgogotran.overlay.ClassifiedRegion
import com.fgogotran.overlay.FgoScreenRegions
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.overlay.OverlayRenderer
import com.fgogotran.overlay.RenderInstruction
import com.fgogotran.overlay.TextRegion
import com.fgogotran.overlay.TranslationOverlay
import com.fgogotran.story.StoryDetector
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Realtime FGO capture -> OCR -> translation -> overlay pipeline.
 */
@AndroidEntryPoint
class FgoAccessibilityService : AccessibilityService() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var backgroundDetector: BackgroundDetector
    @Inject lateinit var translationOverlay: TranslationOverlay
    @Inject lateinit var translator: Translator
    @Inject lateinit var overlayRenderer: OverlayRenderer
    @Inject lateinit var storyDetector: StoryDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false
    private var translationJob: Job? = null
    private var stopVersion = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var isFgoForeground = false
    private var lastRenderedSourceText = ""
    private var isSkipStoryActive = false
    private var autoScanReadyAt = 0L
    private var renderedChoiceBounds: List<Rect> = emptyList()
    private var waitingForChoiceSelectionExit = false

    companion object {
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"
        private const val APP_PACKAGE = "com.fgogotran"
        private const val DETECTION_INTERVAL = 150L
        private const val CAPTURE_SETTLE_DELAY = 16L
        private const val MANUAL_MENU_DISMISS_SETTLE_DELAY = 300L
        private const val TAP_OVERLAY_REMOVAL_DELAY = 80L
        private const val TAP_REPLAY_TIMEOUT = 500L
        private const val FRESHNESS_CHECK_TRANSLATION_DELAY = 400L

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
        TranslationTrigger.setAutoTranslateEnabled(false)
        startDetectionLoop()
        FgoLogger.info(tag, "Gesture injection available: ${canPerformGestures()}")
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
                if (event.isDialogueAdvanceEvent()) {
                    if (translationOverlay.isShowing()) {
                        FgoLogger.debug(tag, "FGO dialogue advance detected; hiding translated overlay for next OCR")
                        translationOverlay.hide()
                    }
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

    fun setAutoTranslationEnabled(enabled: Boolean) {
        TranslationTrigger.setAutoTranslateEnabled(enabled)
        cancelCurrentTranslation()
        if (enabled) {
            autoScanReadyAt = SystemClock.elapsedRealtime() + MANUAL_MENU_DISMISS_SETTLE_DELAY
            FgoLogger.debug(tag, "Auto translate enabled")
        } else {
            autoScanReadyAt = 0L
            FgoLogger.debug(tag, "Auto translate disabled")
        }
    }

    private fun cancelCurrentTranslation() {
        stopVersion++
        TranslationTrigger.cancelPendingTranslation()
        translationJob?.cancel()
        translationJob = null
        lastRenderedSourceText = ""
        renderedChoiceBounds = emptyList()
        waitingForChoiceSelectionExit = false
        translationOverlay.hide()
    }

    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (isFgoForeground && !isProcessing) {
                        val autoEnabled = TranslationTrigger.isAutoTranslateEnabled()
                        val manualRequest = if (autoEnabled) false else TranslationTrigger.consumeRequest()
                        if (autoEnabled &&
                            !translationOverlay.isShowing() &&
                            SystemClock.elapsedRealtime() >= autoScanReadyAt
                        ) {
                            translationJob = serviceScope.launch {
                                processScreen(forceRefresh = false)
                            }
                        } else if (manualRequest) {
                            FgoLogger.debug(tag, "Translate Now requested")
                            val waitForMenuDismissal = TranslationTrigger.consumeMenuDismissSettleRequired()
                            translationJob = serviceScope.launch {
                                if (waitForMenuDismissal) {
                                    // The popup dismissal animates over FGO and can obscure SKIP.
                                    delay(MANUAL_MENU_DISMISS_SETTLE_DELAY)
                                }
                                processScreen(forceRefresh = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    FgoLogger.error(tag, "Detection loop failed", e)
                }
                delay(DETECTION_INTERVAL)
            }
        }
    }

    private suspend fun processScreen(forceRefresh: Boolean) {
        if (isProcessing) return
        isProcessing = true
        val processingVersion = stopVersion

        var screenshot: Bitmap? = null
        var restoreHiddenOverlay = false
        try {
            if (translationOverlay.isShowing()) {
                if (!forceRefresh) return
                restoreHiddenOverlay = true
                FgoLogger.debug(tag, "Hiding translation overlay briefly to read source text")
                translationOverlay.hideForCapture()
                delay(CAPTURE_SETTLE_DELAY)
            }

            screenshot = takeScreenshotCompat() ?: return
            val source = screenshot
            val currentScreenWidth = source.width
            val currentScreenHeight = source.height
            val screenRegions = FgoViewportLayout.regionsForScreen(currentScreenWidth, currentScreenHeight)
            FgoLogger.debug(tag, "FGO viewport=${screenRegions.viewport}, skip=${screenRegions.skip}")

            val isStoryScreen = if (isSkipStoryActive) {
                withContext(Dispatchers.Default) {
                    backgroundDetector.isSkipButtonVisible(source, screenRegions.skip)
                }
            } else {
                confirmSkipMarker(source, screenRegions.skip)
            }
            if (!isStoryScreen) {
                restoreHiddenOverlay = false
                isSkipStoryActive = false
                lastRenderedSourceText = ""
                renderedChoiceBounds = emptyList()
                waitingForChoiceSelectionExit = false
                FgoLogger.debug(tag, "SKIP marker not visible; skipping story OCR")
                translationOverlay.hide()
                return
            }
            if (!isSkipStoryActive) {
                isSkipStoryActive = true
                FgoLogger.debug(tag, "SKIP marker appeared; monitoring story OCR text")
            }
            if (backgroundDetector.isSkipConfirmationVisible(
                    source,
                    screenRegions.skipConfirmationNoButton,
                    screenRegions.skipConfirmationYesButton
                )
            ) {
                restoreHiddenOverlay = false
                lastRenderedSourceText = ""
                FgoLogger.debug(tag, "SKIP confirmation modal visible; skipping translation")
                translationOverlay.hide()
                return
            }

            val choiceRegions = recognizeChoiceRegions(source, screenRegions)
            if (waitingForChoiceSelectionExit) {
                if (choiceRegions.isNotEmpty()) {
                    FgoLogger.debug(tag, "Choice selection is still leaving the screen; suppressing repeated choice translation")
                    return
                }
                waitingForChoiceSelectionExit = false
                FgoLogger.debug(tag, "Choice selection left the screen; resuming auto translation")
            }
            val dialogueComplete = choiceRegions.isEmpty() &&
                backgroundDetector.isDialogueCompleteMarkerVisible(
                    source,
                    screenRegions.dialogueComplete
                )
            val classifiedRegions = if (choiceRegions.isNotEmpty()) {
                FgoLogger.debug(tag, "Choice text detected; translating choices before dialogue marker check")
                choiceRegions
            } else if (dialogueComplete) {
                recognizeDialogueRegions(source, screenRegions)
            } else {
                emptyList()
            }
            if (classifiedRegions.isEmpty()) {
                if (dialogueComplete) {
                    restoreHiddenOverlay = false
                    FgoLogger.debug(tag, "No translatable completed dialogue detected in FGO regions")
                    translationOverlay.hide()
                } else {
                    FgoLogger.debug(tag, "Dialogue is still typing and no choices are visible")
                }
                return
            }

            val detectedLines = classifiedRegions.flatMap { it.lines }
            val storyResult = storyDetector.detect(detectedLines, currentScreenWidth, currentScreenHeight)
            FgoLogger.debug(tag, "Story detection: ${storyResult.isStoryScene}, ${storyResult.reason}")

            val sourceFingerprint = fingerprintFor(classifiedRegions)
            if (!forceRefresh) {
                if (sourceFingerprint == lastRenderedSourceText) {
                    FgoLogger.debug(tag, "Dialogue unchanged; waiting for new OCR text")
                    return
                }
            }

            restoreHiddenOverlay = false
            val translationStartedAt = SystemClock.elapsedRealtime()
            val instructions = buildRenderInstructions(classifiedRegions)
            val translationDuration = SystemClock.elapsedRealtime() - translationStartedAt

            if (instructions.isEmpty()) {
                translationOverlay.hide()
                return
            }
            if (translationDuration >= FRESHNESS_CHECK_TRANSLATION_DELAY &&
                !isSourceFingerprintCurrent(sourceFingerprint)
            ) {
                FgoLogger.debug(tag, "Dialogue changed during translation; discarding stale result")
                return
            }
            if (translationDuration < FRESHNESS_CHECK_TRANSLATION_DELAY) {
                FgoLogger.debug(tag, "Fast translation (${translationDuration}ms); rendering without OCR recheck")
            }
            if (processingVersion != stopVersion) {
                FgoLogger.debug(tag, "Translation was stopped; discarding completed result")
                return
            }

            val rendered = overlayRenderer.render(
                bitmap = source,
                instructions = instructions,
                screenWidth = currentScreenWidth,
                screenHeight = currentScreenHeight
            )
            lastRenderedSourceText = sourceFingerprint
            renderedChoiceBounds = instructions
                .filter { it.region.region == TextRegion.CHOICE_BUTTON }
                .map { Rect(it.region.boundingBox) }
            translationOverlay.updateImage(rendered)
        } catch (e: CancellationException) {
            FgoLogger.debug(tag, "Translation processing cancelled")
            throw e
        } catch (e: Exception) {
            FgoLogger.error(tag, "processScreen failed", e)
        } finally {
            screenshot?.recycle()
            if (restoreHiddenOverlay && processingVersion == stopVersion && !translationOverlay.isShowing()) {
                translationOverlay.restoreAfterCapture()
            }
            isProcessing = false
        }
    }

    private suspend fun buildRenderInstructions(regions: List<ClassifiedRegion>): List<RenderInstruction> {
        suspend fun translateRegion(region: ClassifiedRegion): RenderInstruction? {
            val sourceText = region.lines.joinToString("\n") { it.text }.trim()
            if (sourceText.isBlank()) return null
            return RenderInstruction(
                region = region,
                translatedText = translator.translate(sourceText).translatedText
            )
        }

        val hasDialogueAndName = regions.any { it.region == TextRegion.DIALOGUE_BOX } &&
            regions.any { it.region == TextRegion.NAME_LABEL }
        return if (hasDialogueAndName) {
            coroutineScope {
                regions.map { region -> async { translateRegion(region) } }
                    .awaitAll()
                    .filterNotNull()
            }
        } else {
            regions.mapNotNull { translateRegion(it) }
        }
    }

    private suspend fun recognizeScreenRegion(
        source: Bitmap,
        bounds: Rect,
        region: TextRegion
    ): ClassifiedRegion? {
        val cropped = Bitmap.createBitmap(
            source,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height()
        )
        return try {
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(cropped)
            }
            val lines = ocrResult.lines.toScreenCoordinates(bounds)
            if (lines.isEmpty()) {
                null
            } else {
                ClassifiedRegion(
                    region = region,
                    lines = lines,
                    boundingBox = bounds
                )
            }
        } finally {
            cropped.recycle()
        }
    }

    private suspend fun recognizeChoiceRegions(
        source: Bitmap,
        screenRegions: FgoScreenRegions
    ): List<ClassifiedRegion> {
        val choiceBounds = withContext(Dispatchers.Default) {
            backgroundDetector.detectChoiceButtons(source, screenRegions.choiceSearch)
        }
        val choiceRegions = coroutineScope {
            choiceBounds.map { bounds ->
                async { recognizeScreenRegion(source, bounds, TextRegion.CHOICE_BUTTON) }
            }.awaitAll().filterNotNull()
        }
        return choiceRegions
    }

    private suspend fun recognizeDialogueRegions(
        source: Bitmap,
        screenRegions: FgoScreenRegions
    ): List<ClassifiedRegion> {
        return coroutineScope {
            listOf(
                async {
                    recognizeScreenRegion(source, screenRegions.dialogue, TextRegion.DIALOGUE_BOX)
                },
                async {
                    recognizeScreenRegion(source, screenRegions.name, TextRegion.NAME_LABEL)
                }
            ).awaitAll().filterNotNull()
        }
    }

    private fun fingerprintFor(regions: List<ClassifiedRegion>): String {
        return regions.joinToString("\n\n") { region ->
            "${region.region}:${region.boundingBox.flattenToString()}\n" +
                region.lines.joinToString("\n") { it.text }.trim()
        }.trim()
    }

    private suspend fun isSourceFingerprintCurrent(expectedFingerprint: String): Boolean {
        val currentScreenshot = takeScreenshotCompat() ?: return false
        return try {
            val screenRegions = FgoViewportLayout.regionsForScreen(
                currentScreenshot.width,
                currentScreenshot.height
            )
            if (!confirmSkipMarker(currentScreenshot, screenRegions.skip)) return false
            if (backgroundDetector.isSkipConfirmationVisible(
                    currentScreenshot,
                    screenRegions.skipConfirmationNoButton,
                    screenRegions.skipConfirmationYesButton
                )
            ) {
                return false
            }
            val currentRegions = if (expectedFingerprint.contains(TextRegion.DIALOGUE_BOX.name)) {
                if (!backgroundDetector.isDialogueCompleteMarkerVisible(
                    currentScreenshot,
                    screenRegions.dialogueComplete
                )) {
                    return false
                }
                recognizeDialogueRegions(currentScreenshot, screenRegions)
            } else {
                recognizeChoiceRegions(currentScreenshot, screenRegions)
            }
            currentRegions.isNotEmpty() && fingerprintFor(currentRegions) == expectedFingerprint
        } finally {
            currentScreenshot.recycle()
        }
    }

    private suspend fun confirmSkipMarker(source: Bitmap, bounds: Rect): Boolean {
        val visualCandidate = withContext(Dispatchers.Default) {
            backgroundDetector.isSkipButtonVisible(source, bounds)
        }
        if (!visualCandidate) return false

        val cropped = Bitmap.createBitmap(
            source,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height()
        )
        return try {
            val result = withContext(Dispatchers.Default) {
                ocrEngine.recognize(cropped)
            }
            val lettersOnly = result.fullText.uppercase().filter { it in 'A'..'Z' }
            val confirmed = "SKIP" in lettersOnly
            FgoLogger.debug(
                tag,
                "SKIP OCR confirmed=$confirmed detected='${result.fullText.replace("\n", " ")}'"
            )
            confirmed
        } finally {
            cropped.recycle()
        }
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

    private fun handleTranslatedOverlayTap(x: Float, y: Float) {
        if (isProcessing) {
            FgoLogger.debug(tag, "Ignoring translated overlay tap while processing")
            return
        }

        val tappedChoice = renderedChoiceBounds.any { it.contains(x.toInt(), y.toInt()) }
        if (tappedChoice && TranslationTrigger.isAutoTranslateEnabled()) {
            waitingForChoiceSelectionExit = true
            renderedChoiceBounds = emptyList()
            FgoLogger.debug(tag, "Translated choice tapped; suppressing choice OCR until selection closes")
        }

        serviceScope.launch {
            if (!canPerformGestures()) {
                FgoLogger.warn(tag, "Gesture injection is not granted; disable and re-enable accessibility service")
                return@launch
            }
            FgoLogger.debug(tag, "Translated overlay tapped; forwarding to FGO at $x,$y")
            translationOverlay.hideForCapture()
            delay(TAP_OVERLAY_REMOVAL_DELAY)
            val dispatched = try {
                withTimeoutOrNull(TAP_REPLAY_TIMEOUT) {
                    dispatchTapToFgo(x, y)
                } ?: run {
                    FgoLogger.warn(tag, "Overlay tap replay timed out")
                    false
                }
            } catch (e: Exception) {
                FgoLogger.error(tag, "Overlay tap replay failed", e)
                false
            }
            if (dispatched) {
                FgoLogger.debug(tag, "Overlay tap replay completed; waiting for changed dialogue")
            } else {
                FgoLogger.warn(tag, "Overlay tap replay failed; restoring current translation")
                translationOverlay.restoreAfterCapture()
            }
        }
    }

    private fun canPerformGestures(): Boolean {
        return serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
    }

    @Suppress("NewApi")
    private suspend fun dispatchTapToFgo(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()

        return suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
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

    private fun AccessibilityEvent.isDialogueAdvanceEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

}
