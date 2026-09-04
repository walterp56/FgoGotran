package com.fgogotran.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ComponentName
import android.content.Context
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.battle.BattleSubtitleController
import com.fgogotran.crop.CropResultOverlay
import com.fgogotran.crop.CropResultRenderer
import com.fgogotran.crop.CropSelectionOverlay
import com.fgogotran.capture.MediaProjectionCapture
import com.fgogotran.crop.CropTextLine
import com.fgogotran.data.SettingsRepository
import com.fgogotran.game.FgoPackages
import com.fgogotran.game.ForegroundTestOverride
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.ocr.OcrInputScale
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
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.story.StoryDetector
import com.fgogotran.translation.SceneTranslateInput
import com.fgogotran.translation.SceneTranslateResult
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory
import com.fgogotran.translation.FgoDialogueSymbols
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.TranslationMode
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.translation.TranslateResult
import com.fgogotran.translation.Translator
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.util.FgoLogger
import com.fgogotran.voice.AiVoiceService
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
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appAnalytics: AppAnalytics
    @Inject lateinit var aiVoiceService: AiVoiceService
    @Inject lateinit var diagnosticEventStore: DiagnosticEventStore
    @Inject lateinit var battleSubtitles: BattleSubtitleController
    @Inject lateinit var cropSelectionOverlay: CropSelectionOverlay

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false
    private var battleSubtitlesEnabled = false
    private var foregroundTestOverrideEnabled = false
    private val foregroundTestOverride = ForegroundTestOverride()
    private var battleMonitoring = false
    private var battleFrameBusy = false
    private var battleScanJob: Job? = null
    private var nextBattleScanAt = 0L
    private var translationJob: Job? = null
    private var cropTranslationJob: Job? = null
    private var transientForegroundLossJob: Job? = null
    private var transientForegroundLossStartedAt = 0L
    private var stopVersion = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var isFgoForeground = false
    private val isEffectiveFgoForeground: Boolean
        get() = foregroundTestOverride.isEffective(foregroundTestOverrideEnabled, isFgoForeground)
    private var lastManualRenderedSourceText = ""
    private var lastSemiAutoRenderedSourceText = ""
    private var lastSemiAutoChoiceRenderedSourceText = ""
    private var lastAutoRenderedSourceText = ""
    private var lastManualRenderedStabilityKey = ""
    private var lastSemiAutoRenderedStabilityKey = ""
    private var lastSemiAutoChoiceRenderedStabilityKey = ""
    private var lastAutoRenderedStabilityKey = ""
    private var autoScanReadyAt = 0L
    private var semiAutoBackgroundRetryAt = 0L
    private var semiAutoBlankOcrStreak = 0
    private var semiAutoScreenshotFailStreak = 0
    private var renderedChoiceBounds: List<Rect> = emptyList()
    private var waitingForChoiceSelectionExit = false
    private var isForwardingOverlayTap = false
    private var choiceOcrSuppressedUntil = 0L
    private var suppressedChoiceBoundsKey = ""
    private var emptyChoiceOcrStreak = 0
    private var tapAdvancePolling = false
    private var failedAutoRenderFingerprint = ""
    private var failedAutoRenderRetryAt = 0L
    private var autoTapHandoffPreviousFingerprint = ""
    private var overlayButtonTouchActive = false
    private var overlayButtonLongPressHandled = false
    private var overlayButtonTouchCancelled = false
    private var overlayButtonDownX = 0f
    private var overlayButtonDownY = 0f
    private var overlayButtonLastX = 0f
    private var overlayButtonLastY = 0f
    private var overlayButtonDragging = false
    private var overlayButtonLongPressJob: Job? = null
    private var currentPlayerName = ""
    private var showOriginalGameText = false
    private var gameServer = SettingsRepository.DEFAULT_GAME_SERVER
    private var aiVoiceEnabled = false
    private var aiVoiceApiHintsEnabled = SettingsRepository.DEFAULT_AI_VOICE_API_HINTS_ENABLED
    private var aiVoiceNamedDialogueEnabled = SettingsRepository.DEFAULT_AI_VOICE_NAMED_DIALOGUE_ENABLED
    private var aiVoiceNoSpeakerDialogueEnabled = SettingsRepository.DEFAULT_AI_VOICE_NO_SPEAKER_DIALOGUE_ENABLED
    private var aiVoiceChoiceTextEnabled = SettingsRepository.DEFAULT_AI_VOICE_CHOICE_TEXT_ENABLED
    private var aiVoiceMasterVoice = SettingsRepository.DEFAULT_AI_VOICE_MASTER_VOICE
    private var lastScreenshotErrorCode = 0
    private val unsupportedFgoLikePackageLoggedAt = LinkedHashMap<String, Long>()

    companion object {
        const val FGO_PACKAGE = FgoPackages.JP
        private const val APP_PACKAGE = "com.fgogotran"
        private const val DETECTION_INTERVAL = 120L
        private const val CAPTURE_SETTLE_DELAY = 16L
        private const val SEMI_AUTO_CHOICE_RETRY_DELAY_MS = 250L
        private const val MANUAL_MENU_DISMISS_SETTLE_DELAY = 300L
        private const val TRANSIENT_SYSTEM_UI_FOREGROUND_RECHECK_DELAY = 3_000L
        private const val TRANSIENT_SYSTEM_UI_FOREGROUND_MAX_DELAY = 30_000L
        private const val TAP_TRANSLATION_READ_HOLD_DELAY = 120L
        private const val NEXT_DIALOGUE_POLL_INTERVAL = 120L
        private const val NEXT_DIALOGUE_POLL_TIMEOUT = 2_500L
        private const val TAP_PASSTHROUGH_SETTLE_DELAY = 56L
        private const val TAP_REPLAY_TIMEOUT = 500L
        private const val VOICE_HINT_REQUEST_TIMEOUT_MS = 2_500L
        private const val OVERLAY_BUTTON_LONG_PRESS_TIMEOUT = 420L
        private const val OVERLAY_BUTTON_TOUCH_SLOP = 18f
        private const val CROP_TRANSLATION_WAIT_TIMEOUT = 700L
        private const val CROP_TRANSLATION_MAX_TOKENS = 512
        private const val CROP_OCR_SCALE = 2
        private const val CHOICE_OCR_SCALE = 2
        private const val MIN_FIXED_SLOT_CONFIDENCE = 0.55f
        private const val EMPTY_CHOICE_OCR_BASE_COOLDOWN = 600L
        private const val EMPTY_CHOICE_OCR_MAX_COOLDOWN = 1_200L
        private const val SEMI_AUTO_BLANK_OCR_BASE_COOLDOWN = 300L
        private const val SEMI_AUTO_BLANK_OCR_MAX_COOLDOWN = 900L
        private const val SEMI_AUTO_SCREENSHOT_FAIL_BASE_COOLDOWN = 250L
        private const val SEMI_AUTO_SCREENSHOT_FAIL_MAX_COOLDOWN = 1_000L
        private const val FRESHNESS_CHECK_TRANSLATION_DELAY = 800L
        private const val UNSUPPORTED_FGO_PACKAGE_LOG_COOLDOWN_MS = 10L * 60L * 1000L
        private const val VISUAL_FINGERPRINT_STEP = 3
        private const val VISUAL_FINGERPRINT_MAX_DIFF_RATIO = 0.035f
        private const val AUTO_FAILED_TRANSLATION_RETRY_COOLDOWN = 5_000L
        private const val RED_DIALOGUE_OCR_SCALE = 2
        private const val RED_DIALOGUE_SCAN_STEP = 3
        private const val RED_DIALOGUE_MIN_SAMPLE_PIXELS = 18
        private const val RED_DIALOGUE_MIN_SAMPLE_RATIO = 0.0006f
        private const val RED_DIALOGUE_FORCE_FALLBACK_RATIO = 0.0025f
        private const val RUBY_MAX_CHARS = 14
        private const val RUBY_MAX_BASE_CHARS = 12
        private const val RUBY_HEIGHT_RATIO = 0.72f
        private const val LOG_TEXT_CHUNK_SIZE = 900
        private const val MIN_PALETTE_TEXT_PIXELS = 8
        private const val NO_SPEAKER_PROFILE_ID = "no_speaker"
        private const val MASTER_PROFILE_MALE = "藤丸立香(男)"
        private const val MASTER_PROFILE_FEMALE = "藤丸立香(女)"
        private const val SCREENSHOT_ERROR_BITMAP_UNAVAILABLE = -1
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
        private val FGO_RENDER_WHITE = Color.rgb(245, 245, 240)
        private val FGO_RENDER_RED = Color.rgb(220, 0, 0)
        private val FGO_TEXT_COLOR_SAMPLES = listOf(
            TextColorSample(
                sampleColor = Color.rgb(245, 245, 240),
                renderColor = Color.rgb(245, 245, 240),
                maxDistanceSquared = 120 * 120
            ),
            TextColorSample(
                sampleColor = FGO_RENDER_RED,
                renderColor = FGO_RENDER_RED,
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
        private val NON_BLOCKING_OVERLAY_PACKAGES = setOf(
            "com.samsung.android.app.smartcapture"
        )

        @Volatile
        var instance: FgoAccessibilityService? = null
            private set(value) {
                field = value
                _serviceStarted.value = value != null
            }

        fun isEnabledInSettings(context: Context): Boolean {
            if (_serviceStarted.value) return true

            return runCatching {
                val resolver = context.contentResolver
                val accessibilityEnabled = Settings.Secure.getInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
                if (!accessibilityEnabled) return@runCatching false

                val expected = ComponentName(context, FgoAccessibilityService::class.java)
                Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                    .split(':')
                    .mapNotNull { ComponentName.unflattenFromString(it.trim()) }
                    .any { it.packageName == expected.packageName && it.className == expected.className }
            }.getOrDefault(false)
        }
    }

    private val tag = "Accessibility"
    private data class RegionSourceText(
        val region: ClassifiedRegion,
        val text: String
    )

    private data class SceneSource(
        val regions: List<RegionSourceText>,
        val input: SceneTranslateInput,
        val voiceDialogue: String?,
        val fingerprint: String,
        val stabilityKey: String,
        val hasDialogue: Boolean
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

    private data class DialogueSourceText(
        val translationText: String,
        val voiceText: String
    )

    private sealed class AutoScanResult {
        data class Ready(
            val regions: List<ClassifiedRegion>
        ) : AutoScanResult()

        object Waiting : AutoScanResult()
        object EmptyCompletedDialogue : AutoScanResult()
    }

    private enum class ProcessingMode(val userInitiated: Boolean) {
        MANUAL_TAP(true),
        SEMI_AUTO_CHOICE_TAP(true),
        SEMI_AUTO_BACKGROUND(false),
        AUTO_BACKGROUND(false)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        watchDebugLogging()
        initScreenSize()
        battleSubtitles.init(this)
        translationOverlay.init(
            serviceContext = this,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            onOverlayTap = { x, y -> handleTranslatedOverlayTap(x, y) },
            onOverlayTouch = { event -> handleTranslatedOverlayTouch(event) }
        )
        cropResultOverlay.init(
            serviceContext = this,
            onTap = { x, y -> handleCropResultTap(x, y) },
            onTouch = { event -> handleCropResultOverlayTouch(event) }
        )
        restoreLastTranslationMode()
        watchGameServer()
        watchPlayerName()
        watchOriginalTextDisplay()
        watchVoiceReadScope()
        serviceScope.launch {
            settingsRepository.battleSubtitlesEnabled.collect { enabled ->
                if (battleSubtitlesEnabled == enabled) return@collect
                battleSubtitlesEnabled = enabled
                stopBattleMonitoring()
                nextBattleScanAt = 0
            }
        }
        serviceScope.launch {
            settingsRepository.foregroundTestOverrideEnabled.collect { enabled ->
                if (foregroundTestOverrideEnabled == enabled) return@collect
                foregroundTestOverrideEnabled = enabled
                val activePackage = rootInActiveWindow?.packageName?.toString()
                foregroundTestOverride.observeExternalPackage(
                    activePackage?.takeIf { enabled && it.isEligibleForegroundTestPackage() }
                )
                cancelCurrentTranslation()
                stopBattleMonitoring()
                nextBattleScanAt = 0L
                if (!isEffectiveFgoForeground) {
                    translationOverlay.hideAll()
                    cropResultOverlay.hide()
                }
                FgoLogger.info(tag, "Foreground test override changed: enabled=$enabled")
            }
        }
        reportServiceUsage()
        warmUpManualPipeline()
        FgoLogger.info(tag, "Gesture injection available: ${canPerformGestures()}")
        FgoLogger.info(tag, "Service connected: ${screenWidth}x${screenHeight}")
    }

    private fun reportServiceUsage() {
        serviceScope.launch(Dispatchers.IO) {
            appAnalytics.reportAppUsed()
            appAnalytics.reportCurrentBackendType()
        }
    }

    private fun watchPlayerName() {
        serviceScope.launch {
            settingsRepository.playerName.collect { name ->
                currentPlayerName = TextNormalizer.normalizeForTranslation(name)
            }
        }
    }

    private fun watchOriginalTextDisplay() {
        serviceScope.launch {
            settingsRepository.showOriginalGameText.collect { enabled ->
                showOriginalGameText = enabled
            }
        }
    }

    private fun watchVoiceReadScope() {
        serviceScope.launch {
            settingsRepository.aiVoiceEnabled.collect { enabled ->
                aiVoiceEnabled = enabled
            }
        }
        serviceScope.launch {
            settingsRepository.aiVoiceApiHintsEnabled.collect { enabled ->
                aiVoiceApiHintsEnabled = enabled
            }
        }
        serviceScope.launch {
            settingsRepository.aiVoiceNamedDialogueEnabled.collect { enabled ->
                aiVoiceNamedDialogueEnabled = enabled
            }
        }
        serviceScope.launch {
            settingsRepository.aiVoiceNoSpeakerDialogueEnabled.collect { enabled ->
                aiVoiceNoSpeakerDialogueEnabled = enabled
            }
        }
        serviceScope.launch {
            settingsRepository.aiVoiceChoiceTextEnabled.collect { enabled ->
                aiVoiceChoiceTextEnabled = enabled
            }
        }
        serviceScope.launch {
            settingsRepository.aiVoiceMasterVoice.collect { masterVoice ->
                aiVoiceMasterVoice = SettingsRepository.normalizeAiVoiceMasterVoice(masterVoice)
            }
        }
    }

    private fun watchGameServer() {
        serviceScope.launch {
            settingsRepository.gameServer.collect { server ->
                val normalizedServer = SettingsRepository.normalizeGameServer(server)
                if (normalizedServer == gameServer) return@collect
                gameServer = normalizedServer
                stopBattleMonitoring()
                cancelCurrentTranslation()
                if (!isJapaneseServer()) {
                    translationOverlay.hideAll()
                    cropResultOverlay.hide()
                }
                runnerOverlay.refreshButtonMode()
                FgoLogger.info(tag, "Game server mode changed: $normalizedServer")
            }
        }
    }

    private fun watchDebugLogging() {
        serviceScope.launch {
            settingsRepository.debugLoggingEnabled.collect { enabled ->
                FgoLogger.setEnabled(enabled)
            }
        }
    }

    private fun isJapaneseServer(): Boolean {
        return SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP
    }

    private fun restoreLastTranslationMode() {
        TranslationTrigger.setTranslationMode(TranslationMode.MANUAL)
        serviceScope.launch {
            val restoredMode = runCatching {
                settingsRepository.getLastTranslationMode().toTranslationMode()
            }.getOrElse { error ->
                FgoLogger.warn(tag, "Failed to restore translation mode; using manual", error)
                TranslationMode.MANUAL
            }
            applyTranslationMode(restoredMode)
            startDetectionLoop()
            FgoLogger.debug(tag, "Restored translation mode: $restoredMode")
        }
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
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.className?.toString() == "com.fgogotran.MainActivity") {
                    markFgoForegroundLost(packageName)
                    return
                }
                if (isEffectiveFgoForeground) {
                    cancelTransientForegroundLoss()
                }
                // Our overlays emit window/touch events when they appear or redraw. Treat them as UI noise.
            }
            isFgoEvent -> {
                cancelTransientForegroundLoss()
                if (foregroundTestOverride.observeExternalPackage(null)) {
                    stopBattleMonitoring(resetSession = false)
                }
                if (!isFgoForeground) {
                    FgoLogger.info(tag, "FGO foreground detected: event=$packageName")
                }
                isFgoForeground = true
                if (event.isDialogueAdvanceEvent()) {
                    if (translationOverlay.isShowing()) {
                        FgoLogger.debug(tag, "FGO dialogue advance detected; hiding translated overlay for next OCR")
                        translationOverlay.hide()
                    }
                }
            }
            else -> {
                recordUnsupportedFgoLikePackage(packageName, event)
                when {
                    isEffectiveFgoForeground && packageName.isNonBlockingOverlayPackage() -> {
                        cancelTransientForegroundLoss()
                        FgoLogger.debug(tag, "Non-blocking overlay event while FGO foreground; keeping foreground: event=$packageName")
                    }
                    isEffectiveFgoForeground && packageName.isTransientSystemUiPackage() -> {
                        scheduleTransientForegroundLoss(packageName)
                    }
                    else -> {
                        cancelTransientForegroundLoss()
                        markFgoForegroundLost(packageName)
                    }
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
                foregroundTestOverrideEnabled && activePackage != null &&
                    activePackage == foregroundTestOverride.externalPackageName -> {
                    cancelTransientForegroundLoss()
                }
                activePackage?.isSupportedFgoPackage() == true -> {
                    cancelTransientForegroundLoss()
                    FgoLogger.debug(tag, "FGO still active after transient system UI; keeping foreground")
                }
                activePackage?.isNonBlockingOverlayPackage() == true -> {
                    cancelTransientForegroundLoss()
                    FgoLogger.debug(tag, "Non-blocking overlay active after transient UI; keeping FGO foreground")
                }
                activePackage == null ||
                        activePackage == APP_PACKAGE ||
                        activePackage.isTransientSystemUiPackage() -> {
                    val waitingFor = SystemClock.elapsedRealtime() - transientForegroundLossStartedAt
                    if (waitingFor >= TRANSIENT_SYSTEM_UI_FOREGROUND_MAX_DELAY) {
                        FgoLogger.debug(
                            tag,
                            "Transient system UI persisted for ${waitingFor}ms; keeping last FGO foreground"
                        )
                        cancelTransientForegroundLoss()
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
        val wasFgoForeground = isFgoForeground
        if (isFgoForeground) {
            val delayLabel = if (delayed) " after transient delay" else ""
            FgoLogger.info(tag, "FGO foreground lost$delayLabel: event=$packageName")
        }
        isFgoForeground = false
        val externalPackage = packageName.takeIf {
            foregroundTestOverrideEnabled && it.isEligibleForegroundTestPackage()
        }
        val testTargetChanged = foregroundTestOverride.observeExternalPackage(externalPackage)
        // Repeated accessibility events from the same test target do not invalidate
        // active work. Actual FGO state remains separate from the session override.
        if (externalPackage == null || testTargetChanged || wasFgoForeground) {
            stopBattleMonitoring(resetSession = false)
        }
        if (foregroundTestOverrideEnabled && (testTargetChanged || wasFgoForeground)) {
            cancelCurrentTranslation()
        }
        cancelTransientForegroundLoss()
        if (externalPackage != null && !testTargetChanged && !wasFgoForeground) return
        resetSemiAutoBackgroundState()
        translationOverlay.hideAll()
        cropResultOverlay.hide()
        // Voice playback is independent of overlay/foreground cleanup; the next voice line replaces it.
    }

    private fun restoreFgoForegroundAfterCapture(reason: String) {
        // Test captures must not corrupt the actual package-derived foreground state.
        if (foregroundTestOverrideEnabled) return
        if (isFgoForeground) return
        isFgoForeground = true
        cancelTransientForegroundLoss()
        FgoLogger.info(tag, "FGO foreground restored from OCR capture: $reason")
    }

    override fun onInterrupt() {
        FgoLogger.warn(tag, "Service interrupted")
        cancelTransientForegroundLoss()
        translationOverlay.hideAll()
        cropResultOverlay.hide()
        battleSubtitles.destroy()
        settingsRepository.setForegroundTestOverrideEnabled(false)
        serviceScope.cancel()
    }

    override fun onDestroy() {
        instance = null
        cancelTransientForegroundLoss()
        translationOverlay.destroy()
        cropResultOverlay.destroy()
        battleSubtitles.destroy()
        settingsRepository.setForegroundTestOverrideEnabled(false)
        aiVoiceService.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun setAutoTranslationEnabled(enabled: Boolean) {
        setTranslationMode(if (enabled) TranslationMode.AUTO else TranslationMode.MANUAL)
    }

    fun setTranslationMode(mode: TranslationMode, persist: Boolean = true) {
        applyTranslationMode(mode)
        if (persist) {
            serviceScope.launch(Dispatchers.IO) {
                settingsRepository.setLastTranslationMode(mode.name)
                appAnalytics.reportTranslationMode(mode)
            }
        }
    }

    private fun applyTranslationMode(mode: TranslationMode) {
        TranslationTrigger.setTranslationMode(mode)
        cancelCurrentTranslation()
        cropResultOverlay.hide()
        if (mode != TranslationMode.MANUAL) {
            autoScanReadyAt = SystemClock.elapsedRealtime() + MANUAL_MENU_DISMISS_SETTLE_DELAY
            FgoLogger.debug(tag, "Translation mode enabled: $mode")
        } else {
            autoScanReadyAt = 0L
            FgoLogger.debug(tag, "Translation mode set to manual")
        }
        runnerOverlay.refreshButtonMode()
    }

    private fun String.toTranslationMode(): TranslationMode {
        return runCatching {
            TranslationMode.valueOf(SettingsRepository.normalizeTranslationMode(this))
        }.getOrDefault(TranslationMode.MANUAL)
    }

    fun requestManualTranslation(afterMenuDismiss: Boolean = false): Boolean {
        if (!TranslationTrigger.canUserTapTranslate()) return false
        cropResultOverlay.hide()
        if (battleSubtitlesEnabled && battleSubtitles.blocksStory) {
            nextBattleScanAt = 0L
            return true
        }

        if (!canStartScreenTranslationNow()) {
            FgoLogger.debug(
                tag,
                "Manual translation queued: foreground=$isEffectiveFgoForeground, " +
                        "processing=$isProcessing, tapPolling=$tapAdvancePolling, " +
                        "forwardingTap=$isForwardingOverlayTap, " +
                        "uiBlocking=${TranslationTrigger.isUiBlockingOcr()}"
            )
            TranslationTrigger.requestTranslation(afterMenuDismiss)
            return true
        }

        startManualTranslation(
            afterMenuDismiss = afterMenuDismiss,
            requestedMode = TranslationTrigger.translationMode()
        )
        return true
    }

    private fun canStartScreenTranslationNow(): Boolean {
        return !isProcessing &&
                !battleFrameBusy &&
                cropTranslationJob?.isActive != true &&
                !tapAdvancePolling &&
                !isForwardingOverlayTap &&
                !TranslationTrigger.isUiBlockingOcr()
    }

    private fun startManualTranslation(
        afterMenuDismiss: Boolean = false,
        requestedMode: TranslationMode = TranslationMode.MANUAL
    ) {
        val processingMode = if (requestedMode == TranslationMode.SEMI_AUTO) {
            ProcessingMode.SEMI_AUTO_CHOICE_TAP
        } else {
            ProcessingMode.MANUAL_TAP
        }
        if (!isEffectiveFgoForeground) {
            FgoLogger.debug(tag, "$processingMode requested while FGO foreground flag is stale; attempting capture")
        }
        TranslationTrigger.cancelPendingTranslation()
        translationJob = serviceScope.launch {
            if (afterMenuDismiss) delay(MANUAL_MENU_DISMISS_SETTLE_DELAY)
            processScreen(processingMode)
        }
    }

    fun requestCropTranslation(bounds: Rect, restoreMode: TranslationMode? = null): Boolean {
        if (!isJapaneseServer()) {
            FgoLogger.debug(tag, "Crop translation rejected outside JP server mode")
            return false
        }
        if (!isEffectiveFgoForeground || TranslationTrigger.isUiBlockingOcr()) {
            FgoLogger.warn(tag, "Crop translation rejected; FGO foreground=$isEffectiveFgoForeground")
            return false
        }

        TranslationTrigger.setTranslationMode(TranslationMode.MANUAL)
        cancelCurrentTranslation()
        battleSubtitles.pause()
        serviceScope.launch(Dispatchers.IO) {
            appAnalytics.reportCropModeUsed()
        }
        val cropVersion = stopVersion
        cropTranslationJob = serviceScope.launch {
            var shouldRestoreMode = false
            try {
                val deadline = SystemClock.elapsedRealtime() + CROP_TRANSLATION_WAIT_TIMEOUT
                while ((isProcessing || battleFrameBusy) && SystemClock.elapsedRealtime() < deadline) {
                    delay(CAPTURE_SETTLE_DELAY)
                }
                if (isProcessing || battleFrameBusy) {
                    FgoLogger.warn(tag, "Crop translation skipped; previous pipeline is still busy")
                    showCropStatus(bounds, "请稍后再试")
                    shouldRestoreMode = true
                    return@launch
                }
                processCropTranslation(Rect(bounds))
                shouldRestoreMode = true
            } finally {
                if (shouldRestoreMode && cropVersion == stopVersion) {
                    restoreModeAfterCrop(restoreMode)
                }
            }
        }
        return true
    }

    private fun restoreModeAfterCrop(mode: TranslationMode?) {
        if (mode == null) return
        TranslationTrigger.setTranslationMode(mode)
        if (mode != TranslationMode.MANUAL) {
            autoScanReadyAt = SystemClock.elapsedRealtime() + MANUAL_MENU_DISMISS_SETTLE_DELAY
        } else {
            autoScanReadyAt = 0L
        }
        runnerOverlay.refreshButtonMode()
        FgoLogger.debug(tag, "Restored translation mode after crop: $mode")
    }

    fun clearCropTranslationOverlay() {
        cropTranslationJob?.cancel()
        cropTranslationJob = null
        cropResultOverlay.hide()
    }

    fun stopRunnerSession() {
        FgoLogger.info(tag, "Runner service stopped; disabling active translation")
        TranslationTrigger.setTranslationMode(TranslationMode.MANUAL)
        autoScanReadyAt = 0L
        tapAdvancePolling = false
        cancelCurrentTranslation()
        stopBattleMonitoring()
        settingsRepository.setForegroundTestOverrideEnabled(false)
        translationOverlay.hideAll()
        cropResultOverlay.hide()
    }

    private fun cancelCurrentTranslation() {
        stopVersion++
        TranslationTrigger.cancelPendingTranslation()
        translationJob?.cancel()
        cropTranslationJob?.cancel()
        translationJob = null
        cropTranslationJob = null
        lastManualRenderedSourceText = ""
        lastSemiAutoRenderedSourceText = ""
        lastSemiAutoChoiceRenderedSourceText = ""
        lastAutoRenderedSourceText = ""
        lastManualRenderedStabilityKey = ""
        lastSemiAutoRenderedStabilityKey = ""
        lastSemiAutoChoiceRenderedStabilityKey = ""
        lastAutoRenderedStabilityKey = ""
        failedAutoRenderFingerprint = ""
        failedAutoRenderRetryAt = 0L
        resetSemiAutoBackgroundState()
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

    private fun isSemiAutoBackgroundCoolingDown(): Boolean {
        if (!TranslationTrigger.isSemiAutoEnabled()) return false
        val now = SystemClock.elapsedRealtime()
        return now < semiAutoBackgroundRetryAt
    }

    private fun resetSemiAutoBackgroundState() {
        semiAutoBackgroundRetryAt = 0L
        semiAutoBlankOcrStreak = 0
        semiAutoScreenshotFailStreak = 0
    }

    private fun resetSemiAutoBackoff() {
        semiAutoBackgroundRetryAt = 0L
        semiAutoBlankOcrStreak = 0
        semiAutoScreenshotFailStreak = 0
    }

    private fun delaySemiAutoBackgroundFor(durationMs: Long, reason: String) {
        if (!TranslationTrigger.isSemiAutoEnabled()) return
        val retryAt = SystemClock.elapsedRealtime() + durationMs
        if (retryAt > semiAutoBackgroundRetryAt) {
            semiAutoBackgroundRetryAt = retryAt
            FgoLogger.debug(tag, "Semi-auto background delayed ${durationMs}ms: $reason")
        }
    }

    private fun rememberSemiAutoBlankOcr() {
        if (!TranslationTrigger.isSemiAutoEnabled()) return
        semiAutoBlankOcrStreak++
        semiAutoScreenshotFailStreak = 0
        val cooldown = (SEMI_AUTO_BLANK_OCR_BASE_COOLDOWN * semiAutoBlankOcrStreak)
            .coerceAtMost(SEMI_AUTO_BLANK_OCR_MAX_COOLDOWN)
        delaySemiAutoBackgroundFor(cooldown, "blank dialogue OCR")
    }

    private fun rememberSemiAutoScreenshotFailure() {
        if (!TranslationTrigger.isSemiAutoEnabled()) return
        semiAutoScreenshotFailStreak++
        semiAutoBlankOcrStreak = 0
        val cooldown = (SEMI_AUTO_SCREENSHOT_FAIL_BASE_COOLDOWN * semiAutoScreenshotFailStreak)
            .coerceAtMost(SEMI_AUTO_SCREENSHOT_FAIL_MAX_COOLDOWN)
        delaySemiAutoBackgroundFor(cooldown, "screenshot failed")
    }

    private fun stopBattleMonitoring(resetSession: Boolean = true) {
        battleMonitoring = false
        battleScanJob?.cancel()
        battleScanJob = null
        if (resetSession) battleSubtitles.reset() else battleSubtitles.suspendObservation()
        // The active OCR call owns its bitmap and clears battleFrameBusy in finally.
    }

    private fun monitorBattleIfReady() {
        val eligible = battleSubtitlesEnabled &&
            isEffectiveFgoForeground && isJapaneseServer() &&
            FgoRunnerService.serviceStarted.value && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!eligible) {
            if (battleMonitoring) stopBattleMonitoring(resetSession =
                !battleSubtitlesEnabled || !isJapaneseServer() || !FgoRunnerService.serviceStarted.value)
            return
        }
        battleMonitoring = true
        if (TranslationTrigger.isUiBlockingOcr() || translationOverlay.isShowing() || cropResultOverlay.isShowing() ||
            cropSelectionOverlay.isShowing() || cropTranslationJob?.isActive == true ||
            tapAdvancePolling || isForwardingOverlayTap) {
            battleSubtitles.pause()
            return
        }
        battleSubtitles.resume()
        battleSubtitles.refreshCaption()
        // processScreen also routes its captured frame through the battle observer. Do not
        // invalidate that observation merely because the normal pipeline owns the frame.
        if (isProcessing || battleFrameBusy || SystemClock.elapsedRealtime() < nextBattleScanAt) return
        battleFrameBusy = true
        battleScanJob = serviceScope.launch {
            var frame: Bitmap? = null
            try {
                val capturedAt = SystemClock.elapsedRealtime()
                frame = takeScreenshotCompat()
                if (frame != null) battleSubtitles.inspect(frame, capturedAt)
                else battleSubtitles.observationUnavailable()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                battleSubtitles.observationUnavailable()
                FgoLogger.warn(tag, "Battle subtitle scan failed", error)
            }
            finally {
                frame?.recycle()
                battleFrameBusy = false
                val interval = battleSubtitles.scanIntervalMs.coerceAtLeast(
                    if (MediaProjectionCapture.isAvailable()) 120L else 350L
                )
                nextBattleScanAt = SystemClock.elapsedRealtime() + interval
            }
        }
    }

    private fun startDetectionLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    monitorBattleIfReady()
                    if (canStartScreenTranslationNow()) {
                        val translationMode = TranslationTrigger.translationMode()
                        val manualRequest = if (TranslationTrigger.canUserTapTranslate()) {
                            TranslationTrigger.consumeRequest()
                        } else {
                            false
                        }
                        if (manualRequest && battleSubtitles.blocksStory) {
                            TranslationTrigger.consumeMenuDismissSettleRequired()
                            cropResultOverlay.hide()
                            nextBattleScanAt = 0L
                        } else if (manualRequest) {
                            FgoLogger.debug(tag, "Translate Now requested")
                            val waitForMenuDismissal = TranslationTrigger.consumeMenuDismissSettleRequired()
                            cropResultOverlay.hide()
                            startManualTranslation(
                                afterMenuDismiss = waitForMenuDismissal,
                                requestedMode = translationMode
                            )
                        } else if (isEffectiveFgoForeground &&
                            !battleSubtitles.blocksStory &&
                            translationMode != TranslationMode.MANUAL &&
                            !translationOverlay.isShowing() &&
                            !(translationMode == TranslationMode.SEMI_AUTO && isSemiAutoBackgroundCoolingDown()) &&
                            SystemClock.elapsedRealtime() >= autoScanReadyAt
                        ) {
                            translationJob = serviceScope.launch {
                                val backgroundMode = when (translationMode) {
                                    TranslationMode.SEMI_AUTO -> ProcessingMode.SEMI_AUTO_BACKGROUND
                                    TranslationMode.AUTO -> ProcessingMode.AUTO_BACKGROUND
                                    TranslationMode.MANUAL -> return@launch
                                }
                                processScreen(backgroundMode)
                            }
                        }
                    }
                } catch (e: Exception) {
                    diagnosticEventStore.record(
                        level = DiagnosticEventStore.LEVEL_ERROR,
                        category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                        eventId = "detection_loop_failed",
                        title = "自动侦测循环失败",
                        message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                        server = gameServer
                    )
                    FgoLogger.error(tag, "Detection loop failed", e)
                }
                delay(DETECTION_INTERVAL)
            }
        }
    }

    private suspend fun processScreen(mode: ProcessingMode) {
        if (!mode.userInitiated && !isEffectiveFgoForeground) return
        if (isProcessing || battleFrameBusy) return
        if (!isProcessingModeEnabled(mode)) {
            FgoLogger.debug(tag, "Skipping $mode because translation mode is no longer active")
            return
        }
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
                if (!mode.userInitiated) return
                restoreHiddenOverlay = true
                FgoLogger.debug(tag, "Hiding translation overlay briefly to read source text")
                translationOverlay.hideForCapture()
                delay(CAPTURE_SETTLE_DELAY)
            }

            val capturedAt = SystemClock.elapsedRealtime()
            screenshot = takeScreenshotCompat()
            if (screenshot == null) {
                battleSubtitles.observationUnavailable()
                val failureInfo = screenshotFailureInfo(lastScreenshotErrorCode)
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                    eventId = "screenshot_failed",
                    title = "截屏失败",
                    message = "Android/模拟器没有返回截图：${failureInfo.reason}",
                    server = gameServer,
                    mode = mode.name,
                    detail = failureInfo.detail,
                    errorCode = failureInfo.code
                )
                if (mode == ProcessingMode.SEMI_AUTO_BACKGROUND) {
                    rememberSemiAutoScreenshotFailure()
                }
                runnerOverlay.showTranslationFailureFeedback(fromUserTap = mode.userInitiated)
                return
            }
            val source = screenshot
            if (battleSubtitlesEnabled && FgoRunnerService.serviceStarted.value && isJapaneseServer() &&
                battleSubtitles.inspect(source, capturedAt)) {
                restoreFgoForegroundAfterCapture("battle HUD")
                restoreHiddenOverlay = false
                return
            }
            reportGameServerPipelineUsed()
            val currentScreenWidth = source.width
            val currentScreenHeight = source.height
            val screenRegions = FgoViewportLayout.regionsForScreen(currentScreenWidth, currentScreenHeight)
            FgoLogger.debug(tag, "FGO viewport=${screenRegions.viewport}")

            if (!isJapaneseServer()) {
                processVoiceOnlyScreen(
                    source = source,
                    screenRegions = screenRegions,
                    processStartedAt = processStartedAt,
                    mode = mode
                )
                restoreHiddenOverlay = false
                return
            }

            restoreHiddenOverlay = when (mode) {
                ProcessingMode.MANUAL_TAP -> processManualScreen(
                    source = source,
                    screenRegions = screenRegions,
                    currentScreenWidth = currentScreenWidth,
                    currentScreenHeight = currentScreenHeight,
                    processStartedAt = processStartedAt,
                    processingVersion = processingVersion,
                    restoreHiddenOverlay = restoreHiddenOverlay
                )
                ProcessingMode.SEMI_AUTO_CHOICE_TAP -> processSemiAutoChoiceScreen(
                    source = source,
                    screenRegions = screenRegions,
                    currentScreenWidth = currentScreenWidth,
                    currentScreenHeight = currentScreenHeight,
                    processStartedAt = processStartedAt,
                    processingVersion = processingVersion,
                    restoreHiddenOverlay = restoreHiddenOverlay
                )
                ProcessingMode.SEMI_AUTO_BACKGROUND -> {
                    processSemiAutoDialogueScreen(
                        source = source,
                        screenRegions = screenRegions,
                        currentScreenWidth = currentScreenWidth,
                        currentScreenHeight = currentScreenHeight,
                        processStartedAt = processStartedAt,
                        processingVersion = processingVersion
                    )
                    false
                }
                ProcessingMode.AUTO_BACKGROUND -> {
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
            battleSubtitles.observationUnavailable()
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "pipeline_failed",
                title = "处理流程失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = gameServer,
                mode = mode.name
            )
            FgoLogger.error(tag, "processScreen failed", e)
            runnerOverlay.showTranslationFailureFeedback(fromUserTap = mode.userInitiated)
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

            reportGameServerPipelineUsed()

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
                ocrEngine.recognize(
                    bitmap = ocrBitmap,
                    inputScale = if (scaledForOcr != null) OcrInputScale.X2 else OcrInputScale.X1
                )
            }
            val cropOcrScale = if (scaledForOcr != null) CROP_OCR_SCALE else 1
            val cropLines = cropLocalOcrLines(
                lines = ocrResult.lines,
                coordinateScale = cropOcrScale,
                cropWidth = cropBitmap.width,
                cropHeight = cropBitmap.height
            )
            val cropSourceLines = cropSourceLines(cropLines)
            val sourceText = cropSourceText(cropSourceLines, ocrResult.fullText, ocrResult.engine)
            val ocrDuration = SystemClock.elapsedRealtime() - ocrStartedAt
            logTranslationDebugText("Crop OCR fullText", ocrResult.fullText.trim())
            logTranslationDebugText("Crop source text", sourceText)

            if (sourceText.isBlank()) {
                showCropStatus(cropBounds, "未识别到文字")
                FgoLogger.info(tag, "Crop OCR found no text in ${ocrDuration}ms")
                return
            }

            val translationStartedAt = SystemClock.elapsedRealtime()
            val translationResult = withContext(Dispatchers.IO) {
                translator.translate(
                    sourceText,
                    preserveRubyMeaning = false,
                    cropMode = true,
                    maxTokens = CROP_TRANSLATION_MAX_TOKENS,
                    useTranslationCache = false
                )
            }
            val translated = translationResult.translatedText.trim().ifBlank {
                "翻译失败"
            }
            logTranslationDebugText(
                "Crop translated text (${translationResult.backend}, ${translationResult.targetLocale})",
                translated
            )
            val translationDuration = SystemClock.elapsedRealtime() - translationStartedAt

            val cropTextColor = sampleCropOriginalTextColor(
                crop = cropBitmap,
                lines = cropLines
            )
            val rendered = withContext(Dispatchers.Default) {
                cropResultRenderer.renderOverlay(
                    width = cropBounds.width(),
                    height = cropBounds.height(),
                    text = translated,
                    sourceLines = cropSourceLines.map {
                        CropTextLine(
                            text = it.text,
                            boundingBox = Rect(it.boundingBox)
                        )
                    },
                    textColor = cropTextColor ?: FGO_RENDER_WHITE,
                    targetLocale = translationResult.targetLocale
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
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "crop_translation_failed",
                title = "区域翻译失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = gameServer,
                mode = "CROP"
            )
            FgoLogger.error(tag, "Crop translation failed", e)
            showCropStatus(requestedBounds, "翻译失败")
        } finally {
            scaledForOcr?.recycle()
            cropped?.recycle()
            screenshot?.recycle()
            isProcessing = false
        }
    }

    private fun reportGameServerPipelineUsed() {
        val normalizedServer = SettingsRepository.normalizeGameServer(gameServer)
        serviceScope.launch(Dispatchers.IO) {
            appAnalytics.reportGameServerUsed(normalizedServer)
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

    private fun cropLocalOcrLines(
        lines: List<OcrTextLine>,
        coordinateScale: Int,
        cropWidth: Int,
        cropHeight: Int
    ): List<OcrTextLine> {
        val scale = coordinateScale.coerceAtLeast(1)
        return lines.mapNotNull { line ->
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
            if (!bounds.intersect(0, 0, cropWidth, cropHeight)) return@mapNotNull null
            if (bounds.width() <= 0 || bounds.height() <= 0) return@mapNotNull null
            OcrTextLine(
                text = line.text,
                boundingBox = bounds,
                confidence = line.confidence
            )
        }
    }

    private fun cropSourceLines(lines: List<OcrTextLine>): List<OcrTextLine> {
        return lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    private fun cropSourceText(
        lines: List<OcrTextLine>,
        fullText: String,
        ocrEngine: OcrEngineId
    ): String {
        val sourceText = lines.joinToString("\n") { it.text.trim() }.trim()
            .ifBlank { fullText.trim() }
        return correctMlKitOcrSourceText(sourceText, "CROP", ocrEngine)
    }

    private suspend fun processVoiceOnlyScreen(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        processStartedAt: Long,
        mode: ProcessingMode
    ) {
        val sceneSource = when (mode) {
            ProcessingMode.MANUAL_TAP,
            ProcessingMode.SEMI_AUTO_CHOICE_TAP -> scanVoiceOnlyDialogueScene(
                source = source,
                screenRegions = screenRegions,
                includeChoices = aiVoiceChoiceTextEnabled,
                mode = mode
            )
            ProcessingMode.SEMI_AUTO_BACKGROUND,
            ProcessingMode.AUTO_BACKGROUND -> scanVoiceOnlyCompletedDialogueScene(source, screenRegions, mode)
        }

        if (sceneSource == null) {
            if (mode.userInitiated) {
                runnerOverlay.showTranslationFailureFeedback()
            }
            translationOverlay.hide()
            return
        }

        if (isAlreadyRenderedSource(mode, sceneSource)) {
            FgoLogger.debug(tag, "Voice-only source unchanged; waiting for new OCR text")
            translationOverlay.hide()
            return
        }

        requestVoiceOnlyScene(sceneSource)
        rememberRenderedSourceText(mode, sceneSource.fingerprint, sceneSource.stabilityKey)
        if (mode == ProcessingMode.SEMI_AUTO_BACKGROUND) {
            resetSemiAutoBackoff()
        }
        translationOverlay.hide()
        FgoLogger.info(
            tag,
            "Voice-only pipeline ready ($mode): ocr=${SystemClock.elapsedRealtime() - processStartedAt}ms"
        )
    }

    private suspend fun scanVoiceOnlyDialogueScene(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        includeChoices: Boolean = false,
        mode: ProcessingMode = ProcessingMode.MANUAL_TAP
    ): SceneSource? {
        val dialogueRegions = recognizeDialogueRegions(
            source = source,
            screenRegions = screenRegions,
            allowRedTextFallback = true
        )
        val choiceRegions = if (includeChoices) {
            recognizeChoiceRegions(
                source = source,
                screenRegions = screenRegions,
                mode = mode
            ).regions
        } else {
            emptyList()
        }
        return sceneSourceFor(mergeManualSceneRegions(choiceRegions, dialogueRegions))
            ?.takeIf { scene ->
                scene.hasDialogue || scene.input.choices.any { it.isNotBlank() }
            }
    }

    private suspend fun scanVoiceOnlyCompletedDialogueScene(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        mode: ProcessingMode
    ): SceneSource? {
        val dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
            source,
            screenRegions.dialogueComplete
        )
        if (!dialogueComplete) {
            if (mode == ProcessingMode.SEMI_AUTO_BACKGROUND) {
                rememberSemiAutoBlankOcr()
            }
            FgoLogger.debug(tag, "Voice-only waiting for completed dialogue marker")
            return null
        }

        val sceneSource = scanVoiceOnlyDialogueScene(source, screenRegions)
        if (sceneSource == null && mode == ProcessingMode.SEMI_AUTO_BACKGROUND) {
            rememberSemiAutoBlankOcr()
        }
        return sceneSource
    }

    private fun requestVoiceOnlyScene(sceneSource: SceneSource) {
        val speakerName = voiceSpeakerForDialogue(sceneSource.input.name)
        val dialogue = (sceneSource.voiceDialogue ?: sceneSource.input.dialogue)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val choiceText = if (aiVoiceChoiceTextEnabled) {
            sourceChoiceVoiceText(sceneSource)
        } else {
            null
        }
        if ((speakerName == null || dialogue == null) && choiceText == null) return

        serviceScope.launch {
            if (speakerName != null && dialogue != null) {
                val voiceHint = requestVoiceOnlyVoiceHint(
                    speakerName = speakerName,
                    dialogue = dialogue
                )
                aiVoiceService.speakDialogue(
                    speakerName = speakerName,
                    sourceDialogue = sceneSource.input.dialogue,
                    translatedDialogue = dialogue,
                    voiceHint = voiceHint
                )
            }
            if (choiceText != null) {
                aiVoiceService.speakDialogue(
                    speakerName = masterVoiceProfileId(aiVoiceMasterVoice),
                    sourceDialogue = choiceText,
                    translatedDialogue = choiceText,
                    voiceHint = null
                )
            }
        }
    }

    private suspend fun requestVoiceOnlyVoiceHint(
        speakerName: String,
        dialogue: String
    ): VoiceLineHint? {
        if (isJapaneseServer() || !aiVoiceEnabled || !aiVoiceApiHintsEnabled) return null
        if (!TextNormalizer.hasTranslatableContent(dialogue)) return null

        var completed = false
        return try {
            val hint = withTimeoutOrNull(VOICE_HINT_REQUEST_TIMEOUT_MS) {
                val result = withContext(Dispatchers.IO) {
                    translator.requestVoiceHint(speakerName, dialogue)
                }
                completed = true
                result
            }
            if (!completed) {
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_WARNING,
                    category = DiagnosticEventStore.CATEGORY_VOICE_HINT_API,
                    eventId = "voice_hint_api_timeout",
                    title = "语气增强请求超时",
                    message = "已改用本机语气规则",
                    server = SettingsRepository.normalizeGameServer(gameServer),
                    speaker = speakerName,
                    textPreview = dialogue.diagnosticPreviewText()
                )
                FgoLogger.warn(tag, "Voice-only hint API timed out: speaker=$speakerName")
            }
            hint
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_VOICE_HINT_API,
                eventId = "voice_hint_api_failed",
                title = "语气增强请求失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                server = SettingsRepository.normalizeGameServer(gameServer),
                speaker = speakerName,
                textPreview = dialogue.diagnosticPreviewText()
            )
            FgoLogger.warn(tag, "Voice-only hint API failed: speaker=$speakerName", e)
            null
        }
    }

    private fun String.diagnosticPreviewText(): String {
        return trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
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
                FgoLogger.debug(tag, "No translatable completed dialogue detected in FGO regions")
                runnerOverlay.showTranslationFailureFeedback()
                translationOverlay.hide()
                return false
            }
            FgoLogger.debug(tag, "Manual OCR found no dialogue and no choices")
            return restoreHiddenOverlay
        }

        FgoLogger.debug(tag, "Manual path uses fixed dialogue/choice regions without story guard")
        val sceneSource = sceneSourceFor(scan.regions)
        if (sceneSource == null) {
            runnerOverlay.showTranslationFailureFeedback()
            translationOverlay.hide()
            return false
        }

        if (restoreHiddenOverlay &&
            isAlreadyRenderedSource(ProcessingMode.MANUAL_TAP, sceneSource)
        ) {
            FgoLogger.debug(tag, "Manual source unchanged; restoring previous overlay without translation")
            return true
        }

        translateAndRenderScene(
            mode = ProcessingMode.MANUAL_TAP,
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

    private suspend fun processSemiAutoChoiceScreen(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        currentScreenWidth: Int,
        currentScreenHeight: Int,
        processStartedAt: Long,
        processingVersion: Long,
        restoreHiddenOverlay: Boolean
    ): Boolean {
        var choiceRecognition = recognizeChoiceRegions(
            source = source,
            screenRegions = screenRegions,
            mode = ProcessingMode.SEMI_AUTO_CHOICE_TAP
        )
        var choiceRegions = choiceRecognition.regions
        if (choiceRegions.isEmpty()) {
            FgoLogger.debug(tag, "Semi-auto tap OCR empty; retrying after settle delay")
            delay(SEMI_AUTO_CHOICE_RETRY_DELAY_MS)
            val retryScreenshot = takeScreenshotCompat()
            if (retryScreenshot != null) {
                try {
                    val retryBounds = detectChoiceBounds(retryScreenshot, screenRegions)
                    if (retryBounds.isNotEmpty()) {
                        choiceRecognition = recognizeChoiceRegions(
                            source = retryScreenshot,
                            choiceBounds = retryBounds,
                            mode = ProcessingMode.SEMI_AUTO_CHOICE_TAP
                        )
                    } else {
                        choiceRecognition = recognizeChoiceRegionsByFixedSlots(
                            source = retryScreenshot,
                            screenRegions = screenRegions,
                            preferredCount = null
                        )
                    }
                    choiceRegions = choiceRecognition.regions
                    if (choiceRegions.isEmpty()) {
                        choiceRecognition = recognizeChoiceRegions(
                            source = retryScreenshot,
                            screenRegions = screenRegions,
                            mode = ProcessingMode.SEMI_AUTO_CHOICE_TAP
                        )
                        choiceRegions = choiceRecognition.regions
                    }
                } finally {
                    retryScreenshot.recycle()
                }
            }
        }
        if (choiceRegions.isEmpty()) {
            if (choiceRecognition.bounds.isEmpty()) {
                FgoLogger.debug(tag, "Semi-auto tap found no choice panels")
            } else {
                FgoLogger.debug(tag, "Semi-auto tap found choice panels but OCR returned no text")
            }
            return restoreHiddenOverlay
        }

        FgoLogger.debug(tag, "Semi-auto tap uses choice-only OCR path")
        val sceneSource = sceneSourceFor(choiceRegions)
        if (sceneSource == null) {
            runnerOverlay.showTranslationFailureFeedback()
            translationOverlay.hide()
            return false
        }

        if (restoreHiddenOverlay &&
            isAlreadyRenderedSource(ProcessingMode.SEMI_AUTO_CHOICE_TAP, sceneSource)
        ) {
            FgoLogger.debug(tag, "Semi-auto choice source unchanged; restoring previous overlay")
            return true
        }

        val rendered = translateAndRenderScene(
            mode = ProcessingMode.SEMI_AUTO_CHOICE_TAP,
            source = source,
            currentScreenWidth = currentScreenWidth,
            currentScreenHeight = currentScreenHeight,
            processStartedAt = processStartedAt,
            processingVersion = processingVersion,
            sceneSource = sceneSource,
            recognitionDuration = SystemClock.elapsedRealtime() - processStartedAt
        )
        return if (rendered) false else restoreHiddenOverlay
    }

    private suspend fun scanManualScene(
        source: Bitmap,
        screenRegions: FgoScreenRegions
    ): ManualScanResult {
        val choiceRecognition = recognizeChoiceRegions(source, screenRegions, ProcessingMode.MANUAL_TAP)
        val choiceRegions = choiceRecognition.regions
        if (choiceRegions.isNotEmpty()) {
            FgoLogger.debug(tag, "Manual choice text detected; reading dialogue for context")
            val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
            return ManualScanResult(
                regions = mergeManualSceneRegions(choiceRegions, dialogueRegions),
                dialogueComplete = false
            )
        }

        val dialogueRegions = recognizeDialogueRegions(source, screenRegions)
        val dialogueScene = sceneSourceFor(dialogueRegions)
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

    private suspend fun processSemiAutoDialogueScreen(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        currentScreenWidth: Int,
        currentScreenHeight: Int,
        processStartedAt: Long,
        processingVersion: Long
    ) {
        when (val scan = scanSemiAutoDialogueScene(source, screenRegions, currentScreenWidth, currentScreenHeight)) {
            is AutoScanResult.Ready -> {
                val sceneSource = sceneSourceFor(scan.regions)
                if (sceneSource == null) {
                    rememberSemiAutoBlankOcr()
                    translationOverlay.hide()
                    return
                }
                resetSemiAutoBackoff()
                if (isAlreadyRenderedSource(ProcessingMode.SEMI_AUTO_BACKGROUND, sceneSource)) {
                    FgoLogger.debug(tag, "Semi-auto dialogue source unchanged; waiting for new OCR text")
                    return
                }
                translateAndRenderScene(
                    mode = ProcessingMode.SEMI_AUTO_BACKGROUND,
                    source = source,
                    currentScreenWidth = currentScreenWidth,
                    currentScreenHeight = currentScreenHeight,
                    processStartedAt = processStartedAt,
                    processingVersion = processingVersion,
                    sceneSource = sceneSource,
                    recognitionDuration = SystemClock.elapsedRealtime() - processStartedAt
                )
            }
            AutoScanResult.EmptyCompletedDialogue -> {
                rememberSemiAutoBlankOcr()
                FgoLogger.debug(tag, "Semi-auto found completed dialogue marker with no translatable text")
                translationOverlay.hide()
            }
            AutoScanResult.Waiting -> Unit
        }
    }

    private suspend fun scanSemiAutoDialogueScene(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        currentScreenWidth: Int,
        currentScreenHeight: Int
    ): AutoScanResult {
        val dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
            source,
            screenRegions.dialogueComplete
        )
        if (!dialogueComplete) {
            FgoLogger.debug(tag, "Semi-auto waiting for completed dialogue marker")
            rememberSemiAutoBlankOcr()
            return AutoScanResult.Waiting
        }

        val dialogueRegions = recognizeDialogueRegions(
            source,
            screenRegions,
            allowRedTextFallback = true
        )
        val dialogueScene = sceneSourceFor(dialogueRegions)

        if (dialogueScene?.hasDialogue == true) {
            resetSemiAutoBackoff()
            logAutoStoryDetection(
                "Semi-auto completed dialogue",
                dialogueRegions,
                currentScreenWidth,
                currentScreenHeight
            )
            return AutoScanResult.Ready(regions = dialogueRegions)
        }
        return AutoScanResult.EmptyCompletedDialogue
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
                if (isAlreadyRenderedSource(ProcessingMode.AUTO_BACKGROUND, sceneSource)) {
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
                translateAndRenderScene(
                    mode = ProcessingMode.AUTO_BACKGROUND,
                    source = source,
                    currentScreenWidth = currentScreenWidth,
                    currentScreenHeight = currentScreenHeight,
                    processStartedAt = processStartedAt,
                    processingVersion = processingVersion,
                    sceneSource = sceneSource,
                    recognitionDuration = SystemClock.elapsedRealtime() - processStartedAt
                )
            }
            AutoScanResult.EmptyCompletedDialogue -> {
                FgoLogger.debug(tag, "No translatable completed dialogue detected in FGO regions")
                runnerOverlay.showTranslationFailureFeedback(fromUserTap = false)
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

        if (choiceBounds.isNotEmpty()) {
            val choiceRecognition = recognizeChoiceRegions(source, choiceBounds, ProcessingMode.AUTO_BACKGROUND)
            val choiceRegions = choiceRecognition.regions
            if (choiceRegions.isNotEmpty()) {
                FgoLogger.debug(tag, "Auto choice text detected")
                val dialogueRegions = if (backgroundDetector.isDialogueCompleteMarkerVisible(
                        source,
                        screenRegions.dialogueComplete
                    )
                ) {
                    recognizeDialogueRegions(
                        source,
                        screenRegions,
                        allowRedTextFallback = true
                    )
                } else {
                    emptyList()
                }
                val sceneRegions = mergeManualSceneRegions(choiceRegions, dialogueRegions)
                logAutoStoryDetection("Choice", sceneRegions, currentScreenWidth, currentScreenHeight)
                return AutoScanResult.Ready(regions = sceneRegions)
            }

            FgoLogger.debug(tag, "Choice panels detected by pixels but OCR returned no text")
        }

        val dialogueComplete = backgroundDetector.isDialogueCompleteMarkerVisible(
            source,
            screenRegions.dialogueComplete
        )
        if (!dialogueComplete) {
            FgoLogger.debug(tag, "Auto waiting for completed dialogue marker")
            return AutoScanResult.Waiting
        }

        val dialogueRegions = recognizeDialogueRegions(
            source,
            screenRegions,
            allowRedTextFallback = true
        )
        val dialogueScene = sceneSourceFor(dialogueRegions)
        if (dialogueScene?.hasDialogue == true) {
            val label = if (choiceBounds.isEmpty()) {
                "Completed dialogue"
            } else {
                "Completed dialogue after empty choice"
            }
            logAutoStoryDetection(label, dialogueRegions, currentScreenWidth, currentScreenHeight)
            return AutoScanResult.Ready(regions = dialogueRegions)
        }

        return AutoScanResult.EmptyCompletedDialogue
    }

    private fun logAutoStoryDetection(
        label: String,
        regions: List<ClassifiedRegion>,
        currentScreenWidth: Int,
        currentScreenHeight: Int
    ) {
        val detectedLines = regions.flatMap { it.lines }
        val storyResult = storyDetector.detect(
            lines = detectedLines,
            screenWidth = currentScreenWidth,
            screenHeight = currentScreenHeight,
            viewport = FgoViewportLayout.viewportForScreen(currentScreenWidth, currentScreenHeight)
        )
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
            FgoLogger.debug(tag, "Auto tap handoff saw name-only or empty dialogue OCR; waiting for dialogue text")
            return true
        }

        val storyResult = storyDetector.detect(
            lines = regions.flatMap { it.lines },
            screenWidth = currentScreenWidth,
            screenHeight = currentScreenHeight,
            viewport = FgoViewportLayout.viewportForScreen(currentScreenWidth, currentScreenHeight)
        )
        if (!storyResult.isStoryScene) {
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
        logSceneTranslationDebug(mode, sceneSource, sceneTranslation, instructions)
        val renderedHasChoices = instructions.any { it.region.region == TextRegion.CHOICE_BUTTON }

        missingRequiredRenderReason(sceneSource, instructions)?.let { reason ->
            FgoLogger.warn(tag, "Translation result incomplete; not marking source as rendered: $reason")
            rememberFailedRenderAttempt(mode, sourceFingerprint)
            runnerOverlay.showTranslationFailureFeedback(fromUserTap = mode.userInitiated)
            translationOverlay.hide()
            return false
        }
        if (instructions.isEmpty()) {
            if (!sceneHasRequiredTranslation(sceneSource)) {
                FgoLogger.debug(tag, "Scene has no translatable text; skipping overlay render")
                rememberRenderedSourceText(mode, sourceFingerprint, sceneSource.stabilityKey)
                clearFailedRenderAttempt(sourceFingerprint)
                translationOverlay.hide()
                return true
            }
            addHistoryEntry(source, sceneSource, sceneTranslation, instructions)
            runnerOverlay.showTranslationFailureFeedback(fromUserTap = mode.userInitiated)
            translationOverlay.hide()
            return false
        }
        val resultBuildDuration = translationDuration + layoutDuration
        val forceVisualFreshness = mode == ProcessingMode.AUTO_BACKGROUND && isAutoTapHandoffActive()
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
        if (processingVersion != stopVersion) {
            FgoLogger.debug(tag, "Translation was stopped during render; discarding rendered bitmap")
            rendered.recycle()
            return false
        }
        if (!isProcessingModeEnabled(mode)) {
            FgoLogger.debug(tag, "Translation mode changed during render; discarding rendered bitmap")
            rendered.recycle()
            return false
        }
        rememberRenderedSourceText(mode, sourceFingerprint, sceneSource.stabilityKey)
        clearFailedRenderAttempt(sourceFingerprint)
        if (mode == ProcessingMode.SEMI_AUTO_BACKGROUND) {
            resetSemiAutoBackoff()
        }
        renderedChoiceBounds = if (mode == ProcessingMode.AUTO_BACKGROUND ||
            (mode == ProcessingMode.SEMI_AUTO_CHOICE_TAP && renderedHasChoices)
        ) {
            overlayRenderer.renderedChoiceBounds(
                instructions = instructions,
                screenWidth = currentScreenWidth,
                screenHeight = currentScreenHeight
            )
        } else {
            emptyList()
        }
        addHistoryEntry(source, sceneSource, sceneTranslation, instructions)
        val overlayStartedAt = SystemClock.elapsedRealtime()
        restoreFgoForegroundAfterCapture(mode.name)
        translationOverlay.updateImage(rendered)
        maybeSpeakRenderedDialogue(sceneSource, sceneTranslation, instructions)
        val overlayDuration = SystemClock.elapsedRealtime() - overlayStartedAt
        FgoLogger.info(
            tag,
            "Pipeline ready ($mode): ocr=${recognitionDuration}ms, translate=${translationDuration}ms, " +
                    "layout=${layoutDuration}ms, render=${renderDuration}ms, overlay=${overlayDuration}ms, " +
                    "total=${SystemClock.elapsedRealtime() - processStartedAt}ms"
        )
        return true
    }

    private fun maybeSpeakRenderedDialogue(
        sceneSource: SceneSource,
        sceneTranslation: SceneTranslateResult,
        instructions: List<RenderInstruction>
    ) {
        val renderedDialogue = sceneTranslation.dialogue?.trustedForContext == true &&
            instructions.any {
                it.region.region == TextRegion.DIALOGUE_BOX && it.translatedText.isNotBlank()
            }
        val speakerName = if (renderedDialogue) {
            voiceSpeakerForDialogue(sceneSource.input.name)
        } else {
            null
        }
        val translatedDialogue = if (speakerName != null) {
            sceneTranslation.dialogue?.translatedText?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val choiceText = if (aiVoiceChoiceTextEnabled) {
            renderedChoiceVoiceText(instructions)
        } else {
            null
        }
        if ((speakerName == null || translatedDialogue == null) && choiceText == null) return

        serviceScope.launch {
            if (speakerName != null && translatedDialogue != null) {
                aiVoiceService.speakDialogue(
                    speakerName = speakerName,
                    sourceDialogue = sceneSource.input.dialogue,
                    translatedDialogue = translatedDialogue,
                    voiceHint = sceneTranslation.voiceHint
                )
            }
            if (choiceText != null) {
                aiVoiceService.speakDialogue(
                    speakerName = masterVoiceProfileId(aiVoiceMasterVoice),
                    sourceDialogue = sourceChoiceVoiceText(sceneSource),
                    translatedDialogue = choiceText,
                    voiceHint = null
                )
            }
        }
    }

    private fun voiceSpeakerForDialogue(rawSpeakerName: String?): String? {
        val speakerName = rawSpeakerName?.trim()?.takeIf { it.isNotBlank() }
        return when {
            speakerName != null && aiVoiceNamedDialogueEnabled -> speakerName
            speakerName == null && aiVoiceNoSpeakerDialogueEnabled -> NO_SPEAKER_PROFILE_ID
            else -> null
        }
    }

    private fun renderedChoiceVoiceText(instructions: List<RenderInstruction>): String? {
        return instructions
            .filter { it.region.region == TextRegion.CHOICE_BUTTON }
            .map { it.translatedText.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    private fun sourceChoiceVoiceText(sceneSource: SceneSource): String? {
        return sceneSource.input.choices
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    private fun masterVoiceProfileId(masterVoice: String): String {
        return when (SettingsRepository.normalizeAiVoiceMasterVoice(masterVoice)) {
            SettingsRepository.AI_VOICE_MASTER_FEMALE -> MASTER_PROFILE_FEMALE
            else -> MASTER_PROFILE_MALE
        }
    }

    private fun isProcessingModeEnabled(mode: ProcessingMode): Boolean {
        return when (mode) {
            ProcessingMode.MANUAL_TAP -> TranslationTrigger.canUserTapTranslate()
            ProcessingMode.SEMI_AUTO_CHOICE_TAP -> TranslationTrigger.isSemiAutoEnabled()
            ProcessingMode.SEMI_AUTO_BACKGROUND -> TranslationTrigger.isSemiAutoEnabled()
            ProcessingMode.AUTO_BACKGROUND -> TranslationTrigger.isAutoTranslateEnabled()
        }
    }

    private fun lastRenderedSourceTextFor(mode: ProcessingMode): String {
        return when (mode) {
            ProcessingMode.MANUAL_TAP -> lastManualRenderedSourceText
            ProcessingMode.SEMI_AUTO_CHOICE_TAP -> lastSemiAutoChoiceRenderedSourceText
            ProcessingMode.SEMI_AUTO_BACKGROUND -> lastSemiAutoRenderedSourceText
            ProcessingMode.AUTO_BACKGROUND -> lastAutoRenderedSourceText
        }
    }

    private fun lastRenderedStabilityKeyFor(mode: ProcessingMode): String {
        return when (mode) {
            ProcessingMode.MANUAL_TAP -> lastManualRenderedStabilityKey
            ProcessingMode.SEMI_AUTO_CHOICE_TAP -> lastSemiAutoChoiceRenderedStabilityKey
            ProcessingMode.SEMI_AUTO_BACKGROUND -> lastSemiAutoRenderedStabilityKey
            ProcessingMode.AUTO_BACKGROUND -> lastAutoRenderedStabilityKey
        }
    }

    private fun rememberRenderedSourceText(mode: ProcessingMode, fingerprint: String, stabilityKey: String) {
        when (mode) {
            ProcessingMode.MANUAL_TAP -> {
                lastManualRenderedSourceText = fingerprint
                lastManualRenderedStabilityKey = stabilityKey
            }
            ProcessingMode.SEMI_AUTO_CHOICE_TAP -> {
                lastSemiAutoChoiceRenderedSourceText = fingerprint
                lastSemiAutoChoiceRenderedStabilityKey = stabilityKey
            }
            ProcessingMode.SEMI_AUTO_BACKGROUND -> {
                lastSemiAutoRenderedSourceText = fingerprint
                lastSemiAutoRenderedStabilityKey = stabilityKey
            }
            ProcessingMode.AUTO_BACKGROUND -> {
                lastAutoRenderedSourceText = fingerprint
                lastAutoRenderedStabilityKey = stabilityKey
            }
        }
    }

    private fun isAlreadyRenderedSource(mode: ProcessingMode, sceneSource: SceneSource): Boolean {
        if (sceneSource.fingerprint == lastRenderedSourceTextFor(mode)) return true
        val renderedKey = lastRenderedStabilityKeyFor(mode)
        if (renderedKey.isBlank() || sceneSource.stabilityKey.isBlank()) return false
        return sceneSource.stabilityKey == renderedKey
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
        if (mode != ProcessingMode.AUTO_BACKGROUND) return
        failedAutoRenderFingerprint = fingerprint
        failedAutoRenderRetryAt = SystemClock.elapsedRealtime() + AUTO_FAILED_TRANSLATION_RETRY_COOLDOWN
    }

    private fun clearFailedRenderAttempt(fingerprint: String) {
        if (failedAutoRenderFingerprint != fingerprint) return
        failedAutoRenderFingerprint = ""
        failedAutoRenderRetryAt = 0L
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
        val voiceDialogue = dialogueRegion
            ?.region
            ?.let(::voiceTextForDialogueRegion)
            ?.takeIf { it.isNotBlank() }
        val fingerprint = translatableRegions.joinToString("\n\n") { regionText ->
            "${regionText.region.region}:${regionText.region.boundingBox.flattenToString()}\n${regionText.text}"
        }.trim()
        val dialogueText = dialogueRegion?.text.orEmpty()
        if (voiceDialogue != null && voiceDialogue != dialogueText) {
            FgoLogger.debug(tag, "Dialogue voice source cleaned: ${debugQuote(dialogueText)} -> ${debugQuote(voiceDialogue)}")
        }
        val stabilityKey = sceneStabilityKey(
            name = nameRegion?.text,
            dialogue = dialogueRegion?.text,
            choices = choiceRegions.map { it.text }
        )
        return SceneSource(
            regions = translatableRegions,
            input = SceneTranslateInput(
                name = nameRegion?.text,
                dialogue = dialogueRegion?.text,
                choices = choiceRegions.map { it.text }
            ),
            voiceDialogue = voiceDialogue,
            fingerprint = fingerprint,
            stabilityKey = stabilityKey,
            hasDialogue = dialogueText.isNotBlank()
        )
    }

    private fun sceneStabilityKey(name: String?, dialogue: String?, choices: List<String>): String {
        val dialogueKey = normalizeOcrStabilityText(dialogue.orEmpty())
        val choiceKey = choices
            .map(::normalizeOcrStabilityText)
            .filter { it.isNotBlank() }
            .joinToString("|")
        if (dialogueKey.isNotBlank() || choiceKey.isNotBlank()) {
            return buildString {
                if (dialogueKey.isNotBlank()) {
                    append("D:")
                    append(dialogueKey)
                }
                if (choiceKey.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("C:")
                    append(choiceKey)
                }
            }
        }

        val nameKey = normalizeOcrStabilityText(name.orEmpty())
        return if (nameKey.isBlank()) "" else "N:$nameKey"
    }

    private fun normalizeOcrStabilityText(text: String): String {
        val normalized = TextNormalizer.normalizeForTranslation(text)
        val lexicalKey = buildString(normalized.length) {
            normalized.forEach { char ->
                if (char.isLetterOrDigit() || char.isJapaneseTextChar()) {
                    append(char.lowercaseChar())
                }
            }
        }
        val punctuationKey = FgoDialogueSymbols.sourcePunctuationStabilitySignature(normalized)
        return if (punctuationKey.isBlank()) lexicalKey else "$lexicalKey⟦$punctuationKey⟧"
    }

    private suspend fun translateSceneSource(sceneSource: SceneSource): SceneTranslateResult {
        val previousDialogueContexts = if (isJapaneseServer()) {
            SessionTranslationHistory.lastSceneDialogueContexts(
                excludeDialogueSourceKey = sceneSource.historyDialogueSourceKey()
            )
        } else {
            emptyList()
        }
        val input = sceneSource.input.copy(
            requestVoiceHint = shouldRequestVoiceHint(sceneSource),
            previousDialogueContexts = previousDialogueContexts
        )
        return withContext(Dispatchers.IO) {
            translator.translateScene(input)
        }
    }

    private fun shouldRequestVoiceHint(sceneSource: SceneSource): Boolean {
        if (!aiVoiceEnabled || !aiVoiceApiHintsEnabled) return false
        val dialogue = sceneSource.input.dialogue
            ?.trim()
            ?.takeIf { TextNormalizer.hasTranslatableContent(it) }
            ?: return false
        return voiceSpeakerForDialogue(sceneSource.input.name) != null && dialogue.isNotBlank()
    }

    private fun buildRenderInstructions(
        source: Bitmap,
        sceneSource: SceneSource,
        sceneTranslation: SceneTranslateResult
    ): List<RenderInstruction> {
        val choiceRegions = sceneSource.regions.filter { it.region.region == TextRegion.CHOICE_BUTTON }
        val translatedChoicesByRegion = choiceRegions
            .zip(sceneTranslation.choices)
            .associate { (regionAndText, result) -> regionAndText.region to result }

        return sceneSource.regions.mapNotNull { regionAndText ->
            val translatedResult = when (regionAndText.region.region) {
                TextRegion.NAME_LABEL -> renderableNameTranslation(
                    sourceText = regionAndText.text,
                    result = sceneTranslation.name
                )?.let { text ->
                    sceneTranslation.name?.copy(translatedText = text)
                        ?: TranslateResult(
                            translatedText = text,
                            backend = "source-name",
                            cached = true,
                            targetLocale = nameFallbackTargetLocale(sceneTranslation)
                        )
                }
                TextRegion.DIALOGUE_BOX -> sceneTranslation.dialogue
                TextRegion.CHOICE_BUTTON -> translatedChoicesByRegion[regionAndText.region]
            }
                ?: return@mapNotNull null
            val translatedText = translatedResult.translatedText
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val showOriginalForRegion = showOriginalGameText &&
                regionAndText.region.region in setOf(TextRegion.DIALOGUE_BOX, TextRegion.CHOICE_BUTTON)
            RenderInstruction(
                region = regionAndText.region,
                translatedText = translatedText,
                sourceText = regionAndText.text,
                textColor = renderTextColorForRegion(source, regionAndText.region),
                wideTextSpacing = shouldUseWideRenderSpacing(
                    sourceText = regionAndText.text,
                    region = regionAndText.region.region
                ),
                targetLocale = translatedResult.targetLocale,
                showOriginalText = showOriginalForRegion
            )
        }
    }

    private fun logSceneTranslationDebug(
        mode: ProcessingMode,
        sceneSource: SceneSource,
        sceneTranslation: SceneTranslateResult,
        instructions: List<RenderInstruction>
    ) {
        logTranslationDebugText(
            "Scene OCR source [$mode]",
            buildList {
                sceneSource.input.name?.takeIf { it.isNotBlank() }?.let { add("name=${debugQuote(it)}") }
                sceneSource.input.dialogue?.takeIf { it.isNotBlank() }?.let { add("dialogue=${debugQuote(it)}") }
                sceneSource.input.choices.forEachIndexed { index, choice ->
                    if (choice.isNotBlank()) add("choice[$index]=${debugQuote(choice)}")
                }
            }.joinToString("; ")
        )
        logTranslationDebugText(
            "Scene translated [$mode]",
            buildList {
                sceneTranslation.name?.let { add("name=${debugResult(it)}") }
                sceneTranslation.dialogue?.let { add("dialogue=${debugResult(it)}") }
                sceneTranslation.choices.forEachIndexed { index, result ->
                    if (result.translatedText.isNotBlank()) add("choice[$index]=${debugResult(result)}")
                }
            }.joinToString("; ")
        )
        logTranslationDebugText(
            "Scene render text [$mode]",
            instructions.joinToString("; ") { instruction ->
                "${instruction.region.region}=${debugQuote(instruction.translatedText)} (${instruction.targetLocale})"
            }
        )
    }

    private fun debugResult(result: TranslateResult): String {
        return "${debugQuote(result.translatedText)} " +
            "(${result.backend}, cached=${result.cached}, context=${result.trustedForContext}, ${result.targetLocale})"
    }

    private fun debugQuote(text: String): String {
        return "\"${text.replace("\r", "\\r").replace("\n", "\\n")}\""
    }

    private fun logTranslationDebugText(label: String, text: String) {
        val cleaned = text.ifBlank { "<blank>" }
        val chunks = cleaned.chunked(LOG_TEXT_CHUNK_SIZE)
        if (chunks.size <= 1) {
            FgoLogger.info(tag, "$label: $cleaned")
            return
        }
        chunks.forEachIndexed { index, chunk ->
            FgoLogger.info(tag, "$label [${index + 1}/${chunks.size}]: $chunk")
        }
    }

    private fun missingRequiredRenderReason(
        sceneSource: SceneSource,
        instructions: List<RenderInstruction>
    ): String? {
        val sourceHasDialogue = sceneSource.regions.any {
            it.region.region == TextRegion.DIALOGUE_BOX &&
                TextNormalizer.hasTranslatableContent(it.text)
        }
        val renderedHasDialogue = instructions.any {
            it.region.region == TextRegion.DIALOGUE_BOX && it.translatedText.isNotBlank()
        }
        if (sourceHasDialogue && !renderedHasDialogue) {
            return "dialogue translation missing"
        }

        val sourceChoiceCount = sceneSource.regions.count {
            it.region.region == TextRegion.CHOICE_BUTTON &&
                TextNormalizer.hasTranslatableContent(it.text)
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

    private fun sceneHasRequiredTranslation(sceneSource: SceneSource): Boolean {
        return sceneSource.regions.any {
            when (it.region.region) {
                TextRegion.DIALOGUE_BOX,
                TextRegion.CHOICE_BUTTON -> TextNormalizer.hasTranslatableContent(it.text)
                TextRegion.NAME_LABEL -> false
            }
        }
    }

    private fun renderableNameTranslation(sourceText: String, result: TranslateResult?): String? {
        return result?.translatedText?.trim()?.takeIf { it.isNotBlank() }
            ?: sourceText.trim().takeIf { it.isNotBlank() }
    }

    private fun nameFallbackTargetLocale(sceneTranslation: SceneTranslateResult): String {
        return sceneTranslation.name?.targetLocale
            ?: sceneTranslation.dialogue?.targetLocale
            ?: sceneTranslation.choices.firstOrNull { it.targetLocale.isNotBlank() }?.targetLocale
            ?: SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    }

    private fun Char.isJapaneseTextChar(): Boolean {
        return this in '\u3040'..'\u30ff' ||
                this in '\u3400'..'\u4dbf' ||
                this in '\u4e00'..'\u9fff' ||
                this == '\u3005'
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
            TextRegion.CHOICE_BUTTON -> correctMlKitOcrSourceText(
                sourceText = rawText,
                label = region.region.name,
                ocrEngine = region.ocrEngine
            )
        }
    }

    private fun voiceTextForDialogueRegion(region: ClassifiedRegion): String {
        if (region.region != TextRegion.DIALOGUE_BOX) return ""
        val rawText = formatDialogueForVoice(region.lines, RubyDetectionMode.STRICT)
        return correctMlKitOcrSourceText(
            sourceText = rawText,
            label = "${region.region.name}_VOICE",
            ocrEngine = region.ocrEngine
        )
    }

    private fun correctMlKitOcrSourceText(
        sourceText: String,
        label: String,
        ocrEngine: OcrEngineId
    ): String {
        if (ocrEngine != OcrEngineId.ML_KIT) return sourceText

        val corrected = OcrTextCorrector.correct(sourceText)
        if (corrected != sourceText) {
            FgoLogger.debug(tag, "ML Kit OCR correction ($label): $sourceText -> $corrected")
        }
        return corrected
    }

    private fun formatDialogueForTranslation(
        lines: List<OcrTextLine>,
        rubyDetectionMode: RubyDetectionMode
    ): String {
        return dialogueSourceTextFor(lines, rubyDetectionMode).translationText
    }

    private fun formatDialogueForVoice(
        lines: List<OcrTextLine>,
        rubyDetectionMode: RubyDetectionMode
    ): String {
        return dialogueSourceTextFor(lines, rubyDetectionMode).voiceText
    }

    private fun dialogueSourceTextFor(
        lines: List<OcrTextLine>,
        rubyDetectionMode: RubyDetectionMode
    ): DialogueSourceText {
        val cleanedLines = cleanRubyNoiseLines(lines)
        if (cleanedLines.size < 2) {
            val text = cleanedLines.joinToString("\n") { it.text }.trim()
            return DialogueSourceText(translationText = text, voiceText = text)
        }

        val sorted = cleanedLines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        if (sorted.size < 2) {
            val text = sorted.joinToString("\n") { it.text }.trim()
            return DialogueSourceText(translationText = text, voiceText = text)
        }

        val heights = sorted.map { it.boundingBox.height().coerceAtLeast(1) }.sorted()
        val medianHeight = heights[heights.size / 2]
        val rubyLines = sorted.filter { line ->
            isLikelyRubyLine(line, medianHeight, rubyDetectionMode)
        }.toSet()
        val voiceLines = voiceDialogueLines(
            sorted = sorted,
            rubyLines = rubyLines,
            medianHeight = medianHeight
        )
        val voiceText = voiceLines.joinToString("\n") { it.text.trim() }.trim()
        if (rubyLines.isEmpty()) {
            val text = sorted.joinToString("\n") { it.text }.trim()
            return DialogueSourceText(translationText = text, voiceText = voiceText.ifBlank { text })
        }

        val mainLines = sorted.filterNot { it in rubyLines }.toMutableList()
        if (mainLines.isEmpty()) {
            val text = sorted.joinToString("\n") { it.text }.trim()
            return DialogueSourceText(translationText = text, voiceText = voiceText.ifBlank { text })
        }

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
        val rawText = sorted.filterNot { it in rubyLines }
            .joinToString("\n") { it.text.trim() }
            .trim()
        if (formatted.isNotBlank() && formatted != rawText) {
            FgoLogger.debug(tag, "Ruby formatted source (${rubyDetectionMode.name.lowercase()}): $formatted")
        }
        return DialogueSourceText(
            translationText = formatted,
            voiceText = voiceText.ifBlank { rawText.ifBlank { formatted } }
        )
    }

    private fun voiceDialogueLines(
        sorted: List<OcrTextLine>,
        rubyLines: Set<OcrTextLine>,
        medianHeight: Int
    ): List<OcrTextLine> {
        val geometryRubyLines = sorted.filter { line ->
            line !in rubyLines &&
                isRubyDotNoiseSized(line, medianHeight) &&
                sorted.any { main ->
                    main != line &&
                        main !in rubyLines &&
                        isLikelyRubyAboveMain(line, main, medianHeight)
                }
        }.toSet()
        val mainLines = sorted.filterNot { it in rubyLines || it in geometryRubyLines }
        val candidates = mainLines
            .ifEmpty { sorted.filterNot { it in rubyLines } }
            .ifEmpty { sorted }
        return limitVoiceDialogueLines(candidates)
    }

    private fun limitVoiceDialogueLines(lines: List<OcrTextLine>): List<OcrTextLine> {
        val sorted = lines.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        if (sorted.size <= 2) return sorted

        val maxHeight = sorted.maxOf { it.boundingBox.height().coerceAtLeast(1) }
        val fullSizeLines = sorted.filter { line ->
            line.boundingBox.height().coerceAtLeast(1) >= maxHeight * 0.8f
        }
        val candidates = fullSizeLines.ifEmpty { sorted }
        return candidates
            .sortedWith(
                compareByDescending<OcrTextLine> { it.boundingBox.height().coerceAtLeast(1) }
                    .thenBy { it.boundingBox.top }
                    .thenBy { it.boundingBox.left }
            )
            .take(2)
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
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
            isRubyDotNoiseLine(line) &&
                !isStandaloneDialoguePauseLine(line, meaningfulLines, medianHeight) &&
                isRubyDotNoiseSized(line, medianHeight) &&
                meaningfulLines.any { main ->
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

    private fun isRubyDotNoiseSized(line: OcrTextLine, medianHeight: Int): Boolean {
        val height = line.boundingBox.height().coerceAtLeast(1)
        return height <= medianHeight * RUBY_HEIGHT_RATIO
    }

    private fun isStandaloneDialoguePauseLine(
        line: OcrTextLine,
        meaningfulLines: List<OcrTextLine>,
        medianHeight: Int
    ): Boolean {
        val text = line.text.trim()
        if (!FgoDialogueSymbols.containsLongPause(text)) return false
        val hasSentenceEnd = text.any { it in setOf('。', '！', '!', '？', '?') }
        val dotLikeCount = text.count { it.isRubyDotNoiseChar() }
        if (!hasSentenceEnd && dotLikeCount < 2) return false

        return meaningfulLines.any { main ->
            val verticalDistance = main.boundingBox.top - line.boundingBox.bottom
            val startsNearMain = kotlin.math.abs(line.boundingBox.left - main.boundingBox.left) <= medianHeight * 3
            verticalDistance >= 0 &&
                    verticalDistance <= medianHeight * 2 &&
                    startsNearMain
        }
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
        if (region.region == TextRegion.CHOICE_BUTTON && hasRedTextPixels(source, region)) {
            return FGO_RENDER_RED
        }

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

    private fun hasRedTextPixels(source: Bitmap, region: ClassifiedRegion): Boolean {
        var redPixels = 0
        for (line in region.lines) {
            val bounds = Rect(line.boundingBox).apply { inset(-4, -4) }
            if (!bounds.intersect(0, 0, source.width, source.height)) continue
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            for (y in bounds.top until bounds.bottom step 2) {
                for (x in bounds.left until bounds.right step 2) {
                    if (isLikelyRedDialogueTextPixel(source.getPixel(x, y))) {
                        redPixels++
                        if (redPixels >= MIN_PALETTE_TEXT_PIXELS) return true
                    }
                }
            }
        }
        return false
    }

    private fun sampleCropOriginalTextColor(
        crop: Bitmap,
        lines: List<OcrTextLine>
    ): Int? {
        if (lines.isEmpty()) return null
        return sampleOriginalTextColor(
            source = crop,
            region = ClassifiedRegion(
                region = TextRegion.DIALOGUE_BOX,
                lines = lines,
                boundingBox = Rect(0, 0, crop.width, crop.height),
                ocrEngine = OcrEngineId.UNKNOWN
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
        sceneTranslation: SceneTranslateResult,
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
            ?.let { overlayRenderer.renderedDialogueText(it, source.width, source.height) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val originalDialogue = dialogueInstruction?.historyOriginalText()
        val dialogueTrustedForContext = sceneTranslation.dialogue?.trustedForContext == true
        val contextSourceSpeakerName = sceneSource.input.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val contextTranslatedSpeakerName = if (sceneTranslation.name?.trustedForContext == true) {
            sceneTranslation.name.translatedText
                .trim()
                .takeIf { it.isNotBlank() }
        } else {
            null
        }
        val contextSourceDialogue = dialogueInstruction
            ?.sourceText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val contextTranslatedDialogue = if (dialogueTrustedForContext) {
            dialogueInstruction
                ?.translatedText
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val choiceEntries = choiceInstructions.mapNotNull { instruction ->
            val translated = instruction.translatedText
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Triple(translated, instruction.historyOriginalText(), instruction.textColor)
        }
        val targetLocale = listOfNotNull(
            nameInstruction?.targetLocale,
            dialogueInstruction?.targetLocale
        )
            .plus(choiceInstructions.map { it.targetLocale })
            .firstOrNull { it == SettingsRepository.TARGET_LOCALE_TRADITIONAL }
            ?: SettingsRepository.TARGET_LOCALE_SIMPLIFIED
        val dialogueSourceKey = sceneSource.historyDialogueSourceKey()
        val entrySourceKey = sceneSource.historySourceKey(hasChoices = choiceEntries.isNotEmpty())

        if (name != null || dialogue != null || choiceEntries.isNotEmpty()) {
            SessionTranslationHistory.add(
                SessionTranslationEntry(
                    speakerName = name,
                    dialogueText = dialogue,
                    originalDialogueText = originalDialogue,
                    contextSourceSpeakerName = contextSourceSpeakerName,
                    contextTranslatedSpeakerName = contextTranslatedSpeakerName,
                    contextSourceDialogue = contextSourceDialogue,
                    contextTranslatedDialogue = contextTranslatedDialogue,
                    choices = choiceEntries.map { it.first },
                    originalChoices = choiceEntries.map { it.second },
                    speakerNameColor = nameInstruction?.textColor
                        ?: rawNameRegion?.let { sampleOriginalTextColor(source, it.region) },
                    dialogueTextColor = dialogueInstruction?.textColor,
                    choiceColors = choiceEntries.map { it.third },
                    targetLocale = targetLocale,
                    sourceKey = entrySourceKey,
                    dialogueSourceKey = dialogueSourceKey,
                    contextDialogueTranslationTrusted = dialogueTrustedForContext
                )
            )
        }
    }

    private fun RenderInstruction.historyOriginalText(): String? {
        if (!showOriginalText) return null
        return sourceText.trim().takeIf { it.isNotBlank() }
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
        val filteredChoiceBounds = filterChoiceBounds(rawChoiceBounds, screenRegions.choiceSearch)
        return withContext(Dispatchers.Default) {
            backgroundDetector.snapChoiceButtonsToFixedSlots(
                bitmap = source,
                rawButtons = filteredChoiceBounds,
                fixedSlotLayouts = screenRegions.choiceSlotLayouts
            )
        }
    }

    private suspend fun recognizeChoiceRegions(
        source: Bitmap,
        choiceBounds: List<Rect>,
        mode: ProcessingMode,
        retryEmptyTargetsIndividually: Boolean = mode == ProcessingMode.AUTO_BACKGROUND || choiceBounds.size >= 2,
        allowEnhancedSingleChoiceFallback: Boolean = true
    ): ChoiceRecognitionResult {
        val now = SystemClock.elapsedRealtime()
        val useEmptyChoiceCooldown = mode == ProcessingMode.AUTO_BACKGROUND
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
            if (allowEnhancedSingleChoiceFallback &&
                choiceBounds.size == 1 &&
                (mode.userInitiated || mode == ProcessingMode.AUTO_BACKGROUND)
            ) {
                recognizeEnhancedSingleChoiceRegion(source, choiceBounds.single())?.let { listOf(it) }
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

    private suspend fun recognizeEnhancedSingleChoiceRegion(
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
                ocrEngine.recognize(scaled!!, inputScale = OcrInputScale.X2)
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
                FgoLogger.debug(tag, "Enhanced single-choice binary OCR recovered ${lines.size} line(s)")
                ClassifiedRegion(
                    region = TextRegion.CHOICE_BUTTON,
                    lines = lines,
                    boundingBox = choiceBounds,
                    ocrEngine = ocrResult.engine
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
        return isLikelyRedDialogueTextPixel(pixel) ||
                (r >= 145 && g >= 145 && b >= 145 && max - min <= 95)
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
        val cleanedBounds = filterTrailingBottomChoiceArtifact(bounds, searchBounds)
        if (cleanedBounds.size != 1) return cleanedBounds

        val only = cleanedBounds.single()
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

        return cleanedBounds
    }

    private fun filterTrailingBottomChoiceArtifact(
        bounds: List<Rect>,
        searchBounds: Rect
    ): List<Rect> {
        if (bounds.size != 3) return bounds

        val last = bounds.last()
        val previous = bounds[bounds.lastIndex - 1]
        val bottomTolerance = (searchBounds.height() * 0.02f).toInt().coerceAtLeast(8)
        val nearPreviousTolerance = (searchBounds.height() * 0.04f).toInt().coerceAtLeast(16)
        val lowStartY = searchBounds.top + (searchBounds.height() * 0.64f).toInt()
        val touchesSearchBottom = last.bottom >= searchBounds.bottom - bottomTolerance
        val startsLow = last.top >= lowStartY
        val gluedToPrevious = last.top <= previous.bottom + nearPreviousTolerance

        if (touchesSearchBottom && startsLow && gluedToPrevious) {
            val cleaned = bounds.dropLast(1)
            FgoLogger.debug(
                tag,
                "Ignoring trailing bottom-edge choice artifact ${last.flattenToString()} " +
                    "after ${previous.flattenToString()}"
            )
            return cleaned
        }

        return bounds
    }

    private suspend fun recognizeDialogueRegions(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        allowRedTextFallback: Boolean = false
    ): List<ClassifiedRegion> {
        val regions = recognizeScreenRegions(
            source = source,
            targets = listOf(
                OcrRegionTarget(screenRegions.dialogue, TextRegion.DIALOGUE_BOX),
                OcrRegionTarget(screenRegions.name, TextRegion.NAME_LABEL)
            )
        ).map { region ->
            when (region.region) {
                TextRegion.NAME_LABEL -> region.copy(boundingBox = screenRegions.nameRender)
                TextRegion.DIALOGUE_BOX -> region.copy(boundingBox = screenRegions.dialogueRender)
                TextRegion.CHOICE_BUTTON -> region
            }
        }
        if (!allowRedTextFallback) return regions
        return recoverRedDialogueRegionIfNeeded(
            source = source,
            dialogueBounds = screenRegions.dialogue,
            dialogueRenderBounds = screenRegions.dialogueRender,
            regions = regions
        )
    }

    private suspend fun recoverRedDialogueRegionIfNeeded(
        source: Bitmap,
        dialogueBounds: Rect,
        dialogueRenderBounds: Rect,
        regions: List<ClassifiedRegion>
    ): List<ClassifiedRegion> {
        val normalDialogue = regions.firstOrNull { it.region == TextRegion.DIALOGUE_BOX }
        val normalText = normalDialogue?.let(::sourceTextFor).orEmpty()
        val normalQuality = dialogueOcrQuality(normalText)

        val redPixelRatio = redDialogueTextPixelRatio(source, dialogueBounds)
        if (redPixelRatio <= 0f) return regions

        val shouldTryEnhanced = normalQuality.suspicious ||
                redPixelRatio >= RED_DIALOGUE_FORCE_FALLBACK_RATIO
        if (!shouldTryEnhanced) return regions

        FgoLogger.debug(
            tag,
            "Red dialogue fallback checking redRatio=$redPixelRatio normal=$normalText"
        )

        val enhancedDialogue = recognizeEnhancedRedDialogueRegion(
            source = source,
            dialogueBounds = dialogueBounds,
            dialogueRenderBounds = dialogueRenderBounds
        )
        if (enhancedDialogue == null) {
            FgoLogger.debug(
                tag,
                "Red dialogue fallback found red pixels but recovered no text; dropping weak OCR: $normalText"
            )
            return if (normalQuality.suspicious) {
                regions.filterNot { it.region == TextRegion.DIALOGUE_BOX }
            } else {
                regions
            }
        }

        val enhancedText = sourceTextFor(enhancedDialogue)
        val enhancedQuality = dialogueOcrQuality(enhancedText)
        if (!enhancedQuality.isBetterThan(normalQuality)) {
            FgoLogger.debug(
                tag,
                "Red dialogue fallback rejected weaker OCR: normal=$normalText enhanced=$enhancedText"
            )
            return if (normalQuality.suspicious) {
                regions.filterNot { it.region == TextRegion.DIALOGUE_BOX }
            } else {
                regions
            }
        }

        FgoLogger.debug(
            tag,
            "Red dialogue fallback recovered OCR: $normalText -> $enhancedText"
        )
        if (normalDialogue == null) {
            return listOf(enhancedDialogue) + regions
        }
        return regions.map { region ->
            if (region.region == TextRegion.DIALOGUE_BOX) enhancedDialogue else region
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

        val targetUnionBounds = Rect(clippedTargets.first().bounds)
        clippedTargets.drop(1).forEach { targetUnionBounds.union(it.bounds) }
        val cropBounds = paddedSharedOcrBounds(
            targetUnionBounds,
            source.width,
            source.height
        )
        if (cropBounds.width() <= 0 || cropBounds.height() <= 0
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
                        boundingBox = target.bounds,
                        ocrEngine = ocrResult.engine
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
                    boundingBox = target.bounds,
                    ocrEngine = ocrResult.engine
                )
            }
        } finally {
            cropped.recycle()
        }
    }

    private suspend fun recognizeChoiceSlot(
        source: Bitmap,
        slot: Rect
    ): ClassifiedRegion? {
        val cropBounds = expandedOcrBounds(slot, source.width, source.height)
        if (cropBounds.width() <= 0 || cropBounds.height() <= 0) return null

        val cropped = Bitmap.createBitmap(
            source,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width(),
            cropBounds.height()
        )
        val scaledBitmap = try {
            Bitmap.createScaledBitmap(
                cropped,
                cropBounds.width() * CHOICE_OCR_SCALE,
                cropBounds.height() * CHOICE_OCR_SCALE,
                true
            )
        } catch (t: Throwable) {
            cropped.recycle()
            return null
        }

        return try {
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(scaledBitmap, inputScale = OcrInputScale.X2)
            }
            val regionLines = ocrResult.lines
                .map { line ->
                    OcrTextLine(
                        text = line.text,
                        boundingBox = Rect(
                            cropBounds.left + line.boundingBox.left / CHOICE_OCR_SCALE,
                            cropBounds.top + line.boundingBox.top / CHOICE_OCR_SCALE,
                            cropBounds.left + line.boundingBox.right / CHOICE_OCR_SCALE,
                            cropBounds.top + line.boundingBox.bottom / CHOICE_OCR_SCALE
                        ),
                        confidence = line.confidence
                    )
                }
                .filter { it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }
                .filter { lineBelongsToRegion(it.boundingBox, slot) }

            if (regionLines.isEmpty()) {
                null
            } else {
                ClassifiedRegion(
                    region = TextRegion.CHOICE_BUTTON,
                    lines = regionLines,
                    boundingBox = slot,
                    ocrEngine = ocrResult.engine
                )
            }
        } finally {
            scaledBitmap.recycle()
            cropped.recycle()
        }
    }

    private suspend fun recognizeChoiceRegionsByFixedSlots(
        source: Bitmap,
        screenRegions: FgoScreenRegions,
        preferredCount: Int?
    ): ChoiceRecognitionResult {
        val layouts = screenRegions.choiceSlotLayouts
        var bestBounds: List<Rect> = emptyList()
        var bestRegions: List<ClassifiedRegion> = emptyList()

        fun evaluate(layout: List<Rect>) {
            if (layout.isEmpty()) return
            val regions = layout.mapNotNull { slot ->
                recognizeChoiceSlot(source, slot)
            }.filter { region ->
                preferredCount != null || regionAverageConfidence(region) >= MIN_FIXED_SLOT_CONFIDENCE
            }
            if (regions.isEmpty()) return
            if (regions.size > bestRegions.size) {
                bestBounds = layout
                bestRegions = regions
            }
        }

        if (preferredCount != null) {
            layouts.firstOrNull { it.size == preferredCount }?.let { layout ->
                val regions = layout.mapNotNull { slot -> recognizeChoiceSlot(source, slot) }
                if (regions.size == layout.size) {
                    return ChoiceRecognitionResult(layout, regions)
                }
                if (regions.size > bestRegions.size) {
                    bestBounds = layout
                    bestRegions = regions
                }
            }
        }

        layouts.sortedByDescending { it.size }.forEach { layout ->
            if (preferredCount != null && layout.size == preferredCount) return@forEach
            evaluate(layout)
        }

        return ChoiceRecognitionResult(bestBounds, bestRegions)
    }

    private fun regionAverageConfidence(region: ClassifiedRegion): Float {
        if (region.lines.isEmpty()) return 0f
        return region.lines.map { it.confidence }.average().toFloat()
    }

    private suspend fun recognizeEnhancedRedDialogueRegion(
        source: Bitmap,
        dialogueBounds: Rect,
        dialogueRenderBounds: Rect
    ): ClassifiedRegion? {
        val cropBounds = Rect(dialogueBounds)
        if (!cropBounds.intersect(0, 0, source.width, source.height) ||
            cropBounds.width() <= 0 ||
            cropBounds.height() <= 0
        ) {
            return null
        }

        val cropped = Bitmap.createBitmap(
            source,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width(),
            cropBounds.height()
        )
        val normalized = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        var scaled: Bitmap? = null
        return try {
            val width = cropped.width
            val height = cropped.height
            val sourcePixels = IntArray(width * height)
            val normalizedPixels = IntArray(width * height) { Color.WHITE }
            cropped.getPixels(sourcePixels, 0, width, 0, 0, width, height)

            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    if (!isLikelyRedDialogueTextPixel(sourcePixels[rowOffset + x])) continue

                    val left = (x - 1).coerceAtLeast(0)
                    val right = (x + 1).coerceAtMost(width - 1)
                    val top = (y - 1).coerceAtLeast(0)
                    val bottom = (y + 1).coerceAtMost(height - 1)
                    for (ny in top..bottom) {
                        val normalizedRowOffset = ny * width
                        for (nx in left..right) {
                            normalizedPixels[normalizedRowOffset + nx] = Color.BLACK
                        }
                    }
                }
            }
            normalized.setPixels(normalizedPixels, 0, width, 0, 0, width, height)

            scaled = Bitmap.createScaledBitmap(
                normalized,
                normalized.width * RED_DIALOGUE_OCR_SCALE,
                normalized.height * RED_DIALOGUE_OCR_SCALE,
                false
            )
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(scaled!!, inputScale = OcrInputScale.X2)
            }
            val regionLines = ocrResult.lines
                .map { line ->
                    OcrTextLine(
                        text = line.text,
                        boundingBox = Rect(
                            cropBounds.left + line.boundingBox.left / RED_DIALOGUE_OCR_SCALE,
                            cropBounds.top + line.boundingBox.top / RED_DIALOGUE_OCR_SCALE,
                            cropBounds.left + line.boundingBox.right / RED_DIALOGUE_OCR_SCALE,
                            cropBounds.top + line.boundingBox.bottom / RED_DIALOGUE_OCR_SCALE
                        ),
                        confidence = line.confidence
                    )
                }
                .filter { it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }
                .filter { lineBelongsToRegion(it.boundingBox, dialogueBounds) }

            if (regionLines.isEmpty()) {
                null
            } else {
                FgoLogger.debug(tag, "Enhanced red dialogue OCR recovered ${regionLines.size} line(s)")
                ClassifiedRegion(
                    region = TextRegion.DIALOGUE_BOX,
                    lines = regionLines,
                    boundingBox = dialogueRenderBounds,
                    ocrEngine = ocrResult.engine
                )
            }
        } finally {
            scaled?.recycle()
            normalized.recycle()
            cropped.recycle()
        }
    }

    private fun redDialogueTextPixelRatio(source: Bitmap, dialogueBounds: Rect): Float {
        val bounds = Rect(dialogueBounds)
        if (!bounds.intersect(0, 0, source.width, source.height) ||
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            return 0f
        }

        var redPixels = 0
        var totalSamples = 0
        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                totalSamples++
                if (isLikelyRedDialogueTextPixel(source.getPixel(x, y))) {
                    redPixels++
                }
                x += RED_DIALOGUE_SCAN_STEP
            }
            y += RED_DIALOGUE_SCAN_STEP
        }

        if (totalSamples == 0 || redPixels < RED_DIALOGUE_MIN_SAMPLE_PIXELS) {
            return 0f
        }
        val ratio = redPixels.toFloat() / totalSamples.toFloat()
        return if (ratio >= RED_DIALOGUE_MIN_SAMPLE_RATIO) ratio else 0f
    }

    private fun isLikelyRedDialogueTextPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val strongestNonRed = maxOf(g, b)
        val vividRed = r >= 130 && r - strongestNonRed >= 35
        val dimRed = r >= 95 &&
                r - strongestNonRed >= 24 &&
                r * 2 >= g * 3 &&
                r * 2 >= b * 3
        return vividRed || dimRed
    }

    private fun dialogueOcrQuality(text: String): DialogueOcrQuality {
        val compact = text.filterNot { it.isWhitespace() }
        val japaneseChars = compact.count { it.isJapaneseTextChar() }
        val readableChars = compact.count { it.isLetterOrDigit() || it.isJapaneseTextChar() }
        val symbolChars = compact.length - readableChars
        val suspicious = compact.isBlank() ||
                readableChars == 0 ||
                (japaneseChars == 0 && readableChars < 3)
        val score = japaneseChars * 4 + readableChars * 2 - symbolChars
        return DialogueOcrQuality(
            japaneseChars = japaneseChars,
            readableChars = readableChars,
            score = score,
            suspicious = suspicious
        )
    }

    private data class DialogueOcrQuality(
        val japaneseChars: Int,
        val readableChars: Int,
        val score: Int,
        val suspicious: Boolean
    ) {
        fun isBetterThan(other: DialogueOcrQuality): Boolean {
            if (suspicious) return false
            val readableEnough = japaneseChars >= 2 || readableChars >= 4
            return readableEnough && score > other.score + 2
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

    /**
     * Keeps a small amount of screenshot context outside the configured OCR
     * regions so Paddle's wider line crop can see thin edge punctuation. Text
     * is still assigned against the original target bounds.
     */
    private fun paddedSharedOcrBounds(bounds: Rect, screenWidth: Int, screenHeight: Int): Rect {
        val paddingX = (bounds.width() * 0.02f).toInt().coerceAtLeast(12)
        val paddingY = (bounds.height() * 0.02f).toInt().coerceAtLeast(4)
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
        MediaProjectionCapture.capture()?.let {
            lastScreenshotErrorCode = 0
            return it
        }

        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer,
                                    result.colorSpace
                                )?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                FgoLogger.warn(tag, "Screenshot bitmap conversion failed", e)
                                null
                            } finally {
                                result.hardwareBuffer.close()
                            }
                            lastScreenshotErrorCode = if (bitmap == null) {
                                SCREENSHOT_ERROR_BITMAP_UNAVAILABLE
                            } else {
                                0
                            }
                            if (cont.isActive) {
                                cont.resume(bitmap)
                            } else {
                                bitmap?.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            lastScreenshotErrorCode = errorCode
                            val failureInfo = screenshotFailureInfo(errorCode)
                            FgoLogger.warn(tag, "Screenshot failed: code=$errorCode, reason=${failureInfo.reason}")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            }
        }
    }

    private data class ScreenshotFailureInfo(
        val reason: String,
        val detail: String,
        val code: String
    )

    private fun screenshotFailureInfo(errorCode: Int): ScreenshotFailureInfo {
        return when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> ScreenshotFailureInfo(
                reason = "Android 内部截屏失败",
                detail = "多见于模拟器、系统图形层或当前画面状态异常。可尝试重启游戏、重启模拟器，或切换模拟器图形渲染模式。",
                code = errorCode.toString()
            )
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> ScreenshotFailureInfo(
                reason = "无障碍截屏权限不可用",
                detail = "请关闭并重新开启 FgoGotran 无障碍服务；若系统限制无障碍权限，请在系统设置中允许。",
                code = errorCode.toString()
            )
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> ScreenshotFailureInfo(
                reason = "截屏太频繁，Android 暂时拒绝",
                detail = "请等待约 1 秒后再试；半自动/全自动模式会稍后重试。",
                code = errorCode.toString()
            )
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> ScreenshotFailureInfo(
                reason = "当前显示器无效",
                detail = "多见于模拟器、投屏、黑屏、省电锁屏或显示状态切换。请确认游戏画面实际显示在手机/模拟器主屏上。",
                code = errorCode.toString()
            )
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> ScreenshotFailureInfo(
                reason = "当前窗口无效",
                detail = "Android 无法读取当前窗口画面。请回到 FGO 主画面后再试，或重启游戏/模拟器。",
                code = errorCode.toString()
            )
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> ScreenshotFailureInfo(
                reason = "当前画面禁止系统截屏",
                detail = "系统标记了安全画面，应用无法读取截图。请避开受保护页面后再试。",
                code = errorCode.toString()
            )
            SCREENSHOT_ERROR_BITMAP_UNAVAILABLE -> ScreenshotFailureInfo(
                reason = "模拟器返回了截图对象，但无法转换成图片",
                detail = "多见于模拟器图形兼容问题。可尝试切换 DirectX/OpenGL/Vulkan 渲染模式、更新模拟器，或改用 64 位实例。",
                code = "bitmap_null"
            )
            else -> ScreenshotFailureInfo(
                reason = "Android/模拟器没有返回截图",
                detail = "未知截屏失败。请确认游戏画面没有黑屏，FgoGotran 无障碍服务仍开启，并尝试重启游戏或模拟器。",
                code = errorCode.takeIf { it != 0 }?.toString().orEmpty()
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
                    diagnosticEventStore.record(
                        level = DiagnosticEventStore.LEVEL_WARNING,
                        category = DiagnosticEventStore.CATEGORY_SETUP,
                        eventId = "gesture_injection_missing",
                        title = "无障碍手势注入不可用",
                        message = "区域翻译覆盖层点击无法转发给 FGO",
                        mode = "CROP_TAP"
                    )
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
        val fullAutoEnabled = TranslationTrigger.isAutoTranslateEnabled()
        val semiAutoEnabled = TranslationTrigger.isSemiAutoEnabled()
        val autoChoiceHandoff = (tappedChoice || hasRenderedChoices) && fullAutoEnabled
        val semiAutoChoiceHandoff = (tappedChoice || hasRenderedChoices) && semiAutoEnabled
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
        if (semiAutoChoiceHandoff) {
            renderedChoiceBounds = emptyList()
            FgoLogger.debug(
                tag,
                if (tappedChoice) {
                    "Semi-auto translated choice tapped; forwarding without pausing dialogue scan"
                } else {
                    "Semi-auto choice scene tapped; forwarding without pausing dialogue scan"
                }
            )
        }

        serviceScope.launch {
            isForwardingOverlayTap = true
            if (!canPerformGestures()) {
                FgoLogger.warn(tag, "Gesture injection is not granted; disable and re-enable accessibility service")
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_WARNING,
                    category = DiagnosticEventStore.CATEGORY_SETUP,
                    eventId = "gesture_injection_missing",
                    title = "无障碍手势注入不可用",
                    message = "翻译覆盖层点击无法转发给 FGO",
                    mode = "OVERLAY_TAP"
                )
                if (semiAutoChoiceHandoff) {
                    renderedChoiceBounds = currentRenderedChoiceBounds
                }
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
                    if (fullAutoEnabled) {
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
                    } else if (semiAutoEnabled) {
                        FgoLogger.debug(tag, "Semi-auto overlay tap replay completed; clearing tap overlay")
                        delay(TAP_TRANSLATION_READ_HOLD_DELAY)
                        translationOverlay.hideForCapture()
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
                    if (semiAutoChoiceHandoff) {
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

    private fun handleTranslatedOverlayTouch(event: MotionEvent): Boolean {
        return handleRenderedOverlayButtonTouch(event) {
            translationOverlay.hide()
        }
    }

    private fun handleCropResultOverlayTouch(event: MotionEvent): Boolean {
        return handleRenderedOverlayButtonTouch(event) {
            cropResultOverlay.hide()
        }
    }

    private fun handleRenderedOverlayButtonTouch(
        event: MotionEvent,
        hideInterceptingOverlay: () -> Unit
    ): Boolean {
        val x = event.rawX
        val y = event.rawY
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!runnerOverlay.isPointInsideButton(x, y)) {
                    resetOverlayButtonTouch()
                    false
                } else {
                    overlayButtonLongPressJob?.cancel()
                    overlayButtonLongPressJob = null
                    overlayButtonTouchActive = true
                    overlayButtonLongPressHandled = false
                    overlayButtonTouchCancelled = false
                    overlayButtonDragging = false
                    overlayButtonDownX = x
                    overlayButtonDownY = y
                    overlayButtonLastX = x
                    overlayButtonLastY = y
                    overlayButtonLongPressJob = serviceScope.launch {
                        delay(OVERLAY_BUTTON_LONG_PRESS_TIMEOUT)
                        if (overlayButtonTouchActive &&
                            !overlayButtonLongPressHandled &&
                            !overlayButtonTouchCancelled
                        ) {
                            overlayButtonLongPressHandled = true
                            hideInterceptingOverlay()
                            runnerOverlay.handleInterceptedButtonLongPress()
                        }
                    }
                    true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!overlayButtonTouchActive) {
                    false
                } else {
                    val dx = x - overlayButtonDownX
                    val dy = y - overlayButtonDownY
                    val slop = OVERLAY_BUTTON_TOUCH_SLOP * resources.displayMetrics.density
                    if (!overlayButtonDragging && dx * dx + dy * dy > slop * slop) {
                        overlayButtonLongPressJob?.cancel()
                        overlayButtonLongPressJob = null
                        overlayButtonTouchCancelled = true
                        overlayButtonDragging = true
                        if (runnerOverlay.handleInterceptedButtonDrag(dx, dy)) {
                            overlayButtonLastX = x
                            overlayButtonLastY = y
                        } else {
                            resetOverlayButtonTouch()
                        }
                    } else if (overlayButtonDragging) {
                        val dragDx = x - overlayButtonLastX
                        val dragDy = y - overlayButtonLastY
                        if (dragDx != 0f || dragDy != 0f) {
                            if (runnerOverlay.handleInterceptedButtonDrag(dragDx, dragDy)) {
                                overlayButtonLastX = x
                                overlayButtonLastY = y
                            } else {
                                resetOverlayButtonTouch()
                            }
                        }
                    }
                    true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!overlayButtonTouchActive) {
                    false
                } else {
                    val wasLongPress = overlayButtonLongPressHandled
                    val wasCancelled = overlayButtonTouchCancelled
                    resetOverlayButtonTouch()
                    if (!wasLongPress && !wasCancelled) {
                        runnerOverlay.handleInterceptedButtonTap(x, y)
                    }
                    true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!overlayButtonTouchActive) {
                    false
                } else {
                    resetOverlayButtonTouch()
                    true
                }
            }

            else -> overlayButtonTouchActive
        }
    }

    private fun resetOverlayButtonTouch() {
        overlayButtonLongPressJob?.cancel()
        overlayButtonLongPressJob = null
        overlayButtonTouchActive = false
        overlayButtonLongPressHandled = false
        overlayButtonTouchCancelled = false
        overlayButtonDragging = false
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
                processScreen(ProcessingMode.AUTO_BACKGROUND)
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
        if (!isEffectiveFgoForeground) return false
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
        return FgoPackages.isSupported(this)
    }

    private fun String.isEligibleForegroundTestPackage(): Boolean {
        return this != APP_PACKAGE &&
            !isSupportedFgoPackage() &&
            !isTransientSystemUiPackage() &&
            !isNonBlockingOverlayPackage()
    }

    private fun String.isUnsupportedFgoLikePackage(): Boolean {
        if (isSupportedFgoPackage()) return false
        val value = lowercase()
        return value.contains("fatego") ||
                value.contains("fategp") ||
                value.contains("fategrandorder") ||
                value.startsWith("com.aniplex.fate") ||
                value.startsWith("com.bilibili.fate") ||
                value.startsWith("com.bilibili.fgo") ||
                value.startsWith("com.komoe.fgo") ||
                value.startsWith("com.xiaomeng.fate")
    }

    private fun recordUnsupportedFgoLikePackage(packageName: String, event: AccessibilityEvent) {
        if (!packageName.isUnsupportedFgoLikePackage()) return
        val now = SystemClock.elapsedRealtime()
        val previousAt = unsupportedFgoLikePackageLoggedAt[packageName] ?: 0L
        if (now - previousAt < UNSUPPORTED_FGO_PACKAGE_LOG_COOLDOWN_MS) return
        unsupportedFgoLikePackageLoggedAt[packageName] = now
        pruneUnsupportedFgoLikePackageLogTimes(now)

        val className = event.className?.toString().orEmpty()
        val appLabel = packageLabelForDiagnostic(packageName)
        val detail = buildString {
            append("package=")
            append(packageName)
            if (className.isNotBlank()) {
                append(", class=")
                append(className)
            }
            if (appLabel.isNotBlank()) {
                append(", label=")
                append(appLabel)
            }
        }
        FgoLogger.warn(tag, "Unsupported FGO-like package detected: $detail")
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "unsupported_fgo_package",
            title = "未支持的 FGO 包名",
            message = "检测到疑似 FGO，但包名不在支持列表中",
            server = SettingsRepository.normalizeGameServer(gameServer),
            detail = detail
        )
    }

    private fun pruneUnsupportedFgoLikePackageLogTimes(now: Long) {
        val iterator = unsupportedFgoLikePackageLoggedAt.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > UNSUPPORTED_FGO_PACKAGE_LOG_COOLDOWN_MS) {
                iterator.remove()
            }
        }
    }

    private fun packageLabelForDiagnostic(packageName: String): String {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault("")
    }

    private fun String.isTransientSystemUiPackage(): Boolean {
        return this in TRANSIENT_SYSTEM_UI_PACKAGES
    }

    private fun String.isNonBlockingOverlayPackage(): Boolean {
        return this in NON_BLOCKING_OVERLAY_PACKAGES
    }

    private fun AccessibilityEvent.isDialogueAdvanceEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

}
