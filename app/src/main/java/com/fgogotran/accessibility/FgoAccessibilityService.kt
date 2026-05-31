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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false
    private var translationJob: Job? = null
    private var stopVersion = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var isFgoForeground = false
    private var lastRenderedSourceText = ""
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
    private var typingCandidateFingerprint = ""
    private var typingCandidateFirstSeenAt = 0L
    private var completedDialogueCandidateFingerprint = ""
    private var completedDialogueCandidateFirstSeenAt = 0L
    private var completedDialogueCandidateSeenCount = 0

    companion object {
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"
        private const val APP_PACKAGE = "com.fgogotran"
        private const val DETECTION_INTERVAL = 120L
        private const val CAPTURE_SETTLE_DELAY = 16L
        private const val MANUAL_MENU_DISMISS_SETTLE_DELAY = 300L
        private const val TAP_TRANSLATION_READ_HOLD_DELAY = 120L
        private const val NEXT_DIALOGUE_POLL_INTERVAL = 120L
        private const val NEXT_DIALOGUE_POLL_TIMEOUT = 2_500L
        private const val TAP_PASSTHROUGH_SETTLE_DELAY = 32L
        private const val TAP_REPLAY_TIMEOUT = 500L
        private const val EMPTY_CHOICE_OCR_BASE_COOLDOWN = 600L
        private const val EMPTY_CHOICE_OCR_MAX_COOLDOWN = 1_200L
        private const val FRESHNESS_CHECK_TRANSLATION_DELAY = 800L
        private const val VISUAL_FINGERPRINT_STEP = 3
        private const val VISUAL_FINGERPRINT_MAX_DIFF_RATIO = 0.035f
        private const val EARLY_TRANSLATION_STABLE_DELAY = 80L
        private const val EARLY_TRANSLATION_MIN_DIALOGUE_CHARS = 6
        private const val COMPLETED_DIALOGUE_STABLE_DELAY = 0L
        private const val COMPLETED_DIALOGUE_STABLE_SCANS = 2
        private const val RUBY_MAX_CHARS = 14
        private const val RUBY_HEIGHT_RATIO = 0.72f

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
                resetEarlyTranslation()
                resetCompletedDialogueCandidate()
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

    fun requestManualTranslation(afterMenuDismiss: Boolean = false): Boolean {
        if (TranslationTrigger.isAutoTranslateEnabled()) return false

        if (!isFgoForeground ||
            isProcessing ||
            tapAdvancePolling ||
            TranslationTrigger.isUiBlockingOcr()
        ) {
            TranslationTrigger.requestTranslation(afterMenuDismiss)
            return true
        }

        TranslationTrigger.cancelPendingTranslation()
        translationJob = serviceScope.launch {
            if (afterMenuDismiss) delay(MANUAL_MENU_DISMISS_SETTLE_DELAY)
            processScreen(ProcessingMode.MANUAL)
        }
        return true
    }

    private fun cancelCurrentTranslation() {
        stopVersion++
        TranslationTrigger.cancelPendingTranslation()
        translationJob?.cancel()
        translationJob = null
        resetEarlyTranslation()
        resetCompletedDialogueCandidate()
        lastRenderedSourceText = ""
        renderedChoiceBounds = emptyList()
        waitingForChoiceSelectionExit = false
        isForwardingOverlayTap = false
        choiceOcrSuppressedUntil = 0L
        suppressedChoiceBoundsKey = ""
        emptyChoiceOcrStreak = 0
        translationOverlay.hide()
    }

    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (isFgoForeground &&
                        !isProcessing &&
                        !tapAdvancePolling &&
                        !TranslationTrigger.isUiBlockingOcr()
                    ) {
                        val autoEnabled = TranslationTrigger.isAutoTranslateEnabled()
                        val manualRequest = if (autoEnabled) false else TranslationTrigger.consumeRequest()
                        if (autoEnabled &&
                            !translationOverlay.isShowing() &&
                            SystemClock.elapsedRealtime() >= autoScanReadyAt
                        ) {
                            translationJob = serviceScope.launch {
                                processScreen(ProcessingMode.AUTO)
                            }
                        } else if (manualRequest) {
                            FgoLogger.debug(tag, "Translate Now requested")
                            val waitForMenuDismissal = TranslationTrigger.consumeMenuDismissSettleRequired()
                            translationJob = serviceScope.launch {
                                if (waitForMenuDismissal) delay(MANUAL_MENU_DISMISS_SETTLE_DELAY)
                                processScreen(ProcessingMode.MANUAL)
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
        val manualMode = mode == ProcessingMode.MANUAL
        val autoMode = mode == ProcessingMode.AUTO
        isProcessing = true
        val processStartedAt = SystemClock.elapsedRealtime()
        val processingVersion = stopVersion

        var screenshot: Bitmap? = null
        var restoreHiddenOverlay = false
        try {
            if (translationOverlay.isShowing()) {
                if (!manualMode) return
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

            var dialogueComplete = false
            val classifiedRegions = if (manualMode) {
                val choiceRecognition = recognizeChoiceRegions(source, screenRegions, mode)
                val choiceRegions = choiceRecognition.regions
                if (choiceRegions.isNotEmpty()) {
                    resetEarlyTranslation(clearTypingCandidate = false)
                    resetCompletedDialogueCandidate()
                    FgoLogger.debug(tag, "Manual choice text detected; reading dialogue for context")
                    val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
                    mergeManualSceneRegions(choiceRegions, dialogueRegions)
                } else {
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
                        dialogueRegions
                    } else {
                        dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
                            source,
                            screenRegions.dialogueComplete
                        )
                        if (choiceRecognition.bounds.isNotEmpty()) {
                            FgoLogger.debug(tag, "Manual choice panels detected but OCR returned no text")
                        }
                        emptyList()
                    }
                }
            } else {
                val choiceRecognition = recognizeChoiceRegions(source, screenRegions, mode)
                val choiceRegions = choiceRecognition.regions
                if (waitingForChoiceSelectionExit) {
                    if (choiceRecognition.bounds.isNotEmpty()) {
                        FgoLogger.debug(tag, "Choice selection is still leaving the screen; suppressing repeated choice translation")
                        return
                    }
                    waitingForChoiceSelectionExit = false
                    FgoLogger.debug(tag, "Choice selection left the screen; resuming auto translation")
                }
                dialogueComplete = if (choiceRegions.isEmpty()) {
                    backgroundDetector.isDialogueCompleteMarkerVisible(
                        source,
                        screenRegions.dialogueComplete
                    )
                } else {
                    false
                }
                if (choiceRegions.isNotEmpty()) {
                    resetEarlyTranslation(clearTypingCandidate = false)
                    resetCompletedDialogueCandidate()
                    FgoLogger.debug(tag, "Choice text detected; translating choices before dialogue marker check")
                    choiceRegions
                } else if (choiceRecognition.bounds.isNotEmpty()) {
                    resetEarlyTranslation(clearTypingCandidate = false)
                    resetCompletedDialogueCandidate()
                    FgoLogger.debug(tag, "Choice panels detected by pixels but OCR returned no text; waiting for readable choices")
                    return
                } else if (dialogueComplete) {
                    recognizeDialogueRegions(source, screenRegions)
                } else {
                    resetCompletedDialogueCandidate()
                    val typingRegions = recognizeDialogueRegions(source, screenRegions)
                    val typingSceneSource = sceneSourceFor(typingRegions)
                    if (typingSceneSource != null && typingSceneSource.hasDialogue) {
                        val detectedLines = typingRegions.flatMap { it.lines }
                        val storyResult = storyDetector.detect(detectedLines, currentScreenWidth, currentScreenHeight)
                        FgoLogger.debug(tag, "Typing story detection: ${storyResult.isStoryScene}, ${storyResult.reason}")
                        maybeStartEarlyTranslation(typingSceneSource, processingVersion)
                        return
                    } else {
                        FgoLogger.debug(tag, "Dialogue is still typing and no stable OCR text is visible")
                        return
                    }
                }
            }
            if (classifiedRegions.isEmpty()) {
                if (dialogueComplete) {
                    resetEarlyTranslation()
                    restoreHiddenOverlay = false
                    FgoLogger.debug(tag, "No translatable completed dialogue detected in FGO regions")
                    translationOverlay.hide()
                } else {
                    FgoLogger.debug(tag, "Dialogue is still typing and no choices are visible")
                }
                return
            }

            if (autoMode) {
                val detectedLines = classifiedRegions.flatMap { it.lines }
                val storyResult = storyDetector.detect(detectedLines, currentScreenWidth, currentScreenHeight)
                FgoLogger.debug(tag, "Story detection: ${storyResult.isStoryScene}, ${storyResult.reason}")
            } else {
                FgoLogger.debug(tag, "Manual path uses fixed dialogue/choice regions without story guard")
            }

            val sceneSource = sceneSourceFor(classifiedRegions)
            if (sceneSource == null) {
                translationOverlay.hide()
                return
            }
            val recognitionDuration = SystemClock.elapsedRealtime() - processStartedAt
            val sourceFingerprint = sceneSource.fingerprint
            if (manualMode && restoreHiddenOverlay && sourceFingerprint == lastRenderedSourceText) {
                FgoLogger.debug(tag, "Manual source unchanged; restoring previous overlay without translation")
                return
            }
            if (autoMode) {
                if (sourceFingerprint == lastRenderedSourceText) {
                    FgoLogger.debug(tag, "Dialogue unchanged; waiting for new OCR text")
                    return
                }
                if (sceneSource.hasDialogue &&
                    sceneSource.input.choices.isEmpty() &&
                    !isCompletedDialogueStable(sceneSource)
                ) {
                    maybeStartEarlyTranslation(
                        sceneSource = sceneSource,
                        processingVersion = processingVersion,
                        requireStableTypingCandidate = false
                    )
                    return
                }
            }

            restoreHiddenOverlay = false
            val translationStartedAt = SystemClock.elapsedRealtime()
            val sceneTranslation = if (manualMode) {
                translateSceneSource(sceneSource)
            } else {
                awaitEarlyTranslation(sourceFingerprint) ?: translateSceneSource(sceneSource)
            }
            val translationDuration = SystemClock.elapsedRealtime() - translationStartedAt
            val layoutStartedAt = SystemClock.elapsedRealtime()
            val instructions = withContext(Dispatchers.Default) {
                buildRenderInstructions(source, sceneSource, sceneTranslation)
            }
            val layoutDuration = SystemClock.elapsedRealtime() - layoutStartedAt

            if (instructions.isEmpty()) {
                translationOverlay.hide()
                return
            }
            val resultBuildDuration = translationDuration + layoutDuration
            if (autoMode && resultBuildDuration >= FRESHNESS_CHECK_TRANSLATION_DELAY) {
                val sourceVisualFingerprint = visualFingerprintFor(source, classifiedRegions)
                if (!isSourceVisuallyCurrent(sourceVisualFingerprint)) {
                    FgoLogger.debug(tag, "Dialogue changed during translation; discarding stale result")
                    return
                }
            }
            if (resultBuildDuration < FRESHNESS_CHECK_TRANSLATION_DELAY) {
                FgoLogger.debug(
                    tag,
                    "Fast translation (${translationDuration}ms + layout ${layoutDuration}ms); rendering without OCR recheck"
                )
            }
            if (processingVersion != stopVersion) {
                FgoLogger.debug(tag, "Translation was stopped; discarding completed result")
                return
            }
            if (TranslationTrigger.isUiBlockingOcr()) {
                FgoLogger.debug(tag, "Overlay UI opened during translation; discarding result")
                return
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
            lastRenderedSourceText = sourceFingerprint
            resetCompletedDialogueCandidate()
            renderedChoiceBounds = instructions
                .filter { it.region.region == TextRegion.CHOICE_BUTTON }
                .map { Rect(it.region.boundingBox) }
            addHistoryEntry(instructions)
            val overlayStartedAt = SystemClock.elapsedRealtime()
            translationOverlay.updateImage(rendered)
            val overlayDuration = SystemClock.elapsedRealtime() - overlayStartedAt
            FgoLogger.info(
                tag,
                "Pipeline ready ($mode): ocr=${recognitionDuration}ms, translate=${translationDuration}ms, " +
                    "layout=${layoutDuration}ms, render=${renderDuration}ms, overlay=${overlayDuration}ms, " +
                    "total=${SystemClock.elapsedRealtime() - processStartedAt}ms"
            )
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

    private fun isCompletedDialogueStable(sceneSource: SceneSource): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (sceneSource.fingerprint != completedDialogueCandidateFingerprint) {
            completedDialogueCandidateFingerprint = sceneSource.fingerprint
            completedDialogueCandidateFirstSeenAt = now
            completedDialogueCandidateSeenCount = 1
            if (COMPLETED_DIALOGUE_STABLE_DELAY <= 0L &&
                COMPLETED_DIALOGUE_STABLE_SCANS <= 1
            ) {
                return true
            }
            FgoLogger.debug(tag, "Completed dialogue candidate changed; waiting for stable OCR")
            return false
        }

        completedDialogueCandidateSeenCount++
        val stableFor = now - completedDialogueCandidateFirstSeenAt
        val stable = stableFor >= COMPLETED_DIALOGUE_STABLE_DELAY &&
            completedDialogueCandidateSeenCount >= COMPLETED_DIALOGUE_STABLE_SCANS
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
        if (sceneSource.fingerprint == lastRenderedSourceText) return

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
                val result = translateSceneSource(sceneSource)
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
                textColor = sampleOriginalTextColor(source, regionAndText.region)
            )
        }
    }

    private fun renderableNameTranslation(sourceText: String, result: TranslateResult?): String? {
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

    private fun samePlainText(left: String, right: String): Boolean {
        return TextNormalizer.normalizeForTranslation(left)
            .filterNot { it.isNameComparePunctuation() }
            .equals(
                TextNormalizer.normalizeForTranslation(right).filterNot { it.isNameComparePunctuation() },
                ignoreCase = true
            )
    }

    private fun Char.isJapaneseKana(): Boolean {
        return this in '\u3040'..'\u30ff'
    }

    private fun Char.isNameComparePunctuation(): Boolean {
        return isWhitespace() || this in setOf(
            '・', '･', '·', '•', '.', '．', '。', '、', ',', '，',
            '!', '！', '?', '？', ':', '：', ';', '；'
        )
    }

    private fun sourceTextFor(region: ClassifiedRegion): String {
        return when (region.region) {
            TextRegion.DIALOGUE_BOX -> formatDialogueForTranslation(region.lines)
            else -> region.lines
                .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
                .joinToString("\n") { it.text }
                .trim()
        }
    }

    private fun formatDialogueForTranslation(lines: List<OcrTextLine>): String {
        if (lines.size < 2) {
            return lines.joinToString("\n") { it.text }.trim()
        }

        val sorted = lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        if (sorted.size < 2) return sorted.joinToString("\n") { it.text }.trim()

        val heights = sorted.map { it.boundingBox.height().coerceAtLeast(1) }.sorted()
        val medianHeight = heights[heights.size / 2]
        val rubyLines = sorted.filter { line -> isLikelyRubyLine(line, medianHeight) }.toSet()
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
                    compareBy<OcrTextLine> { kotlin.math.abs(it.boundingBox.centerX() - ruby.boundingBox.centerX()) }
                        .thenBy { it.boundingBox.top - ruby.boundingBox.bottom }
                )
            if (main != null) {
                rubyByMain.getOrPut(main) { mutableListOf() }.add(ruby)
            }
        }

        return mainLines
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
            .joinToString("\n") { main ->
                val rubies = rubyByMain[main]
                    ?.sortedBy { it.boundingBox.left }
                    .orEmpty()
                if (rubies.isEmpty()) {
                    main.text
                } else {
                    rubies.fold(main.text) { text, ruby ->
                        insertRubyAnnotation(text, main.boundingBox, ruby, useJapaneseRubyMarkup = true)
                    }
                }
            }
            .trim()
    }

    private fun isLikelyRubyLine(line: OcrTextLine, medianHeight: Int): Boolean {
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
        return rubyChars >= (text.length * 0.7f).toInt().coerceAtLeast(1) &&
            text.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
    }

    private fun horizontalOverlap(a: Rect, b: Rect): Int {
        return (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
    }

    private fun insertRubyAnnotation(
        mainText: String,
        mainBounds: Rect,
        ruby: OcrTextLine,
        useJapaneseRubyMarkup: Boolean
    ): String {
        if (mainText.isBlank()) return mainText
        val rubyText = ruby.text.trim()
        if (rubyText.isBlank() ||
            mainText.contains("《$rubyText》") ||
            mainText.contains("($rubyText)") ||
            mainText.contains(rubyText)
        ) {
            return mainText
        }

        val approximateCharWidth = mainBounds.width().toFloat() / mainText.length.coerceAtLeast(1)
        val rawIndex = ((ruby.boundingBox.centerX() - mainBounds.left) / approximateCharWidth)
            .toInt()
            .coerceIn(1, mainText.length)
        val insertIndex = refineRubyInsertIndex(mainText, rawIndex)
        val annotation = if (useJapaneseRubyMarkup) {
            "《$rubyText》"
        } else {
            "($rubyText)"
        }
        return mainText.substring(0, insertIndex) + annotation + mainText.substring(insertIndex)
    }

    private fun refineRubyInsertIndex(text: String, rawIndex: Int): Int {
        val punctuation = setOf('、', '。', '，', ',', '！', '!', '？', '?', '…', '」', '』', ')', '）')
        var index = rawIndex.coerceIn(1, text.length)
        while (index < text.length && text[index] in setOf('は', 'が', 'を', 'に', 'へ', 'で', 'と', 'も')) {
            break
        }
        while (index > 1 && text[index - 1] in punctuation) {
            index--
        }
        return index.coerceIn(1, text.length)
    }

    private fun sampleOriginalTextColor(source: Bitmap, region: ClassifiedRegion): Int? {
        var redScore = 0
        var cyanScore = 0
        var whiteScore = 0

        for (line in region.lines) {
            val bounds = Rect(line.boundingBox)
            bounds.intersect(0, 0, source.width, source.height)
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            for (y in bounds.top until bounds.bottom step 2) {
                for (x in bounds.left until bounds.right step 2) {
                    val pixel = source.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    val spread = max - min

                    if (r >= 180 && g <= 125 && b <= 125 && r - maxOf(g, b) >= 45) {
                        redScore++
                    } else if (g >= 150 && b >= 150 && r <= 130 && minOf(g, b) - r >= 45) {
                        cyanScore++
                    } else if (r >= 175 && g >= 175 && b >= 175 && spread <= 70) {
                        whiteScore++
                    }
                }
            }
        }

        return when {
            cyanScore >= 8 && cyanScore >= redScore && cyanScore > whiteScore / 3 -> android.graphics.Color.rgb(80, 235, 235)
            redScore >= 8 && redScore > whiteScore / 3 -> android.graphics.Color.rgb(255, 80, 80)
            whiteScore > 0 -> android.graphics.Color.rgb(245, 245, 240)
            else -> null
        }
    }

    private fun addHistoryEntry(instructions: List<RenderInstruction>) {
        val nameInstruction = instructions.firstOrNull { it.region.region == TextRegion.NAME_LABEL }
        val dialogueInstruction = instructions.firstOrNull { it.region.region == TextRegion.DIALOGUE_BOX }
        val choiceInstructions = instructions.filter { it.region.region == TextRegion.CHOICE_BUTTON }

        val name = nameInstruction
            ?.translatedText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val dialogue = dialogueInstruction
            ?.translatedText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val choicePairs = choiceInstructions
            .map { it.translatedText.trim() }
            .zip(choiceInstructions.map { it.textColor })
            .filter { it.first.isNotBlank() }

        if (name != null || dialogue != null || choicePairs.isNotEmpty()) {
            SessionTranslationHistory.add(
                SessionTranslationEntry(
                    speakerName = name,
                    dialogueText = dialogue,
                    choices = choicePairs.map { it.first },
                    speakerNameColor = nameInstruction?.textColor,
                    dialogueTextColor = dialogueInstruction?.textColor,
                    choiceColors = choicePairs.map { it.second }
                )
            )
        }
    }

    private suspend fun recognizeChoiceRegions(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        mode: ProcessingMode
    ): ChoiceRecognitionResult {
        val now = SystemClock.elapsedRealtime()
        val useEmptyChoiceCooldown = mode == ProcessingMode.AUTO
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
        val choiceBounds = filterChoiceBounds(rawChoiceBounds, screenRegions.choiceSearch)
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
            retryEmptyTargetsIndividually = choiceBounds.size >= 2
        )
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
        )
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
                val currentChoices = backgroundDetector.detectChoiceButtons(currentScreenshot, screenRegions.choiceSearch)
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
        if (isForwardingOverlayTap) {
            FgoLogger.debug(tag, "Ignoring duplicate translated overlay tap while replay is active")
            return
        }
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
                        FgoLogger.debug(tag, "Overlay tap replay completed; holding translation before polling next dialogue")
                        pollNextCompletedDialogueAfterTap()
                    } else {
                        FgoLogger.debug(tag, "Overlay tap replay completed; holding translation before capture hide")
                        delay(TAP_TRANSLATION_READ_HOLD_DELAY)
                        translationOverlay.hideForCapture()
                    }
                } else {
                    FgoLogger.warn(tag, "Overlay tap replay failed; restoring current translation")
                    translationOverlay.setTranslatedOverlayTouchable(true)
                }
            } finally {
                isForwardingOverlayTap = false
            }
        }
    }

    private suspend fun pollNextCompletedDialogueAfterTap() {
        tapAdvancePolling = true
        try {
            delay(TAP_TRANSLATION_READ_HOLD_DELAY)
            translationOverlay.hideForCapture()

            val deadline = SystemClock.elapsedRealtime() + NEXT_DIALOGUE_POLL_TIMEOUT
            while (SystemClock.elapsedRealtime() < deadline &&
                TranslationTrigger.isAutoTranslateEnabled()
            ) {
                processScreen(ProcessingMode.AUTO)
                if (translationOverlay.isShowing()) {
                    FgoLogger.debug(tag, "Next dialogue translated during tap handoff")
                    return
                }
                delay(NEXT_DIALOGUE_POLL_INTERVAL)
            }
            FgoLogger.debug(tag, "Next dialogue handoff polling ended; normal scan will continue")
        } finally {
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

    private fun AccessibilityEvent.isDialogueAdvanceEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

}
