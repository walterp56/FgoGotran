package com.fgogotran.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Color
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
import com.fgogotran.crop.CropResultOverlay
import com.fgogotran.crop.CropResultRenderer
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.ocr.OcrTextCorrector
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.overlay.BackgroundDetector
import com.fgogotran.overlay.ClassifiedRegion
import com.fgogotran.overlay.FgoScreenRegions
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.overlay.OverlayRenderer
import com.fgogotran.overlay.RenderInstruction
import com.fgogotran.overlay.TextRegion
import com.fgogotran.overlay.TranslationOverlay
import com.fgogotran.runner.FgoRunnerOverlay
import com.fgogotran.story.StoryDetector
import com.fgogotran.translation.SceneTranslateInput
import com.fgogotran.translation.SceneTranslateResult
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.translation.TranslateResult
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    @Inject lateinit var cropResultOverlay: CropResultOverlay
    @Inject lateinit var cropResultRenderer: CropResultRenderer
    @Inject lateinit var runnerOverlay: FgoRunnerOverlay

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false
    private var translationJob: Job? = null
    private var cropTranslationJob: Job? = null
    private var transientForegroundLossJob: Job? = null
    private var transientForegroundLossStartedAt = 0L
    private var stopVersion = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var isFgoForeground = false
    private var lastManualRenderedSourceText = ""
    private var lastAutoRenderedSourceText = ""
    private var autoScanReadyAt = 0L
    private var renderedChoiceBounds: List<Rect> = emptyList()
    private var waitingForChoiceSelectionExit = false
    private var isForwardingOverlayTap = false
    private var choiceOcrSuppressedUntil = 0L
    private var suppressedChoiceBoundsKey = ""
    private var emptyChoiceOcrStreak = 0
    private var tapAdvancePolling = false
    private var earlyTranslationJob: Job? = null
    private var earlyTranslationFingerprint = ""
    private var earlyTranslationResult: EarlyTranslationResult? = null
    private var failedAutoRenderFingerprint = ""
    private var failedAutoRenderRetryAt = 0L
    private var typingCandidateFingerprint = ""
    private var typingCandidateFirstSeenAt = 0L
    private var completedDialogueCandidateFingerprint = ""
    private var completedDialogueCandidateFirstSeenAt = 0L
    private var completedDialogueCandidateSeenCount = 0
    private var autoTapHandoffPreviousFingerprint = ""

    companion object {
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"
        private const val APP_PACKAGE = "com.fgogotran"
        private const val DETECTION_INTERVAL = 120L
        private const val CAPTURE_SETTLE_DELAY = 16L
        private const val MANUAL_MENU_DISMISS_SETTLE_DELAY = 300L
        private const val TRANSIENT_SYSTEM_UI_FOREGROUND_RECHECK_DELAY = 3_000L
        private const val TRANSIENT_SYSTEM_UI_FOREGROUND_MAX_DELAY = 30_000L
        private const val TAP_TRANSLATION_READ_HOLD_DELAY = 120L
        private const val NEXT_DIALOGUE_POLL_INTERVAL = 120L
        private const val NEXT_DIALOGUE_POLL_TIMEOUT = 2_500L
        private const val TAP_PASSTHROUGH_SETTLE_DELAY = 32L
        private const val TAP_REPLAY_TIMEOUT = 500L
        private const val CROP_TRANSLATION_WAIT_TIMEOUT = 700L
        private const val CROP_OCR_SCALE = 2
        private const val EMPTY_CHOICE_OCR_BASE_COOLDOWN = 600L
        private const val EMPTY_CHOICE_OCR_MAX_COOLDOWN = 1_200L
        private const val FRESHNESS_CHECK_TRANSLATION_DELAY = 800L
        private const val VISUAL_FINGERPRINT_STEP = 3
        private const val VISUAL_FINGERPRINT_MAX_DIFF_RATIO = 0.035f
        private const val EARLY_TRANSLATION_STABLE_DELAY = 80L
        private const val EARLY_TRANSLATION_MIN_DIALOGUE_CHARS = 6
        private const val COMPLETED_DIALOGUE_STABLE_DELAY = 160L
        private const val COMPLETED_DIALOGUE_STABLE_SCANS = 2
        private const val TAP_HANDOFF_COMPLETED_DIALOGUE_STABLE_DELAY = 180L
        private const val TAP_HANDOFF_COMPLETED_DIALOGUE_STABLE_SCANS = 2
        private const val AUTO_FAILED_TRANSLATION_RETRY_COOLDOWN = 5_000L
        private const val RUBY_MAX_CHARS = 14
        private const val RUBY_MAX_BASE_CHARS = 12
        private const val RUBY_HEIGHT_RATIO = 0.72f
        private const val MIN_PALETTE_TEXT_PIXELS = 8
        private val FGO_RENDER_WHITE = Color.rgb(245, 245, 240)
        private val FGO_TEXT_COLOR_SAMPLES = listOf(
            TextColorSample(
                sampleColor = Color.rgb(245, 245, 240),
                renderColor = Color.rgb(245, 245, 240),
                maxDistanceSquared = 120 * 120
            ),
            TextColorSample(
                sampleColor = Color.rgb(255, 80, 80),
                renderColor = Color.rgb(255, 80, 80),
                maxDistanceSquared = 100 * 100
            ),
            TextColorSample(
                sampleColor = Color.rgb(80, 235, 235),
                renderColor = Color.rgb(80, 235, 235),
                maxDistanceSquared = 115 * 115
            ),
            TextColorSample(
                sampleColor = Color.rgb(197, 227, 94),
                renderColor = Color.rgb(197, 227, 94),
                maxDistanceSquared = 90 * 90
            )
        )

        private val _serviceStarted = mutableStateOf(false)
        val serviceStarted: State<Boolean>
            get() = _serviceStarted

        private val TRANSIENT_SYSTEM_UI_PACKAGES = setOf(
            "com.android.systemui"
        )

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

    private data class RegionSourceText(
        val region: ClassifiedRegion,
        val text: String
    )

    private data class SceneSource(
        val regions: List<RegionSourceText>,
        val input: SceneTranslateInput,
        val fingerprint: String,
        val hasDialogue: Boolean,
        val dialogueCharCount: Int
    )

    private data class EarlyTranslationResult(
        val fingerprint: String,
        val result: SceneTranslateResult
    )

    private data class OcrRegionTarget(
        val bounds: Rect,
        val region: TextRegion
    )

    private data class ChoiceRecognitionResult(
        val bounds: List<Rect>,
        val regions: List<ClassifiedRegion>
    )

    private data class ManualScanResult(
        val regions: List<ClassifiedRegion>,
        val dialogueComplete: Boolean
    )

    private enum class RubyDetectionMode {
        STRICT,
        PERMISSIVE
    }

    private sealed class AutoScanResult {
        data class Ready(
            val regions: List<ClassifiedRegion>
        ) : AutoScanResult()

        data class EarlyTyping(
            val sceneSource: SceneSource
        ) : AutoScanResult()

        object Waiting : AutoScanResult()
        object EmptyCompletedDialogue : AutoScanResult()
    }

    private enum class ProcessingMode {
        MANUAL,
        AUTO
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initScreenSize()
        translationOverlay.init(this, screenWidth, screenHeight) { x, y ->
            handleTranslatedOverlayTap(x, y)
        }
        cropResultOverlay.init(this) { x, y ->
            handleCropResultTap(x, y)
        }
        TranslationTrigger.setAutoTranslateEnabled(false)
        startDetectionLoop()
        warmUpManualPipeline()
        FgoLogger.info(tag, "Gesture injection available: ${canPerformGestures()}")
        FgoLogger.info(tag, "Service connected: ${screenWidth}x${screenHeight}")
    }

    private fun warmUpManualPipeline() {
        serviceScope.launch(Dispatchers.Default) {
            ocrEngine.warmUp()
        }
        serviceScope.launch(Dispatchers.IO) {
            translator.warmUp()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        val isFgoEvent = packageName.isSupportedFgoPackage()

        when {
            packageName == APP_PACKAGE -> {
                if (isFgoForeground) {
                    cancelTransientForegroundLoss()
                    translationOverlay.showIndicator()
                }
                // Our overlays emit window/touch events when they appear or redraw. Treat them as UI noise.
            }
            isFgoEvent -> {
                cancelTransientForegroundLoss()
                if (!isFgoForeground) {
                    FgoLogger.info(tag, "FGO foreground detected: event=$packageName")
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
                if (isFgoForeground && packageName.isTransientSystemUiPackage()) {
                    scheduleTransientForegroundLoss(packageName)
                } else {
                    cancelTransientForegroundLoss()
                    markFgoForegroundLost(packageName)
                }
            }
        }
    }

    private fun scheduleTransientForegroundLoss(packageName: String) {
        transientForegroundLossJob?.cancel()
        if (transientForegroundLossStartedAt == 0L) {
            transientForegroundLossStartedAt = SystemClock.elapsedRealtime()
        }
        FgoLogger.debug(tag, "Transient system UI event while FGO foreground; delaying foreground loss: event=$packageName")
        transientForegroundLossJob = serviceScope.launch {
            delay(TRANSIENT_SYSTEM_UI_FOREGROUND_RECHECK_DELAY)
            transientForegroundLossJob = null
            val activePackage = rootInActiveWindow?.packageName?.toString()
            when {
                activePackage?.isSupportedFgoPackage() == true -> {
                    cancelTransientForegroundLoss()
                    FgoLogger.debug(tag, "FGO still active after transient system UI; keeping foreground")
                    translationOverlay.showIndicator()
                }
                activePackage == null ||
                        activePackage == APP_PACKAGE ||
                        activePackage.isTransientSystemUiPackage() -> {
                    val waitingFor = SystemClock.elapsedRealtime() - transientForegroundLossStartedAt
                    if (waitingFor >= TRANSIENT_SYSTEM_UI_FOREGROUND_MAX_DELAY) {
                        FgoLogger.debug(
                            tag,
                            "Transient system UI persisted for ${waitingFor}ms; clearing FGO foreground"
                        )
                        markFgoForegroundLost(packageName, delayed = true)
                    } else {
                        FgoLogger.debug(
                            tag,
                            "Transient system UI still active after ${waitingFor}ms; keeping FGO foreground"
                        )
                        scheduleTransientForegroundLoss(packageName)
                    }
                }
                else -> {
                    markFgoForegroundLost(activePackage, delayed = true)
                }
            }
        }
    }

    private fun cancelTransientForegroundLoss() {
        transientForegroundLossJob?.cancel()
        transientForegroundLossJob = null
        transientForegroundLossStartedAt = 0L
    }

    private fun markFgoForegroundLost(packageName: String, delayed: Boolean = false) {
        if (isFgoForeground) {
            val delayLabel = if (delayed) " after transient delay" else ""
            FgoLogger.info(tag, "FGO foreground lost$delayLabel: event=$packageName")
        }
        isFgoForeground = false
        cancelTransientForegroundLoss()
        resetEarlyTranslation()
        resetCompletedDialogueCandidate()
        translationOverlay.hideAll()
        cropResultOverlay.hide()
    }

    override fun onInterrupt() {
        FgoLogger.warn(tag, "Service interrupted")
        cancelTransientForegroundLoss()
        translationOverlay.hideAll()
        cropResultOverlay.hide()
        serviceScope.cancel()
    }

    override fun onDestroy() {
        instance = null
        cancelTransientForegroundLoss()
        translationOverlay.destroy()
        cropResultOverlay.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun setAutoTranslationEnabled(enabled: Boolean) {
        TranslationTrigger.setAutoTranslateEnabled(enabled)
        cancelCurrentTranslation()
        cropResultOverlay.hide()
        if (enabled) {
            autoScanReadyAt = SystemClock.elapsedRealtime() + MANUAL_MENU_DISMISS_SETTLE_DELAY
            FgoLogger.debug(tag, "Auto translate enabled")
        } else {
            autoScanReadyAt = 0L
            FgoLogger.debug(tag, "Auto translate disabled")
        }
    }

    fun requestManualTranslation(afterMenuDismiss: Boolean = false): Boolean {
        if (TranslationTrigger.isAutoTranslateEnabled()) return false
        cropResultOverlay.hide()

        if (!canStartScreenTranslationNow()) {
            FgoLogger.debug(
                tag,
                "Manual translation queued: foreground=$isFgoForeground, " +
                        "processing=$isProcessing, tapPolling=$tapAdvancePolling, " +
                        "uiBlocking=${TranslationTrigger.isUiBlockingOcr()}"
            )
            TranslationTrigger.requestTranslation(afterMenuDismiss)
            return true
        }

        startManualTranslation(afterMenuDismiss)
        return true
    }

    private fun canStartScreenTranslationNow(): Boolean {
        return !isProcessing &&
                !tapAdvancePolling &&
                !TranslationTrigger.isUiBlockingOcr()
    }

    private fun startManualTranslation(afterMenuDismiss: Boolean = false) {
        if (!isFgoForeground) {
            FgoLogger.debug(tag, "Manual translation requested while FGO foreground flag is stale; attempting capture")
        }
        TranslationTrigger.cancelPendingTranslation()
        translationJob = serviceScope.launch {
            if (afterMenuDismiss) delay(MANUAL_MENU_DISMISS_SETTLE_DELAY)
            processScreen(ProcessingMode.MANUAL)
        }
    }

    fun requestCropTranslation(bounds: Rect): Boolean {
        if (!isFgoForeground || TranslationTrigger.isUiBlockingOcr()) {
            FgoLogger.warn(tag, "Crop translation rejected; FGO foreground=$isFgoForeground")
            return false
        }

        TranslationTrigger.setAutoTranslateEnabled(false)
        cancelCurrentTranslation()
        cropTranslationJob = serviceScope.launch {
            val deadline = SystemClock.elapsedRealtime() + CROP_TRANSLATION_WAIT_TIMEOUT
            while (isProcessing && SystemClock.elapsedRealtime() < deadline) {
                delay(CAPTURE_SETTLE_DELAY)
            }
            if (isProcessing) {
                FgoLogger.warn(tag, "Crop translation skipped; previous pipeline is still busy")
                showCropStatus(bounds, "请稍后再试")
                return@launch
            }
            processCropTranslation(Rect(bounds))
        }
        return true
    }

    fun clearCropTranslationOverlay() {
        cropTranslationJob?.cancel()
        cropTranslationJob = null
        cropResultOverlay.hide()
    }

    private fun cancelCurrentTranslation() {
        stopVersion++
        TranslationTrigger.cancelPendingTranslation()
        translationJob?.cancel()
        cropTranslationJob?.cancel()
        translationJob = null
        cropTranslationJob = null
        resetEarlyTranslation()
        resetCompletedDialogueCandidate()
        lastManualRenderedSourceText = ""
        lastAutoRenderedSourceText = ""
        failedAutoRenderFingerprint = ""
        failedAutoRenderRetryAt = 0L
        renderedChoiceBounds = emptyList()
        waitingForChoiceSelectionExit = false
        isForwardingOverlayTap = false
        autoTapHandoffPreviousFingerprint = ""
        choiceOcrSuppressedUntil = 0L
        suppressedChoiceBoundsKey = ""
        emptyChoiceOcrStreak = 0
        translationOverlay.hide()
        cropResultOverlay.hide()
    }

    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (canStartScreenTranslationNow()) {
                        val autoEnabled = TranslationTrigger.isAutoTranslateEnabled()
                        val manualRequest = if (autoEnabled) false else TranslationTrigger.consumeRequest()
                        if (manualRequest) {
                            FgoLogger.debug(tag, "Translate Now requested")
                            val waitForMenuDismissal = TranslationTrigger.consumeMenuDismissSettleRequired()
                            cropResultOverlay.hide()
                            startManualTranslation(waitForMenuDismissal)
                        } else if (isFgoForeground &&
                            autoEnabled &&
                            !translationOverlay.isShowing() &&
                            SystemClock.elapsedRealtime() >= autoScanReadyAt
                        ) {
                            translationJob = serviceScope.launch {
                                processScreen(ProcessingMode.AUTO)
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

    private suspend fun processScreen(mode: ProcessingMode) {
        if (isProcessing) return
        if (TranslationTrigger.isUiBlockingOcr()) {
            FgoLogger.debug(tag, "Overlay UI visible; skipping OCR")
            return
        }
        isProcessing = true
        val processStartedAt = SystemClock.elapsedRealtime()
        val processingVersion = stopVersion

        var screenshot: Bitmap? = null
        var restoreHiddenOverlay = false
        try {
            if (translationOverlay.isShowing()) {
                if (mode == ProcessingMode.AUTO) return
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
            FgoLogger.debug(tag, "FGO viewport=${screenRegions.viewport}")

            restoreHiddenOverlay = when (mode) {
                ProcessingMode.MANUAL -> processManualScreen(
                    source = source,
                    screenRegions = screenRegions,
                    currentScreenWidth = currentScreenWidth,
                    currentScreenHeight = currentScreenHeight,
                    processStartedAt = processStartedAt,
                    processingVersion = processingVersion,
                    restoreHiddenOverlay = restoreHiddenOverlay
                )
                ProcessingMode.AUTO -> {
                    processAutoScreen(
                        source = source,
                        screenRegions = screenRegions,
                        currentScreenWidth = currentScreenWidth,
                        currentScreenHeight = currentScreenHeight,
                        processStartedAt = processStartedAt,
                        processingVersion = processingVersion
                    )
                    false
                }
            }
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

    private suspend fun processCropTranslation(requestedBounds: Rect) {
        if (isProcessing) return
        isProcessing = true
        val startedAt = SystemClock.elapsedRealtime()
        var screenshot: Bitmap? = null
        var cropped: Bitmap? = null
        var scaledForOcr: Bitmap? = null

        try {
            translationOverlay.hideForCapture()
            cropResultOverlay.hide()
            delay(CAPTURE_SETTLE_DELAY)

            screenshot = takeScreenshotCompat()
            if (screenshot == null) {
                showCropStatus(requestedBounds, "截图失败")
                return
            }

            val cropBounds = clippedCropBounds(requestedBounds, screenshot.width, screenshot.height)
            if (cropBounds == null) {
                showCropStatus(requestedBounds, "区域太小")
                return
            }

            val cropBitmap = Bitmap.createBitmap(
                screenshot,
                cropBounds.left,
                cropBounds.top,
                cropBounds.width(),
                cropBounds.height()
            )
            cropped = cropBitmap
            val ocrBitmap = if (cropBitmap.width < 900 || cropBitmap.height < 500) {
                scaledForOcr = Bitmap.createScaledBitmap(
                    cropBitmap,
                    cropBitmap.width * CROP_OCR_SCALE,
                    cropBitmap.height * CROP_OCR_SCALE,
                    false
                )
                scaledForOcr!!
            } else {
                cropBitmap
            }

            val ocrStartedAt = SystemClock.elapsedRealtime()
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(ocrBitmap)
            }
            val sourceText = cropSourceText(ocrResult.lines, ocrResult.fullText)
            val ocrDuration = SystemClock.elapsedRealtime() - ocrStartedAt

            if (sourceText.isBlank()) {
                showCropStatus(cropBounds, "未识别到文字")
                FgoLogger.info(tag, "Crop OCR found no text in ${ocrDuration}ms")
                return
            }

            val translationStartedAt = SystemClock.elapsedRealtime()
            val translated = withContext(Dispatchers.IO) {
                translator.translate(sourceText, preserveRubyMeaning = true).translatedText.trim()
            }.ifBlank {
                "翻译失败"
            }
            val translationDuration = SystemClock.elapsedRealtime() - translationStartedAt

            val cropTextColor = sampleCropOriginalTextColor(
                crop = cropBitmap,
                lines = ocrResult.lines,
                coordinateScale = if (scaledForOcr != null) CROP_OCR_SCALE else 1
            )
            val rendered = withContext(Dispatchers.Default) {
                cropResultRenderer.render(
                    width = cropBounds.width(),
                    height = cropBounds.height(),
                    text = translated,
                    textColor = cropTextColor ?: FGO_RENDER_WHITE
                )
            }
            cropResultOverlay.show(cropBounds, rendered)
            FgoLogger.info(
                tag,
                "Crop pipeline ready: ocr=${ocrDuration}ms, translate=${translationDuration}ms, " +
                        "total=${SystemClock.elapsedRealtime() - startedAt}ms, bounds=${cropBounds.flattenToString()}"
            )
        } catch (e: CancellationException) {
            FgoLogger.debug(tag, "Crop translation cancelled")
            throw e
        } catch (e: Exception) {
            FgoLogger.error(tag, "Crop translation failed", e)
            showCropStatus(requestedBounds, "翻译失败")
        } finally {
            scaledForOcr?.recycle()
            cropped?.recycle()
            screenshot?.recycle()
            isProcessing = false
        }
    }

    private fun showCropStatus(bounds: Rect, message: String) {
        val safeWidth = bounds.width().coerceAtLeast(180)
        val safeHeight = bounds.height().coerceAtLeast(96)
        val bitmap = cropResultRenderer.render(safeWidth, safeHeight, message)
        cropResultOverlay.show(
            Rect(bounds.left, bounds.top, bounds.left + safeWidth, bounds.top + safeHeight),
            bitmap
        )
    }

    private fun clippedCropBounds(bounds: Rect, screenWidth: Int, screenHeight: Int): Rect? {
        val clipped = Rect(bounds)
        if (!clipped.intersect(0, 0, screenWidth, screenHeight)) return null
        if (clipped.width() < 32 || clipped.height() < 32) return null
        return clipped
    }

    private fun cropSourceText(lines: List<OcrTextLine>, fullText: String): String {
        val cleanedText = formatDialogueForTranslation(lines, RubyDetectionMode.PERMISSIVE)
        val sourceText = cleanedText.ifBlank {
            if (lines.any { isRubyDotNoiseLine(it) }) "" else fullText.trim()
        }
        return correctOcrSourceText(sourceText, "CROP")
    }

    private suspend fun processManualScreen(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        currentScreenWidth: Int,
        currentScreenHeight: Int,
        processStartedAt: Long,
        processingVersion: Long,
        restoreHiddenOverlay: Boolean
    ): Boolean {
        val scan = scanManualScene(source, screenRegions)
        if (scan.regions.isEmpty()) {
            if (scan.dialogueComplete) {
                resetEarlyTranslation()
                FgoLogger.debug(tag, "No translatable completed dialogue detected in FGO regions")
                translationOverlay.hide()
                return false
            }
            FgoLogger.debug(tag, "Manual OCR found no dialogue and no choices")
            return restoreHiddenOverlay
        }

        FgoLogger.debug(tag, "Manual path uses fixed dialogue/choice regions without story guard")
        val sceneSource = sceneSourceFor(scan.regions)
        if (sceneSource == null) {
            translationOverlay.hide()
            return false
        }

        if (restoreHiddenOverlay &&
            sceneSource.fingerprint == lastRenderedSourceTextFor(ProcessingMode.MANUAL)
        ) {
            FgoLogger.debug(tag, "Manual source unchanged; restoring previous overlay without translation")
            return true
        }

        translateAndRenderScene(
            mode = ProcessingMode.MANUAL,
            source = source,
            currentScreenWidth = currentScreenWidth,
            currentScreenHeight = currentScreenHeight,
            processStartedAt = processStartedAt,
            processingVersion = processingVersion,
            sceneSource = sceneSource,
            recognitionDuration = SystemClock.elapsedRealtime() - processStartedAt
        )
        return false
    }

    private suspend fun scanManualScene(
        source: Bitmap,
        screenRegions: FgoScreenRegions
    ): ManualScanResult {
        val choiceRecognition = recognizeChoiceRegions(source, screenRegions, ProcessingMode.MANUAL)
        val choiceRegions = choiceRecognition.regions
        if (choiceRegions.isNotEmpty()) {
            resetEarlyTranslation(clearTypingCandidate = false)
            resetCompletedDialogueCandidate()
            FgoLogger.debug(tag, "Manual choice text detected; reading dialogue for context")
            val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
            return ManualScanResult(
                regions = mergeManualSceneRegions(choiceRegions, dialogueRegions),
                dialogueComplete = false
            )
        }

        val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
        val dialogueScene = sceneSourceFor(dialogueRegions)
        resetEarlyTranslation()
        resetCompletedDialogueCandidate()
        if (dialogueScene?.hasDialogue == true) {
            if (choiceRecognition.bounds.isEmpty()) {
                FgoLogger.debug(tag, "Manual dialogue OCR hit; no choice panels detected")
            } else {
                FgoLogger.debug(tag, "Manual dialogue OCR hit; choice panels had no readable text")
            }
            return ManualScanResult(regions = dialogueRegions, dialogueComplete = false)
        }

        if (choiceRecognition.bounds.isNotEmpty()) {
            FgoLogger.debug(tag, "Manual choice panels detected but OCR returned no text")
        }
        val dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
            source,
            screenRegions.dialogueComplete
        )
        return ManualScanResult(regions = emptyList(), dialogueComplete = dialogueComplete)
    }

    private suspend fun processAutoScreen(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        currentScreenWidth: Int,
        currentScreenHeight: Int,
        processStartedAt: Long,
        processingVersion: Long
    ) {
        when (val scan = scanAutoScene(source, screenRegions, currentScreenWidth, currentScreenHeight)) {
            is AutoScanResult.Ready -> {
                val sceneSource = sceneSourceFor(scan.regions)
                if (sceneSource == null) {
                    translationOverlay.hide()
                    return
                }
                if (sceneSource.fingerprint == lastRenderedSourceTextFor(ProcessingMode.AUTO)) {
                    FgoLogger.debug(tag, "Auto source unchanged; waiting for new OCR text")
                    return
                }
                if (isAutoFailedRenderCoolingDown(sceneSource)) {
                    return
                }
                if (shouldHoldAutoTapHandoffScene(
                        sceneSource = sceneSource,
                        regions = scan.regions,
                        currentScreenWidth = currentScreenWidth,
                        currentScreenHeight = currentScreenHeight
                    )
                ) {
                    return
                }
                val completedStableDelay = if (isAutoTapHandoffActive()) {
                    TAP_HANDOFF_COMPLETED_DIALOGUE_STABLE_DELAY
                } else {
                    COMPLETED_DIALOGUE_STABLE_DELAY
                }
                val completedStableScans = if (isAutoTapHandoffActive()) {
                    TAP_HANDOFF_COMPLETED_DIALOGUE_STABLE_SCANS
                } else {
                    COMPLETED_DIALOGUE_STABLE_SCANS
                }
                if (sceneSource.hasDialogue &&
                    sceneSource.input.choices.isEmpty() &&
                    !isCompletedDialogueStable(
                        sceneSource = sceneSource,
                        stableDelay = completedStableDelay,
                        stableScans = completedStableScans
                    )
                ) {
                    maybeStartEarlyTranslation(
                        sceneSource = sceneSource,
                        processingVersion = processingVersion,
                        requireStableTypingCandidate = false
                    )
                    return
                }
                translateAndRenderScene(
                    mode = ProcessingMode.AUTO,
                    source = source,
                    currentScreenWidth = currentScreenWidth,
                    currentScreenHeight = currentScreenHeight,
                    processStartedAt = processStartedAt,
                    processingVersion = processingVersion,
                    sceneSource = sceneSource,
                    recognitionDuration = SystemClock.elapsedRealtime() - processStartedAt
                )
            }
            is AutoScanResult.EarlyTyping -> {
                maybeStartEarlyTranslation(scan.sceneSource, processingVersion)
            }
            AutoScanResult.EmptyCompletedDialogue -> {
                resetEarlyTranslation()
                FgoLogger.debug(tag, "No translatable completed dialogue detected in FGO regions")
                translationOverlay.hide()
            }
            AutoScanResult.Waiting -> Unit
        }
    }

    private suspend fun scanAutoScene(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        currentScreenWidth: Int,
        currentScreenHeight: Int
    ): AutoScanResult {
        val choiceBounds = detectChoiceBounds(source, screenRegions)
        if (waitingForChoiceSelectionExit) {
            if (choiceBounds.isNotEmpty()) {
                FgoLogger.debug(tag, "Choice selection is still leaving the screen; suppressing repeated choice translation")
                return AutoScanResult.Waiting
            }
            waitingForChoiceSelectionExit = false
            FgoLogger.debug(tag, "Choice selection left the screen; resuming auto translation")
        }

        if (choiceBounds.size == 1) {
            val dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
                source,
                screenRegions.dialogueComplete
            )
            val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
            val dialogueScene = sceneSourceFor(dialogueRegions)

            if (dialogueScene?.hasDialogue == true) {
                val choiceRecognition = recognizeChoiceRegions(
                    source = source,
                    choiceBounds = choiceBounds,
                    mode = ProcessingMode.AUTO,
                    retryEmptyTargetsIndividually = false,
                    allowAutoSingleChoiceFallback = false
                )
                val choiceRegions = choiceRecognition.regions
                if (choiceRegions.isNotEmpty()) {
                    resetEarlyTranslation(clearTypingCandidate = false)
                    resetCompletedDialogueCandidate()
                    FgoLogger.debug(tag, "Auto single-choice text detected with dialogue context")
                    val sceneRegions = mergeManualSceneRegions(choiceRegions, dialogueRegions)
                    logAutoStoryDetection("Choice", sceneRegions, currentScreenWidth, currentScreenHeight)
                    return AutoScanResult.Ready(regions = sceneRegions)
                }

                FgoLogger.debug(tag, "Auto dialogue OCR hit before single-choice OCR fallback")
                if (dialogueComplete) {
                    logAutoStoryDetection(
                        "Completed dialogue before single choice",
                        dialogueRegions,
                        currentScreenWidth,
                        currentScreenHeight
                    )
                    return AutoScanResult.Ready(regions = dialogueRegions)
                }

                resetCompletedDialogueCandidate()
                logAutoStoryDetection(
                    "Typing dialogue before single choice",
                    dialogueRegions,
                    currentScreenWidth,
                    currentScreenHeight
                )
                return AutoScanResult.EarlyTyping(dialogueScene)
            }

            val choiceRecognition = recognizeChoiceRegions(source, choiceBounds, ProcessingMode.AUTO)
            val choiceRegions = choiceRecognition.regions
            if (choiceRegions.isNotEmpty()) {
                resetEarlyTranslation(clearTypingCandidate = false)
                resetCompletedDialogueCandidate()
                FgoLogger.debug(tag, "Auto single-choice text detected after dialogue OCR miss")
                val sceneRegions = mergeManualSceneRegions(choiceRegions, dialogueRegions)
                logAutoStoryDetection("Choice", sceneRegions, currentScreenWidth, currentScreenHeight)
                return AutoScanResult.Ready(regions = sceneRegions)
            }

            resetEarlyTranslation(clearTypingCandidate = false)
            resetCompletedDialogueCandidate()
            FgoLogger.debug(tag, "Choice panels detected by pixels but OCR returned no text; waiting for readable choices")
            return AutoScanResult.Waiting
        }

        if (choiceBounds.size > 1) {
            val choiceRecognition = recognizeChoiceRegions(source, choiceBounds, ProcessingMode.AUTO)
            val choiceRegions = choiceRecognition.regions
            if (choiceRegions.isNotEmpty()) {
                resetEarlyTranslation(clearTypingCandidate = false)
                resetCompletedDialogueCandidate()
                FgoLogger.debug(tag, "Auto choice text detected; reading dialogue for same scene history as manual")
                val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
                val sceneRegions = mergeManualSceneRegions(choiceRegions, dialogueRegions)
                logAutoStoryDetection("Choice", sceneRegions, currentScreenWidth, currentScreenHeight)
                return AutoScanResult.Ready(regions = sceneRegions)
            }
        }

        val dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
            source,
            screenRegions.dialogueComplete
        )
        val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
        val dialogueScene = sceneSourceFor(dialogueRegions)

        if (choiceBounds.isNotEmpty()) {
            if (dialogueScene?.hasDialogue == true) {
                FgoLogger.debug(tag, "Auto dialogue OCR hit; choice panels had no readable text")
                if (dialogueComplete) {
                    logAutoStoryDetection(
                        "Completed dialogue after empty choice",
                        dialogueRegions,
                        currentScreenWidth,
                        currentScreenHeight
                    )
                    return AutoScanResult.Ready(regions = dialogueRegions)
                }

                resetCompletedDialogueCandidate()
                logAutoStoryDetection(
                    "Typing dialogue after empty choice",
                    dialogueRegions,
                    currentScreenWidth,
                    currentScreenHeight
                )
                return AutoScanResult.EarlyTyping(dialogueScene)
            }

            resetEarlyTranslation(clearTypingCandidate = false)
            resetCompletedDialogueCandidate()
            FgoLogger.debug(tag, "Choice panels detected by pixels but OCR returned no text; waiting for readable choices")
            return AutoScanResult.Waiting
        }

        if (dialogueComplete) {
            if (dialogueScene?.hasDialogue == true) {
                logAutoStoryDetection("Completed dialogue", dialogueRegions, currentScreenWidth, currentScreenHeight)
                return AutoScanResult.Ready(regions = dialogueRegions)
            }
            return AutoScanResult.EmptyCompletedDialogue
        }

        resetCompletedDialogueCandidate()
        if (dialogueScene?.hasDialogue == true) {
            logAutoStoryDetection("Typing dialogue", dialogueRegions, currentScreenWidth, currentScreenHeight)
            return AutoScanResult.EarlyTyping(dialogueScene)
        }

        FgoLogger.debug(tag, "Dialogue is still typing and no stable OCR text is visible")
        return AutoScanResult.Waiting
    }

    private fun logAutoStoryDetection(
        label: String,
        regions: List<ClassifiedRegion>,
        currentScreenWidth: Int,
        currentScreenHeight: Int
    ) {
        val detectedLines = regions.flatMap { it.lines }
        val storyResult = storyDetector.detect(detectedLines, currentScreenWidth, currentScreenHeight)
        FgoLogger.debug(tag, "$label story detection: ${storyResult.isStoryScene}, ${storyResult.reason}")
    }

    private fun isAutoTapHandoffActive(): Boolean {
        return autoTapHandoffPreviousFingerprint.isNotBlank()
    }

    private fun shouldHoldAutoTapHandoffScene(
        sceneSource: SceneSource,
        regions: List<ClassifiedRegion>,
        currentScreenWidth: Int,
        currentScreenHeight: Int
    ): Boolean {
        if (!isAutoTapHandoffActive()) return false
        if (sceneSource.fingerprint == autoTapHandoffPreviousFingerprint) {
            FgoLogger.debug(tag, "Auto tap handoff saw previous source text again; waiting")
            return true
        }
        if (sceneSource.input.choices.isNotEmpty()) return false
        if (!sceneSource.hasDialogue) {
            resetCompletedDialogueCandidate()
            FgoLogger.debug(tag, "Auto tap handoff saw name-only or empty dialogue OCR; waiting for dialogue text")
            return true
        }

        val storyResult = storyDetector.detect(
            regions.flatMap { it.lines },
            currentScreenWidth,
            currentScreenHeight
        )
        if (!storyResult.isStoryScene) {
            resetCompletedDialogueCandidate()
            FgoLogger.debug(tag, "Auto tap handoff rejected weak story OCR: ${storyResult.reason}")
            return true
        }
        return false
    }

    private suspend fun translateAndRenderScene(
        mode: ProcessingMode,
        source: Bitmap,
        currentScreenWidth: Int,
        currentScreenHeight: Int,
        processStartedAt: Long,
        processingVersion: Long,
        sceneSource: SceneSource,
        recognitionDuration: Long
    ): Boolean {
        val sourceFingerprint = sceneSource.fingerprint
        val translationStartedAt = SystemClock.elapsedRealtime()
        val sceneTranslation = translateSceneSource(sceneSource)
        val translationDuration = SystemClock.elapsedRealtime() - translationStartedAt
        val layoutStartedAt = SystemClock.elapsedRealtime()
        val instructions = withContext(Dispatchers.Default) {
            buildRenderInstructions(source, sceneSource, sceneTranslation)
        }
        val layoutDuration = SystemClock.elapsedRealtime() - layoutStartedAt

        missingRequiredRenderReason(sceneSource, instructions)?.let { reason ->
            FgoLogger.warn(tag, "Translation result incomplete; not marking source as rendered: $reason")
            rememberFailedRenderAttempt(mode, sourceFingerprint)
            translationOverlay.hide()
            return false
        }
        if (instructions.isEmpty()) {
            addHistoryEntry(source, sceneSource, instructions)
            translationOverlay.hide()
            return false
        }
        val resultBuildDuration = translationDuration + layoutDuration
        val forceVisualFreshness = mode == ProcessingMode.AUTO && isAutoTapHandoffActive()
        if (resultBuildDuration >= FRESHNESS_CHECK_TRANSLATION_DELAY || forceVisualFreshness) {
            val sourceVisualFingerprint = visualFingerprintFor(source, sceneSource.regions.map { it.region })
            if (!isSourceVisuallyCurrent(sourceVisualFingerprint)) {
                FgoLogger.debug(tag, "Dialogue changed during translation; discarding stale result")
                return false
            }
        }
        if (resultBuildDuration < FRESHNESS_CHECK_TRANSLATION_DELAY && !forceVisualFreshness) {
            FgoLogger.debug(
                tag,
                "Fast translation (${translationDuration}ms + layout ${layoutDuration}ms); rendering without OCR recheck"
            )
        } else if (forceVisualFreshness) {
            FgoLogger.debug(tag, "Auto tap handoff visual freshness check passed")
        }
        if (processingVersion != stopVersion) {
            FgoLogger.debug(tag, "Translation was stopped; discarding completed result")
            return false
        }
        if (TranslationTrigger.isUiBlockingOcr()) {
            FgoLogger.debug(tag, "Overlay UI opened during translation; discarding result")
            return false
        }

        val renderStartedAt = SystemClock.elapsedRealtime()
        val rendered = withContext(Dispatchers.Default) {
            overlayRenderer.render(
                bitmap = source,
                instructions = instructions,
                screenWidth = currentScreenWidth,
                screenHeight = currentScreenHeight
            )
        }
        val renderDuration = SystemClock.elapsedRealtime() - renderStartedAt
        rememberRenderedSourceText(mode, sourceFingerprint)
        clearFailedRenderAttempt(sourceFingerprint)
        resetCompletedDialogueCandidate()
        renderedChoiceBounds = if (mode == ProcessingMode.AUTO) {
            overlayRenderer.renderedChoiceBounds(
                instructions = instructions,
                screenWidth = currentScreenWidth,
                screenHeight = currentScreenHeight
            )
        } else {
            emptyList()
        }
        addHistoryEntry(source, sceneSource, instructions)
        val overlayStartedAt = SystemClock.elapsedRealtime()
        translationOverlay.updateImage(rendered)
        val overlayDuration = SystemClock.elapsedRealtime() - overlayStartedAt
        FgoLogger.info(
            tag,
            "Pipeline ready ($mode): ocr=${recognitionDuration}ms, translate=${translationDuration}ms, " +
                    "layout=${layoutDuration}ms, render=${renderDuration}ms, overlay=${overlayDuration}ms, " +
                    "total=${SystemClock.elapsedRealtime() - processStartedAt}ms"
        )
        return true
    }

    private fun lastRenderedSourceTextFor(mode: ProcessingMode): String {
        return when (mode) {
            ProcessingMode.MANUAL -> lastManualRenderedSourceText
            ProcessingMode.AUTO -> lastAutoRenderedSourceText
        }
    }

    private fun rememberRenderedSourceText(mode: ProcessingMode, fingerprint: String) {
        when (mode) {
            ProcessingMode.MANUAL -> lastManualRenderedSourceText = fingerprint
            ProcessingMode.AUTO -> lastAutoRenderedSourceText = fingerprint
        }
    }

    private fun isAutoFailedRenderCoolingDown(sceneSource: SceneSource): Boolean {
        if (sceneSource.fingerprint != failedAutoRenderFingerprint) return false
        val now = SystemClock.elapsedRealtime()
        if (now >= failedAutoRenderRetryAt) return false

        FgoLogger.debug(
            tag,
            "Auto failed translation cooldown active for same source: ${failedAutoRenderRetryAt - now}ms remaining"
        )
        return true
    }

    private fun rememberFailedRenderAttempt(mode: ProcessingMode, fingerprint: String) {
        if (mode != ProcessingMode.AUTO) return
        failedAutoRenderFingerprint = fingerprint
        failedAutoRenderRetryAt = SystemClock.elapsedRealtime() + AUTO_FAILED_TRANSLATION_RETRY_COOLDOWN
    }

    private fun clearFailedRenderAttempt(fingerprint: String) {
        if (failedAutoRenderFingerprint != fingerprint) return
        failedAutoRenderFingerprint = ""
        failedAutoRenderRetryAt = 0L
    }

    private fun resetEarlyTranslation(clearTypingCandidate: Boolean = true) {
        earlyTranslationJob?.cancel()
        earlyTranslationJob = null
        earlyTranslationFingerprint = ""
        earlyTranslationResult = null
        if (clearTypingCandidate) {
            typingCandidateFingerprint = ""
            typingCandidateFirstSeenAt = 0L
        }
    }

    private fun resetCompletedDialogueCandidate() {
        completedDialogueCandidateFingerprint = ""
        completedDialogueCandidateFirstSeenAt = 0L
        completedDialogueCandidateSeenCount = 0
    }

    private fun isCompletedDialogueStable(
        sceneSource: SceneSource,
        stableDelay: Long = COMPLETED_DIALOGUE_STABLE_DELAY,
        stableScans: Int = COMPLETED_DIALOGUE_STABLE_SCANS
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (sceneSource.fingerprint != completedDialogueCandidateFingerprint) {
            completedDialogueCandidateFingerprint = sceneSource.fingerprint
            completedDialogueCandidateFirstSeenAt = now
            completedDialogueCandidateSeenCount = 1
            if (stableDelay <= 0L &&
                stableScans <= 1
            ) {
                return true
            }
            FgoLogger.debug(tag, "Completed dialogue candidate changed; waiting for stable OCR")
            return false
        }

        completedDialogueCandidateSeenCount++
        val stableFor = now - completedDialogueCandidateFirstSeenAt
        val stable = stableFor >= stableDelay &&
                completedDialogueCandidateSeenCount >= stableScans
        if (!stable) {
            FgoLogger.debug(
                tag,
                "Completed dialogue OCR not stable yet: ${stableFor}ms scans=$completedDialogueCandidateSeenCount"
            )
        }
        return stable
    }

    private fun maybeStartEarlyTranslation(
        sceneSource: SceneSource,
        processingVersion: Long,
        requireStableTypingCandidate: Boolean = true
    ) {
        if (!TranslationTrigger.isAutoTranslateEnabled()) return
        if (!sceneSource.hasDialogue) return
        if (sceneSource.dialogueCharCount < EARLY_TRANSLATION_MIN_DIALOGUE_CHARS) return
        if (sceneSource.fingerprint == lastAutoRenderedSourceText) return

        val now = SystemClock.elapsedRealtime()
        if (requireStableTypingCandidate && sceneSource.fingerprint != typingCandidateFingerprint) {
            typingCandidateFingerprint = sceneSource.fingerprint
            typingCandidateFirstSeenAt = now
            if (earlyTranslationFingerprint.isNotBlank() &&
                earlyTranslationFingerprint != sceneSource.fingerprint
            ) {
                FgoLogger.debug(tag, "Typing OCR changed; cancelling stale early translation")
                resetEarlyTranslation(clearTypingCandidate = false)
                typingCandidateFingerprint = sceneSource.fingerprint
                typingCandidateFirstSeenAt = now
            }
            return
        }

        if (requireStableTypingCandidate &&
            now - typingCandidateFirstSeenAt < EARLY_TRANSLATION_STABLE_DELAY
        ) {
            return
        }
        if (earlyTranslationFingerprint == sceneSource.fingerprint &&
            (earlyTranslationJob?.isActive == true || earlyTranslationResult != null)
        ) {
            return
        }

        resetEarlyTranslation(clearTypingCandidate = false)
        earlyTranslationFingerprint = sceneSource.fingerprint
        earlyTranslationResult = null
        earlyTranslationJob = serviceScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                FgoLogger.debug(tag, "Starting early translation while dialogue is typing")
                val result = translateSceneSourceDirect(sceneSource)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (processingVersion == stopVersion &&
                    earlyTranslationFingerprint == sceneSource.fingerprint
                ) {
                    earlyTranslationResult = EarlyTranslationResult(sceneSource.fingerprint, result)
                    FgoLogger.info(tag, "Early translation ready in ${elapsed}ms")
                }
            } catch (e: CancellationException) {
                FgoLogger.debug(tag, "Early translation cancelled")
                throw e
            } catch (e: Exception) {
                if (earlyTranslationFingerprint == sceneSource.fingerprint) {
                    FgoLogger.warn(tag, "Early translation failed; final pass will translate normally", e)
                }
            } finally {
                if (earlyTranslationFingerprint == sceneSource.fingerprint) {
                    earlyTranslationJob = null
                }
            }
        }
    }

    private suspend fun awaitEarlyTranslation(fingerprint: String): SceneTranslateResult? {
        if (earlyTranslationFingerprint.isNotBlank() && earlyTranslationFingerprint != fingerprint) {
            resetEarlyTranslation()
            return null
        }

        val activeJob = earlyTranslationJob
        if (activeJob?.isActive == true && earlyTranslationFingerprint == fingerprint) {
            FgoLogger.debug(tag, "Awaiting in-flight early translation for completed dialogue")
            activeJob.join()
        }

        val result = earlyTranslationResult
            ?.takeIf { it.fingerprint == fingerprint }
            ?.result

        if (result != null) {
            FgoLogger.info(tag, "Using early translation result for completed dialogue")
            resetEarlyTranslation()
            return result
        }

        if (earlyTranslationFingerprint == fingerprint) {
            resetEarlyTranslation()
        }
        return null
    }

    private fun mergeManualSceneRegions(
        choiceRegions: List<ClassifiedRegion>,
        dialogueRegions: List<ClassifiedRegion>
    ): List<ClassifiedRegion> {
        if (dialogueRegions.isEmpty()) return choiceRegions
        return buildList {
            addAll(dialogueRegions.filter { it.region == TextRegion.NAME_LABEL })
            addAll(dialogueRegions.filter { it.region == TextRegion.DIALOGUE_BOX })
            addAll(choiceRegions)
        }
    }

    private fun sceneSourceFor(regions: List<ClassifiedRegion>): SceneSource? {
        val translatableRegions = regions.mapNotNull { region ->
            val sourceText = sourceTextFor(region)
            if (sourceText.isBlank()) null else RegionSourceText(region, sourceText)
        }
        if (translatableRegions.isEmpty()) return null

        val nameRegion = translatableRegions.firstOrNull { it.region.region == TextRegion.NAME_LABEL }
        val dialogueRegion = translatableRegions.firstOrNull { it.region.region == TextRegion.DIALOGUE_BOX }
        val choiceRegions = translatableRegions.filter { it.region.region == TextRegion.CHOICE_BUTTON }
        val fingerprint = translatableRegions.joinToString("\n\n") { regionText ->
            "${regionText.region.region}:${regionText.region.boundingBox.flattenToString()}\n${regionText.text}"
        }.trim()
        val dialogueText = dialogueRegion?.text.orEmpty()
        return SceneSource(
            regions = translatableRegions,
            input = SceneTranslateInput(
                name = nameRegion?.text,
                dialogue = dialogueRegion?.text,
                choices = choiceRegions.map { it.text }
            ),
            fingerprint = fingerprint,
            hasDialogue = dialogueText.isNotBlank(),
            dialogueCharCount = dialogueText.count { !it.isWhitespace() }
        )
    }

    private suspend fun translateSceneSource(sceneSource: SceneSource): SceneTranslateResult {
        awaitEarlyTranslation(sceneSource.fingerprint)?.let { return it }
        return translateSceneSourceDirect(sceneSource)
    }

    private suspend fun translateSceneSourceDirect(sceneSource: SceneSource): SceneTranslateResult {
        return withContext(Dispatchers.IO) {
            translator.translateScene(sceneSource.input)
        }
    }

    private fun buildRenderInstructions(
        source: Bitmap,
        sceneSource: SceneSource,
        sceneTranslation: SceneTranslateResult
    ): List<RenderInstruction> {
        val choiceRegions = sceneSource.regions.filter { it.region.region == TextRegion.CHOICE_BUTTON }
        val translatedChoicesByRegion = choiceRegions
            .zip(sceneTranslation.choices)
            .associate { (regionAndText, result) -> regionAndText.region to result.translatedText }

        return sceneSource.regions.mapNotNull { regionAndText ->
            val translatedText = when (regionAndText.region.region) {
                TextRegion.NAME_LABEL -> renderableNameTranslation(
                    sourceText = regionAndText.text,
                    result = sceneTranslation.name
                )
                TextRegion.DIALOGUE_BOX -> sceneTranslation.dialogue?.translatedText
                TextRegion.CHOICE_BUTTON -> translatedChoicesByRegion[regionAndText.region]
            }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            RenderInstruction(
                region = regionAndText.region,
                translatedText = translatedText,
                textColor = renderTextColorForRegion(source, regionAndText.region),
                wideTextSpacing = shouldUseWideRenderSpacing(
                    sourceText = regionAndText.text,
                    region = regionAndText.region.region
                )
            )
        }
    }

    private fun missingRequiredRenderReason(
        sceneSource: SceneSource,
        instructions: List<RenderInstruction>
    ): String? {
        val sourceHasDialogue = sceneSource.regions.any {
            it.region.region == TextRegion.DIALOGUE_BOX && it.text.isNotBlank()
        }
        val renderedHasDialogue = instructions.any {
            it.region.region == TextRegion.DIALOGUE_BOX && it.translatedText.isNotBlank()
        }
        if (sourceHasDialogue && !renderedHasDialogue) {
            return "dialogue translation missing"
        }

        val sourceChoiceCount = sceneSource.regions.count {
            it.region.region == TextRegion.CHOICE_BUTTON && it.text.isNotBlank()
        }
        if (sourceChoiceCount == 0) return null

        val renderedChoiceCount = instructions.count {
            it.region.region == TextRegion.CHOICE_BUTTON && it.translatedText.isNotBlank()
        }
        if (renderedChoiceCount < sourceChoiceCount) {
            return "choice translation missing ($renderedChoiceCount/$sourceChoiceCount)"
        }
        return null
    }

    private fun renderableNameTranslation(sourceText: String, result: TranslateResult?): String? {
        if (isPlaceholderSpeakerName(sourceText)) {
            FgoLogger.debug(tag, "Rendering placeholder speaker name as raw text: $sourceText")
            return null
        }
        val translated = result?.translatedText?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (result.backend == "none") {
            FgoLogger.debug(tag, "Skipping name render without translation backend")
            return null
        }
        if (samePlainText(sourceText, translated) ||
            translated.any { it.isJapaneseKana() } ||
            translated.length > 32 ||
            translated.any { it in setOf('\n', '\r', '。', '！', '？', '!', '?') }
        ) {
            FgoLogger.debug(tag, "Skipping untranslated/plain name render: $sourceText -> $translated")
            return null
        }
        return translated
    }

    private fun isPlaceholderSpeakerName(sourceText: String): Boolean {
        val compact = sourceText.trim().filterNot { it.isWhitespace() }
        return compact.length >= 2 && compact.all { it == '?' || it == '\uFF1F' || it == '\uFE56' }
    }

    private fun samePlainText(left: String, right: String): Boolean {
        return TextNormalizer.normalizeForTranslation(left)
            .filterNot { it.isNameComparePunctuation() }
            .equals(
                TextNormalizer.normalizeForTranslation(right).filterNot { it.isNameComparePunctuation() },
                ignoreCase = true
            )
    }

    private fun Char.isJapaneseKana(): Boolean {
        return this in '\u3040'..'\u30ff' && this != '\u30FB'
    }

    private fun Char.isNameComparePunctuation(): Boolean {
        return isWhitespace() || this in setOf(
            '・', '･', '·', '•', '.', '．', '。', '、', ',', '，',
            '!', '！', '?', '？', ':', '：', ';', '；'
        )
    }

    private fun sourceTextFor(region: ClassifiedRegion): String {
        val rawText = when (region.region) {
            TextRegion.DIALOGUE_BOX -> formatDialogueForTranslation(region.lines, RubyDetectionMode.STRICT)
            else -> cleanRubyNoiseLines(region.lines)
                .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
                .joinToString("\n") { it.text }
                .trim()
        }
        return when (region.region) {
            TextRegion.NAME_LABEL -> rawText
            TextRegion.DIALOGUE_BOX,
            TextRegion.CHOICE_BUTTON -> correctOcrSourceText(rawText, region.region.name)
        }
    }

    private fun correctOcrSourceText(sourceText: String, label: String): String {
        val corrected = OcrTextCorrector.correct(sourceText)
        if (corrected != sourceText) {
            FgoLogger.debug(tag, "OCR correction ($label): $sourceText -> $corrected")
        }
        return corrected
    }

    private fun formatDialogueForTranslation(
        lines: List<OcrTextLine>,
        rubyDetectionMode: RubyDetectionMode
    ): String {
        val cleanedLines = cleanRubyNoiseLines(lines)
        if (cleanedLines.size < 2) {
            return cleanedLines.joinToString("\n") { it.text }.trim()
        }

        val sorted = cleanedLines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        if (sorted.size < 2) return sorted.joinToString("\n") { it.text }.trim()

        val heights = sorted.map { it.boundingBox.height().coerceAtLeast(1) }.sorted()
        val medianHeight = heights[heights.size / 2]
        val rubyLines = sorted.filter { line ->
            isLikelyRubyLine(line, medianHeight, rubyDetectionMode)
        }.toSet()
        if (rubyLines.isEmpty()) return sorted.joinToString("\n") { it.text }.trim()

        val mainLines = sorted.filterNot { it in rubyLines }.toMutableList()
        if (mainLines.isEmpty()) return sorted.joinToString("\n") { it.text }.trim()

        val rubyByMain = mutableMapOf<OcrTextLine, MutableList<OcrTextLine>>()
        for (ruby in rubyLines) {
            val main = mainLines
                .filter { it.boundingBox.top >= ruby.boundingBox.bottom - medianHeight / 3 }
                .filter { it.boundingBox.top - ruby.boundingBox.bottom <= medianHeight }
                .filter {
                    horizontalOverlap(ruby.boundingBox, it.boundingBox) >= ruby.boundingBox.width() / 4 ||
                            ruby.boundingBox.centerX() in it.boundingBox.left..it.boundingBox.right
                }
                .minWithOrNull(
                    compareByDescending<OcrTextLine> { horizontalOverlap(ruby.boundingBox, it.boundingBox) }
                        .thenBy { kotlin.math.abs(it.boundingBox.centerX() - ruby.boundingBox.centerX()) }
                        .thenBy { it.boundingBox.top - ruby.boundingBox.bottom }
                )
            if (main != null) {
                rubyByMain.getOrPut(main) { mutableListOf() }.add(ruby)
            }
        }

        val formatted = mainLines
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
            .joinToString("\n") { main ->
                val rubies = rubyByMain[main]
                    ?.sortedBy { it.boundingBox.left }
                    .orEmpty()
                if (rubies.isEmpty()) {
                    main.text
                } else {
                    insertRubyAnnotations(main.text, main.boundingBox, rubies, useJapaneseRubyMarkup = true)
                }
            }
            .trim()
        if (formatted.isNotBlank()) {
            val rawText = sorted.filterNot { it in rubyLines }
                .joinToString("\n") { it.text.trim() }
                .trim()
            if (formatted != rawText) {
                FgoLogger.debug(tag, "Ruby formatted source (${rubyDetectionMode.name.lowercase()}): $formatted")
            }
        }
        return formatted
    }

    private fun cleanRubyNoiseLines(lines: List<OcrTextLine>): List<OcrTextLine> {
        val sorted = lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        if (sorted.size < 2) return sorted.filterNot { isRubyDotNoiseLine(it) }

        val heights = sorted.map { it.boundingBox.height().coerceAtLeast(1) }.sorted()
        val medianHeight = heights[heights.size / 2]
        val meaningfulLines = sorted.filterNot { isRubyDotNoiseLine(it) }
        if (meaningfulLines.isEmpty()) return emptyList()

        val noiseLines = sorted.filter { line ->
            isRubyDotNoiseLine(line) && meaningfulLines.any { main ->
                isLikelyRubyAboveMain(line, main, medianHeight)
            }
        }.toSet()

        return sorted.filterNot { it in noiseLines }
    }

    private fun isRubyDotNoiseLine(line: OcrTextLine): Boolean {
        val text = line.text.trim()
        if (text.isBlank()) return false
        if (text.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' || it.isLetterOrDigit() }) {
            return false
        }
        val dotLikeCount = text.count { it.isRubyDotNoiseChar() }
        return dotLikeCount > 0 && text.all { it.isRubyDotNoiseChar() || it.isWhitespace() }
    }

    private fun isLikelyRubyAboveMain(
        ruby: OcrTextLine,
        main: OcrTextLine,
        medianHeight: Int
    ): Boolean {
        if (main.boundingBox.top < ruby.boundingBox.bottom - medianHeight / 3) return false
        if (main.boundingBox.top - ruby.boundingBox.bottom > medianHeight * 2) return false
        return horizontalOverlap(ruby.boundingBox, main.boundingBox) >= ruby.boundingBox.width() / 4 ||
                ruby.boundingBox.centerX() in main.boundingBox.left..main.boundingBox.right
    }

    private fun Char.isRubyDotNoiseChar(): Boolean {
        return this in setOf(
            '.', ',', ':', ';', '-', '_', '~',
            '・', '･', '…', '‥', '·', '•', '。', '、',
            '︙', '⋯', '—', '–', '─', '━'
        )
    }

    private fun isLikelyRubyLine(
        line: OcrTextLine,
        medianHeight: Int,
        rubyDetectionMode: RubyDetectionMode
    ): Boolean {
        val text = line.text.trim()
        if (text.length !in 1..RUBY_MAX_CHARS) return false
        val height = line.boundingBox.height().coerceAtLeast(1)
        if (height > medianHeight * RUBY_HEIGHT_RATIO) return false
        val rubyChars = text.count {
            it in '\u3040'..'\u30ff' ||
                    it in '\u4e00'..'\u9fff' ||
                    it.isLetterOrDigit() ||
                    it in setOf('ー', '・', '･', '＝', '=', '-', '－')
        }
        val hasJapanese = text.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
        val hasReadable = when (rubyDetectionMode) {
            RubyDetectionMode.STRICT -> hasJapanese
            RubyDetectionMode.PERMISSIVE -> text.any {
                it.isLetterOrDigit() || it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff'
            }
        }
        return rubyChars >= (text.length * 0.7f).toInt().coerceAtLeast(1) && hasReadable
    }

    private fun horizontalOverlap(a: Rect, b: Rect): Int {
        return (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
    }

    private data class RubyInsertion(
        val index: Int,
        val annotation: String,
        val rubyText: String
    )

    private fun insertRubyAnnotations(
        mainText: String,
        mainBounds: Rect,
        rubies: List<OcrTextLine>,
        useJapaneseRubyMarkup: Boolean
    ): String {
        val insertions = rubies
            .mapNotNull { ruby -> rubyInsertion(mainText, mainBounds, ruby, useJapaneseRubyMarkup) }
            .distinctBy { it.index to it.rubyText }
            .sortedByDescending { it.index }
        if (insertions.isEmpty()) return mainText

        var result = mainText
        for (insertion in insertions) {
            val index = insertion.index.coerceIn(0, result.length)
            result = result.substring(0, index) + insertion.annotation + result.substring(index)
        }
        return result
    }

    private fun insertRubyAnnotation(
        mainText: String,
        mainBounds: Rect,
        ruby: OcrTextLine,
        useJapaneseRubyMarkup: Boolean
    ): String {
        val insertion = rubyInsertion(mainText, mainBounds, ruby, useJapaneseRubyMarkup)
            ?: return mainText
        return mainText.substring(0, insertion.index) +
                insertion.annotation +
                mainText.substring(insertion.index)
    }

    private fun rubyInsertion(
        mainText: String,
        mainBounds: Rect,
        ruby: OcrTextLine,
        useJapaneseRubyMarkup: Boolean
    ): RubyInsertion? {
        if (mainText.isBlank()) return null
        val rubyText = ruby.text.trim()
        if (rubyText.isBlank() ||
            mainText.contains("《$rubyText》") ||
            mainText.contains("($rubyText)") ||
            mainText.contains(rubyText)
        ) {
            return null
        }

        val approximateCharWidth = mainBounds.width().toFloat() / mainText.length.coerceAtLeast(1)
        val rawStartIndex = kotlin.math.floor(
            (ruby.boundingBox.left - mainBounds.left) / approximateCharWidth
        )
            .toInt()
            .coerceIn(0, mainText.length - 1)
        val rawEndIndex = kotlin.math.ceil(
            (ruby.boundingBox.right - mainBounds.left) / approximateCharWidth
        )
            .toInt()
            .coerceIn(1, mainText.length)
        val insertIndex = refineRubyInsertIndex(mainText, rawStartIndex, rawEndIndex)
        val annotation = if (useJapaneseRubyMarkup) {
            "《$rubyText》"
        } else {
            "($rubyText)"
        }
        return RubyInsertion(insertIndex, annotation, rubyText)
    }

    private fun refineRubyInsertIndex(
        text: String,
        rawStartIndex: Int,
        rawEndIndex: Int
    ): Int {
        val punctuation = setOf('、', '。', '，', ',', '！', '!', '？', '?', '…', '」', '』', ')', '）')
        val closingMarks = setOf('」', '』', ')', '）', ']', '】', '》')
        val startIndex = rawStartIndex.coerceIn(0, text.lastIndex)
        var index = rawEndIndex.coerceIn(1, text.length)
        while (index < text.length &&
            index > 0 &&
            text[index - 1].isAsciiWordChar() &&
            text[index].isAsciiWordChar()
        ) {
            index++
        }
        while (index < text.length &&
            index - startIndex < RUBY_MAX_BASE_CHARS &&
            shouldExtendJapaneseRubyBase(text[index - 1], text[index])
        ) {
            index++
        }
        while (index < text.length && text[index] in closingMarks) {
            index++
        }
        while (index > 1 && text[index - 1] in punctuation) {
            index--
        }
        return index.coerceIn(1, text.length)
    }

    private fun Char.isAsciiWordChar(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_'
    }

    private fun shouldExtendJapaneseRubyBase(previous: Char, next: Char): Boolean {
        return (previous.isCjkIdeograph() && next.isCjkIdeograph()) ||
                (previous.isKatakanaLike() && next.isKatakanaLike())
    }

    private fun Char.isCjkIdeograph(): Boolean {
        return this in '\u3400'..'\u9FFF'
    }

    private fun Char.isKatakanaLike(): Boolean {
        return this in '\u30A0'..'\u30FF' || this in '\uFF66'..'\uFF9D' ||
                this in setOf('ー', '・', '･')
    }

    private fun shouldUseWideRenderSpacing(sourceText: String, region: TextRegion): Boolean {
        return when (region) {
            TextRegion.DIALOGUE_BOX,
            TextRegion.CHOICE_BUTTON -> hasFgoWideSourceSpacing(sourceText)
            TextRegion.NAME_LABEL -> false
        }
    }

    private fun renderTextColorForRegion(source: Bitmap, region: ClassifiedRegion): Int? {
        return when (region.region) {
            TextRegion.DIALOGUE_BOX,
            TextRegion.NAME_LABEL,
            TextRegion.CHOICE_BUTTON -> sampleOriginalTextColor(source, region)
        }
    }

    private fun hasFgoWideSourceSpacing(sourceText: String): Boolean {
        var spacedGaps = 0
        var adjacentPairs = 0

        sourceText.lines().forEach { line ->
            var previousTextChar: Char? = null
            var sawWhitespace = false

            for (char in line) {
                if (char.isWhitespace() || char == '\u3000') {
                    if (previousTextChar != null) {
                        sawWhitespace = true
                    }
                    continue
                }

                if (!char.isJapaneseOrCjkForSpacing()) {
                    previousTextChar = null
                    sawWhitespace = false
                    continue
                }

                if (previousTextChar != null) {
                    if (sawWhitespace) {
                        spacedGaps++
                    } else {
                        adjacentPairs++
                    }
                }
                previousTextChar = char
                sawWhitespace = false
            }
        }

        return spacedGaps >= 2 && spacedGaps * 2 >= adjacentPairs
    }

    private fun Char.isJapaneseOrCjkForSpacing(): Boolean {
        return isCjkIdeograph() ||
                this in '\u3040'..'\u309F' ||
                this in '\u30A0'..'\u30FF' ||
                this in '\uFF66'..'\uFF9D'
    }

    private fun sampleOriginalTextColor(source: Bitmap, region: ClassifiedRegion): Int? {
        val matchCounts = IntArray(FGO_TEXT_COLOR_SAMPLES.size)

        for (line in region.lines) {
            val bounds = Rect(line.boundingBox)
            if (!bounds.intersect(0, 0, source.width, source.height)) continue
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            for (y in bounds.top until bounds.bottom step 2) {
                for (x in bounds.left until bounds.right step 2) {
                    val pixel = source.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val sampleIndex = nearestTextColorSampleIndex(r, g, b)
                    if (sampleIndex >= 0) {
                        matchCounts[sampleIndex]++
                    }
                }
            }
        }

        val bestIndex = matchCounts.indices.maxByOrNull { matchCounts[it] } ?: return null
        return if (matchCounts[bestIndex] >= MIN_PALETTE_TEXT_PIXELS) {
            FGO_TEXT_COLOR_SAMPLES[bestIndex].renderColor
        } else {
            null
        }
    }

    private fun sampleCropOriginalTextColor(
        crop: Bitmap,
        lines: List<OcrTextLine>,
        coordinateScale: Int
    ): Int? {
        val scale = coordinateScale.coerceAtLeast(1)
        val cropLines = lines.mapNotNull { line ->
            val sourceBounds = line.boundingBox
            val bounds = if (scale == 1) {
                Rect(sourceBounds)
            } else {
                Rect(
                    sourceBounds.left / scale,
                    sourceBounds.top / scale,
                    (sourceBounds.right + scale - 1) / scale,
                    (sourceBounds.bottom + scale - 1) / scale
                )
            }
            if (bounds.width() <= 0 || bounds.height() <= 0) return@mapNotNull null
            OcrTextLine(
                text = line.text,
                boundingBox = bounds,
                confidence = line.confidence
            )
        }
        if (cropLines.isEmpty()) return null
        return sampleOriginalTextColor(
            source = crop,
            region = ClassifiedRegion(
                region = TextRegion.DIALOGUE_BOX,
                lines = cropLines,
                boundingBox = Rect(0, 0, crop.width, crop.height)
            )
        )
    }

    private fun nearestTextColorSampleIndex(red: Int, green: Int, blue: Int): Int {
        var bestIndex = -1
        var bestDistance = Int.MAX_VALUE
        FGO_TEXT_COLOR_SAMPLES.forEachIndexed { index, sample ->
            val distance = sample.distanceSquared(red, green, blue)
            if (distance <= sample.maxDistanceSquared && distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private data class TextColorSample(
        val sampleColor: Int,
        val renderColor: Int,
        val maxDistanceSquared: Int
    ) {
        fun distanceSquared(red: Int, green: Int, blue: Int): Int {
            val dr = red - Color.red(sampleColor)
            val dg = green - Color.green(sampleColor)
            val db = blue - Color.blue(sampleColor)
            return dr * dr + dg * dg + db * db
        }
    }

    private fun addHistoryEntry(
        source: Bitmap,
        sceneSource: SceneSource,
        instructions: List<RenderInstruction>
    ) {
        val rawNameRegion = sceneSource.regions.firstOrNull { it.region.region == TextRegion.NAME_LABEL }
        val nameInstruction = instructions.firstOrNull { it.region.region == TextRegion.NAME_LABEL }
        val dialogueInstruction = instructions.firstOrNull { it.region.region == TextRegion.DIALOGUE_BOX }
        val choiceInstructions = instructions.filter { it.region.region == TextRegion.CHOICE_BUTTON }

        val renderedName = nameInstruction
            ?.translatedText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val rawName = rawNameRegion
            ?.text
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val name = renderedName ?: rawName
        val dialogue = dialogueInstruction
            ?.let { overlayRenderer.renderedDialogueText(it, source.height) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val choicePairs = choiceInstructions
            .map { it.translatedText.trim() }
            .zip(choiceInstructions.map { it.textColor })
            .filter { it.first.isNotBlank() }
        val dialogueSourceKey = sceneSource.historyDialogueSourceKey()
        val entrySourceKey = sceneSource.historySourceKey(hasChoices = choicePairs.isNotEmpty())

        if (name != null || dialogue != null || choicePairs.isNotEmpty()) {
            SessionTranslationHistory.add(
                SessionTranslationEntry(
                    speakerName = name,
                    dialogueText = dialogue,
                    choices = choicePairs.map { it.first },
                    speakerNameColor = nameInstruction?.textColor
                        ?: rawNameRegion?.let { sampleOriginalTextColor(source, it.region) },
                    dialogueTextColor = dialogueInstruction?.textColor,
                    choiceColors = choicePairs.map { it.second },
                    sourceKey = entrySourceKey,
                    dialogueSourceKey = dialogueSourceKey
                )
            )
        }
    }

    private fun SceneSource.historyDialogueSourceKey(): String {
        val dialogue = input.dialogue?.trim().orEmpty()
        if (dialogue.isBlank()) return ""
        return listOf(
            input.name.orEmpty(),
            dialogue
        )
            .joinToString("\n")
            .trim()
    }

    private fun SceneSource.historySourceKey(hasChoices: Boolean): String {
        val dialogueSourceKey = historyDialogueSourceKey()
        if (!hasChoices) return dialogueSourceKey

        val choicesSourceKey = input.choices
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (choicesSourceKey.isBlank()) return dialogueSourceKey

        return listOf(
            "CHOICES",
            dialogueSourceKey,
            choicesSourceKey
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
    }

    private suspend fun recognizeChoiceRegions(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        mode: ProcessingMode
    ): ChoiceRecognitionResult {
        return recognizeChoiceRegions(
            source = source,
            choiceBounds = detectChoiceBounds(source, screenRegions),
            mode = mode
        )
    }

    private suspend fun detectChoiceBounds(
        source: Bitmap,
        screenRegions: FgoScreenRegions
    ): List<Rect> {
        val primaryChoiceBounds = withContext(Dispatchers.Default) {
            backgroundDetector.detectChoiceButtons(source, screenRegions.choiceSearch)
        }
        val rawChoiceBounds = if (shouldExpandChoiceSearch(primaryChoiceBounds, screenRegions.choiceSearch)) {
            val expandedSearch = Rect(
                screenRegions.choiceSearch.left,
                screenRegions.viewport.top,
                screenRegions.choiceSearch.right,
                screenRegions.choiceSearch.bottom
            )
            FgoLogger.debug(
                tag,
                "Expanding choice search upward for tall list candidate: ${primaryChoiceBounds.map { it.flattenToString() }}"
            )
            withContext(Dispatchers.Default) {
                backgroundDetector.detectChoiceButtons(source, expandedSearch)
            }
        } else {
            primaryChoiceBounds
        }
        return filterChoiceBounds(rawChoiceBounds, screenRegions.choiceSearch)
    }

    private suspend fun recognizeChoiceRegions(
        source: Bitmap,
        choiceBounds: List<Rect>,
        mode: ProcessingMode,
        retryEmptyTargetsIndividually: Boolean = mode == ProcessingMode.AUTO || choiceBounds.size >= 2,
        allowAutoSingleChoiceFallback: Boolean = true
    ): ChoiceRecognitionResult {
        val now = SystemClock.elapsedRealtime()
        val useEmptyChoiceCooldown = mode == ProcessingMode.AUTO
        if (choiceBounds.isEmpty()) return ChoiceRecognitionResult(emptyList(), emptyList())

        val choiceBoundsKey = choiceBounds.joinToString("|") { it.flattenToString() }
        if (useEmptyChoiceCooldown &&
            now < choiceOcrSuppressedUntil &&
            choiceBoundsKey == suppressedChoiceBoundsKey
        ) {
            FgoLogger.debug(tag, "Skipping same empty choice panel during cooldown")
            return ChoiceRecognitionResult(choiceBounds, emptyList())
        }

        val choiceRegions = recognizeScreenRegions(
            source = source,
            targets = choiceBounds.map { OcrRegionTarget(it, TextRegion.CHOICE_BUTTON) },
            retryEmptyTargetsIndividually = retryEmptyTargetsIndividually
        ).ifEmpty {
            if (allowAutoSingleChoiceFallback && mode == ProcessingMode.AUTO && choiceBounds.size == 1) {
                recognizeAutoSingleChoiceRegion(source, choiceBounds.single())?.let { listOf(it) }
                    ?: emptyList()
            } else {
                emptyList()
            }
        }
        if (choiceRegions.size != choiceBounds.size) {
            FgoLogger.debug(
                tag,
                "Choice OCR mapped ${choiceRegions.size}/${choiceBounds.size} panel(s)"
            )
        }
        if (choiceRegions.isEmpty()) {
            if (useEmptyChoiceCooldown) {
                emptyChoiceOcrStreak = if (choiceBoundsKey == suppressedChoiceBoundsKey) {
                    emptyChoiceOcrStreak + 1
                } else {
                    1
                }
                val cooldown = (EMPTY_CHOICE_OCR_BASE_COOLDOWN * emptyChoiceOcrStreak)
                    .coerceAtMost(EMPTY_CHOICE_OCR_MAX_COOLDOWN)
                choiceOcrSuppressedUntil = now + cooldown
                suppressedChoiceBoundsKey = choiceBoundsKey
                FgoLogger.debug(
                    tag,
                    "Detected ${choiceBounds.size} choice panel(s) with no OCR text; suppressing same panels for ${cooldown}ms"
                )
            } else {
                FgoLogger.debug(tag, "Manual choice OCR returned no text; not applying auto cooldown")
            }
        } else {
            choiceOcrSuppressedUntil = 0L
            suppressedChoiceBoundsKey = ""
            emptyChoiceOcrStreak = 0
        }
        return ChoiceRecognitionResult(choiceBounds, choiceRegions)
    }

    private suspend fun recognizeAutoSingleChoiceRegion(
        source: Bitmap,
        choiceBounds: Rect
    ): ClassifiedRegion? {
        val textBounds = Rect(choiceBounds).apply {
            left += (width() * 0.14f).toInt()
            right -= (width() * 0.03f).toInt()
            top -= (height() * 0.14f).toInt()
            bottom += (height() * 0.14f).toInt()
            if (!intersect(0, 0, source.width, source.height) ||
                width() <= 0 ||
                height() <= 0
            ) {
                return null
            }
        }

        val cropped = Bitmap.createBitmap(
            source,
            textBounds.left,
            textBounds.top,
            textBounds.width(),
            textBounds.height()
        )
        val normalized = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        var scaled: Bitmap? = null
        return try {
            for (y in 0 until cropped.height) {
                for (x in 0 until cropped.width) {
                    normalized.setPixel(
                        x,
                        y,
                        if (isLikelyChoiceTextPixel(cropped.getPixel(x, y))) {
                            android.graphics.Color.BLACK
                        } else {
                            android.graphics.Color.WHITE
                        }
                    )
                }
            }

            val scale = 2
            scaled = Bitmap.createScaledBitmap(
                normalized,
                normalized.width * scale,
                normalized.height * scale,
                false
            )
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(scaled!!)
            }
            val lines = ocrResult.lines
                .map { line ->
                    OcrTextLine(
                        text = line.text,
                        boundingBox = Rect(
                            textBounds.left + line.boundingBox.left / scale,
                            textBounds.top + line.boundingBox.top / scale,
                            textBounds.left + line.boundingBox.right / scale,
                            textBounds.top + line.boundingBox.bottom / scale
                        ),
                        confidence = line.confidence
                    )
                }
                .filter { it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }

            if (lines.isEmpty()) {
                null
            } else {
                FgoLogger.debug(tag, "Auto single-choice binary OCR recovered ${lines.size} line(s)")
                ClassifiedRegion(
                    region = TextRegion.CHOICE_BUTTON,
                    lines = lines,
                    boundingBox = choiceBounds
                )
            }
        } finally {
            scaled?.recycle()
            normalized.recycle()
            cropped.recycle()
        }
    }

    private fun isLikelyChoiceTextPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return r >= 145 && g >= 145 && b >= 145 && max - min <= 95
    }

    private fun shouldExpandChoiceSearch(
        bounds: List<Rect>,
        searchBounds: Rect
    ): Boolean {
        if (bounds.isEmpty()) return false
        val topTolerance = (searchBounds.height() * 0.02f).toInt().coerceAtLeast(8)
        val clippedAtTop = bounds.first().top <= searchBounds.top + topTolerance
        return clippedAtTop || bounds.size >= 4
    }

    private fun filterChoiceBounds(
        bounds: List<Rect>,
        searchBounds: Rect
    ): List<Rect> {
        if (bounds.size != 1) return bounds

        val only = bounds.single()
        val bottomTolerance = (searchBounds.height() * 0.01f).toInt().coerceAtLeast(4)
        val lowStartY = searchBounds.top + (searchBounds.height() * 0.70f).toInt()
        val tallEnough = only.height() >= (searchBounds.height() * 0.14f).toInt()
        val touchesSearchBottom = only.bottom >= searchBounds.bottom - bottomTolerance
        if (only.top >= lowStartY && tallEnough && touchesSearchBottom) {
            FgoLogger.debug(
                tag,
                "Ignoring lone bottom-edge choice-like panel ${only.flattenToString()}"
            )
            return emptyList()
        }

        return bounds
    }

    private suspend fun recognizeDialogueRegions(
        source: Bitmap,
        screenRegions: FgoScreenRegions
    ): List<ClassifiedRegion> {
        return recognizeScreenRegions(
            source = source,
            targets = listOf(
                OcrRegionTarget(screenRegions.dialogue, TextRegion.DIALOGUE_BOX),
                OcrRegionTarget(screenRegions.name, TextRegion.NAME_LABEL)
            )
        ).map { region ->
            if (region.region == TextRegion.NAME_LABEL) {
                region.copy(boundingBox = screenRegions.nameRender)
            } else {
                region
            }
        }
    }

    private suspend fun recognizeScreenRegions(
        source: Bitmap,
        targets: List<OcrRegionTarget>,
        retryEmptyTargetsIndividually: Boolean = false
    ): List<ClassifiedRegion> {
        val clippedTargets = targets.mapNotNull { target ->
            val clipped = Rect(target.bounds)
            if (!clipped.intersect(0, 0, source.width, source.height) ||
                clipped.width() <= 0 ||
                clipped.height() <= 0
            ) {
                null
            } else {
                target.copy(bounds = clipped)
            }
        }
        if (clippedTargets.isEmpty()) return emptyList()

        val cropBounds = Rect(clippedTargets.first().bounds)
        clippedTargets.drop(1).forEach { cropBounds.union(it.bounds) }
        if (!cropBounds.intersect(0, 0, source.width, source.height) ||
            cropBounds.width() <= 0 ||
            cropBounds.height() <= 0
        ) {
            return emptyList()
        }

        val cropped = Bitmap.createBitmap(
            source,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width(),
            cropBounds.height()
        )
        return try {
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(cropped)
            }
            val lines = ocrResult.lines
                .toScreenCoordinates(cropBounds)
                .filter { it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }

            val regionsByTarget = clippedTargets.mapNotNull { target ->
                val regionLines = lines.filter { lineBelongsToRegion(it.boundingBox, target.bounds) }
                if (regionLines.isEmpty()) {
                    null
                } else {
                    targetKey(target) to ClassifiedRegion(
                        region = target.region,
                        lines = regionLines,
                        boundingBox = target.bounds
                    )
                }
            }.toMap().toMutableMap()

            if (retryEmptyTargetsIndividually) {
                val missingTargets = clippedTargets.filter { targetKey(it) !in regionsByTarget }
                missingTargets.forEach { target ->
                    recognizeSingleScreenRegion(source, target)?.let { recoveredRegion ->
                        regionsByTarget[targetKey(target)] = recoveredRegion
                    }
                }
                if (missingTargets.isNotEmpty()) {
                    FgoLogger.debug(
                        tag,
                        "Choice OCR individual retry recovered ${regionsByTarget.size}/${clippedTargets.size} panel(s)"
                    )
                }
            }

            clippedTargets.mapNotNull { regionsByTarget[targetKey(it)] }
        } finally {
            cropped.recycle()
        }
    }

    private suspend fun recognizeSingleScreenRegion(
        source: Bitmap,
        target: OcrRegionTarget
    ): ClassifiedRegion? {
        val cropBounds = expandedOcrBounds(target.bounds, source.width, source.height)
        if (cropBounds.width() <= 0 || cropBounds.height() <= 0) return null

        val cropped = Bitmap.createBitmap(
            source,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width(),
            cropBounds.height()
        )
        return try {
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(cropped)
            }
            val regionLines = ocrResult.lines
                .toScreenCoordinates(cropBounds)
                .filter { it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }
                .filter { lineBelongsToRegion(it.boundingBox, target.bounds) }
            if (regionLines.isEmpty()) {
                null
            } else {
                ClassifiedRegion(
                    region = target.region,
                    lines = regionLines,
                    boundingBox = target.bounds
                )
            }
        } finally {
            cropped.recycle()
        }
    }

    private fun expandedOcrBounds(bounds: Rect, screenWidth: Int, screenHeight: Int): Rect {
        val paddingX = (bounds.width() * 0.02f).toInt().coerceAtLeast(12)
        val paddingY = (bounds.height() * 0.12f).toInt().coerceAtLeast(10)
        return Rect(
            (bounds.left - paddingX).coerceAtLeast(0),
            (bounds.top - paddingY).coerceAtLeast(0),
            (bounds.right + paddingX).coerceAtMost(screenWidth),
            (bounds.bottom + paddingY).coerceAtMost(screenHeight)
        )
    }

    private fun targetKey(target: OcrRegionTarget): String {
        return "${target.region}:${target.bounds.flattenToString()}"
    }

    private fun lineBelongsToRegion(lineBounds: Rect, regionBounds: Rect): Boolean {
        if (regionBounds.contains(lineBounds.centerX(), lineBounds.centerY())) return true

        val overlapWidth = (minOf(lineBounds.right, regionBounds.right) -
                maxOf(lineBounds.left, regionBounds.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(lineBounds.bottom, regionBounds.bottom) -
                maxOf(lineBounds.top, regionBounds.top)).coerceAtLeast(0)
        val overlapArea = overlapWidth * overlapHeight
        val lineArea = lineBounds.width().coerceAtLeast(1) * lineBounds.height().coerceAtLeast(1)
        return overlapArea >= lineArea * 0.45f
    }

    private suspend fun isSourceVisuallyCurrent(expected: VisualSourceFingerprint): Boolean {
        val currentScreenshot = takeScreenshotCompat() ?: return false
        return try {
            val screenRegions = FgoViewportLayout.regionsForScreen(
                currentScreenshot.width,
                currentScreenshot.height
            )
            if (expected.hasChoices) {
                val currentChoices = detectChoiceBounds(currentScreenshot, screenRegions)
                if (currentChoices.size < expected.choiceRegionCount) return false
            }
            if (expected.hasDialogue) {
                val markerVisible = backgroundDetector.isDialogueCompleteMarkerVisible(
                    currentScreenshot,
                    screenRegions.dialogueComplete
                )
                if (!markerVisible) {
                    FgoLogger.debug(tag, "Dialogue marker not visible during visual freshness check; ignoring animated marker")
                }
            }
            if (expected.samples.isEmpty()) return false
            expected.samples.all { sample ->
                val currentMask = textMaskFor(currentScreenshot, sample.bounds)
                val matches = currentMask != null && masksAreSimilar(sample.mask, currentMask)
                if (!matches) {
                    FgoLogger.debug(tag, "Visual freshness mismatch in ${sample.region}")
                }
                matches
            }
        } finally {
            currentScreenshot.recycle()
        }
    }

    private fun visualFingerprintFor(
        source: Bitmap,
        regions: List<ClassifiedRegion>
    ): VisualSourceFingerprint {
        val samples = regions.flatMap { region ->
            region.lines.mapNotNull { line ->
                textMaskFor(source, line.boundingBox)?.let { mask ->
                    VisualTextSample(region.region, Rect(line.boundingBox), mask)
                }
            }
        }
        return VisualSourceFingerprint(
            hasDialogue = regions.any {
                it.region == TextRegion.DIALOGUE_BOX || it.region == TextRegion.NAME_LABEL
            },
            hasChoices = regions.any { it.region == TextRegion.CHOICE_BUTTON },
            choiceRegionCount = regions.count { it.region == TextRegion.CHOICE_BUTTON },
            samples = samples
        )
    }

    private fun textMaskFor(bitmap: Bitmap, sourceBounds: Rect): VisualTextMask? {
        val bounds = Rect(sourceBounds)
        bounds.inset(-2, -2)
        if (!bounds.intersect(0, 0, bitmap.width, bitmap.height)) return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        val columns = ((bounds.width() + VISUAL_FINGERPRINT_STEP - 1) / VISUAL_FINGERPRINT_STEP).coerceAtLeast(1)
        val rows = ((bounds.height() + VISUAL_FINGERPRINT_STEP - 1) / VISUAL_FINGERPRINT_STEP).coerceAtLeast(1)
        val sampleCount = columns * rows
        val words = LongArray((sampleCount + 63) / 64)
        var index = 0
        var textPixels = 0

        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                if (isLikelyTextPixel(bitmap.getPixel(x, y))) {
                    words[index / 64] = words[index / 64] or (1L shl (index and 63))
                    textPixels++
                }
                index++
                x += VISUAL_FINGERPRINT_STEP
            }
            y += VISUAL_FINGERPRINT_STEP
        }

        return VisualTextMask(sampleCount = index, textPixels = textPixels, words = words)
    }

    private fun isLikelyTextPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val spread = max - min
        val whiteText = r >= 170 && g >= 170 && b >= 170 && spread <= 95
        val redText = r >= 165 && r - maxOf(g, b) >= 40
        val cyanText = g >= 140 && b >= 140 && minOf(g, b) - r >= 35
        return whiteText || redText || cyanText
    }

    private fun masksAreSimilar(expected: VisualTextMask, current: VisualTextMask): Boolean {
        if (expected.sampleCount != current.sampleCount) return false
        val textPixelDiff = kotlin.math.abs(expected.textPixels - current.textPixels)
        val textPixelTolerance = maxOf(8, (expected.sampleCount * VISUAL_FINGERPRINT_MAX_DIFF_RATIO).toInt())
        if (textPixelDiff > textPixelTolerance) return false

        var bitDiff = 0
        for (index in expected.words.indices) {
            bitDiff += java.lang.Long.bitCount(expected.words[index] xor current.words[index])
            if (bitDiff > textPixelTolerance) return false
        }
        return true
    }

    private data class VisualSourceFingerprint(
        val hasDialogue: Boolean,
        val hasChoices: Boolean,
        val choiceRegionCount: Int,
        val samples: List<VisualTextSample>
    )

    private data class VisualTextSample(
        val region: TextRegion,
        val bounds: Rect,
        val mask: VisualTextMask
    )

    private data class VisualTextMask(
        val sampleCount: Int,
        val textPixels: Int,
        val words: LongArray
    )

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

    private fun handleCropResultTap(x: Float, y: Float) {
        if (isForwardingOverlayTap) {
            FgoLogger.debug(tag, "Ignoring crop result tap while replay is active")
            return
        }

        serviceScope.launch {
            isForwardingOverlayTap = true
            cropResultOverlay.hide()
            try {
                if (!canPerformGestures()) {
                    FgoLogger.warn(tag, "Gesture injection is not granted; crop tap will only hide overlay")
                    return@launch
                }
                delay(TAP_PASSTHROUGH_SETTLE_DELAY)
                FgoLogger.debug(tag, "Crop result tapped; forwarding to FGO at $x,$y")
                withTimeoutOrNull(TAP_REPLAY_TIMEOUT) {
                    dispatchTapToFgo(x, y)
                } ?: FgoLogger.warn(tag, "Crop tap replay timed out")
            } catch (e: Exception) {
                FgoLogger.error(tag, "Crop tap replay failed", e)
            } finally {
                isForwardingOverlayTap = false
            }
        }
    }

    private fun handleTranslatedOverlayTap(x: Float, y: Float) {
        if (runnerOverlay.handleInterceptedButtonTap(x, y)) {
            return
        }
        if (isForwardingOverlayTap) {
            FgoLogger.debug(tag, "Ignoring duplicate translated overlay tap while replay is active")
            return
        }
        if (isProcessing) {
            FgoLogger.debug(tag, "Ignoring translated overlay tap while processing")
            return
        }

        val currentRenderedChoiceBounds = renderedChoiceBounds
        val hasRenderedChoices = currentRenderedChoiceBounds.isNotEmpty()
        val tappedChoice = currentRenderedChoiceBounds.any { it.contains(x.toInt(), y.toInt()) }
        val autoChoiceHandoff = (tappedChoice || hasRenderedChoices) && TranslationTrigger.isAutoTranslateEnabled()
        val previousAutoFingerprint = lastAutoRenderedSourceText
        if (autoChoiceHandoff) {
            waitingForChoiceSelectionExit = true
            renderedChoiceBounds = emptyList()
            FgoLogger.debug(
                tag,
                if (tappedChoice) {
                    "Translated choice tapped; suppressing choice OCR until selection closes"
                } else {
                    "Auto choice scene tapped; suppressing choice OCR until selection closes"
                }
            )
        }

        serviceScope.launch {
            isForwardingOverlayTap = true
            if (!canPerformGestures()) {
                FgoLogger.warn(tag, "Gesture injection is not granted; disable and re-enable accessibility service")
                isForwardingOverlayTap = false
                return@launch
            }
            try {
                FgoLogger.debug(tag, "Translated overlay tapped; forwarding to FGO at $x,$y")
                translationOverlay.setTranslatedOverlayTouchable(false)
                delay(TAP_PASSTHROUGH_SETTLE_DELAY)
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
                    if (TranslationTrigger.isAutoTranslateEnabled()) {
                        FgoLogger.debug(
                            tag,
                            if (autoChoiceHandoff) {
                                "Overlay choice tap replay completed; polling next dialogue immediately"
                            } else {
                                "Overlay tap replay completed; holding translation before polling next dialogue"
                            }
                        )
                        pollNextCompletedDialogueAfterTap(
                            skipReadHold = autoChoiceHandoff,
                            previousFingerprint = previousAutoFingerprint
                        )
                    } else {
                        FgoLogger.debug(tag, "Overlay tap replay completed; holding translation before capture hide")
                        delay(TAP_TRANSLATION_READ_HOLD_DELAY)
                        translationOverlay.hideForCapture()
                    }
                } else {
                    if (autoChoiceHandoff) {
                        waitingForChoiceSelectionExit = false
                        renderedChoiceBounds = currentRenderedChoiceBounds
                    }
                    FgoLogger.warn(tag, "Overlay tap replay failed; restoring current translation")
                    translationOverlay.setTranslatedOverlayTouchable(true)
                }
            } finally {
                isForwardingOverlayTap = false
            }
        }
    }

    private suspend fun pollNextCompletedDialogueAfterTap(
        skipReadHold: Boolean = false,
        previousFingerprint: String = ""
    ) {
        tapAdvancePolling = true
        autoTapHandoffPreviousFingerprint = previousFingerprint
        try {
            if (!skipReadHold) {
                delay(TAP_TRANSLATION_READ_HOLD_DELAY)
            }
            translationOverlay.hideForCapture()

            val deadline = SystemClock.elapsedRealtime() + NEXT_DIALOGUE_POLL_TIMEOUT
            while (SystemClock.elapsedRealtime() < deadline &&
                TranslationTrigger.isAutoTranslateEnabled()
            ) {
                processScreen(ProcessingMode.AUTO)
                val renderedFingerprint = lastAutoRenderedSourceText
                if (translationOverlay.isShowing() &&
                    renderedFingerprint.isNotBlank() &&
                    renderedFingerprint != previousFingerprint
                ) {
                    FgoLogger.debug(tag, "Next dialogue translated during tap handoff")
                    return
                }
                if (translationOverlay.isShowing()) {
                    FgoLogger.debug(tag, "Tap handoff rendered without a new source fingerprint; hiding for next capture")
                    translationOverlay.hideForCapture()
                }
                delay(NEXT_DIALOGUE_POLL_INTERVAL)
            }
            FgoLogger.debug(tag, "Next dialogue handoff polling ended; normal scan will continue")
        } finally {
            autoTapHandoffPreviousFingerprint = ""
            tapAdvancePolling = false
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

    private fun String.isTransientSystemUiPackage(): Boolean {
        return this in TRANSIENT_SYSTEM_UI_PACKAGES
    }

    private fun AccessibilityEvent.isDialogueAdvanceEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

}
