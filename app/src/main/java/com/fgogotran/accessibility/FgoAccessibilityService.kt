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
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory
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
    private var autoScanReadyAt = 0L
    private var renderedChoiceBounds: List<Rect> = emptyList()
    private var waitingForChoiceSelectionExit = false
    private var isForwardingOverlayTap = false
    private var choiceOcrSuppressedUntil = 0L
    private var suppressedChoiceBoundsKey = ""
    private var emptyChoiceOcrStreak = 0
    private var tapAdvancePolling = false

    companion object {
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"
        private const val APP_PACKAGE = "com.fgogotran"
        private const val DETECTION_INTERVAL = 150L
        private const val CAPTURE_SETTLE_DELAY = 16L
        private const val MANUAL_MENU_DISMISS_SETTLE_DELAY = 300L
        private const val TAP_TRANSLATION_READ_HOLD_DELAY = 500L
        private const val NEXT_DIALOGUE_POLL_INTERVAL = 150L
        private const val NEXT_DIALOGUE_POLL_TIMEOUT = 2_500L
        private const val TAP_PASSTHROUGH_SETTLE_DELAY = 32L
        private const val TAP_REPLAY_TIMEOUT = 500L
        private const val EMPTY_CHOICE_OCR_BASE_COOLDOWN = 600L
        private const val EMPTY_CHOICE_OCR_MAX_COOLDOWN = 1_200L
        private const val FRESHNESS_CHECK_TRANSLATION_DELAY = 400L
        private const val VISUAL_FINGERPRINT_STEP = 3
        private const val VISUAL_FINGERPRINT_MAX_DIFF_RATIO = 0.035f

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
                                processScreen(forceRefresh = false)
                            }
                        } else if (manualRequest) {
                            FgoLogger.debug(tag, "Translate Now requested")
                            val waitForMenuDismissal = TranslationTrigger.consumeMenuDismissSettleRequired()
                            translationJob = serviceScope.launch {
                                if (waitForMenuDismissal) delay(MANUAL_MENU_DISMISS_SETTLE_DELAY)
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
        if (TranslationTrigger.isUiBlockingOcr()) {
            FgoLogger.debug(tag, "Overlay UI visible; skipping OCR")
            return
        }
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
            FgoLogger.debug(tag, "FGO viewport=${screenRegions.viewport}")

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
            val instructions = buildRenderInstructions(source, classifiedRegions)
            val translationDuration = SystemClock.elapsedRealtime() - translationStartedAt

            if (instructions.isEmpty()) {
                translationOverlay.hide()
                return
            }
            if (translationDuration >= FRESHNESS_CHECK_TRANSLATION_DELAY) {
                val sourceVisualFingerprint = visualFingerprintFor(source, classifiedRegions)
                if (!isSourceVisuallyCurrent(sourceVisualFingerprint)) {
                    FgoLogger.debug(tag, "Dialogue changed during translation; discarding stale result")
                    return
                }
            }
            if (translationDuration < FRESHNESS_CHECK_TRANSLATION_DELAY) {
                FgoLogger.debug(tag, "Fast translation (${translationDuration}ms); rendering without OCR recheck")
            }
            if (processingVersion != stopVersion) {
                FgoLogger.debug(tag, "Translation was stopped; discarding completed result")
                return
            }
            if (TranslationTrigger.isUiBlockingOcr()) {
                FgoLogger.debug(tag, "Overlay UI opened during translation; discarding result")
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
            addHistoryEntry(instructions)
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

    private suspend fun buildRenderInstructions(
        source: Bitmap,
        regions: List<ClassifiedRegion>
    ): List<RenderInstruction> {
        val translatableRegions = regions.mapNotNull { region ->
            val sourceText = sourceTextFor(region)
            if (sourceText.isBlank()) null else region to sourceText
        }
        if (translatableRegions.isEmpty()) return emptyList()

        val nameRegion = translatableRegions.firstOrNull { it.first.region == TextRegion.NAME_LABEL }
        val dialogueRegion = translatableRegions.firstOrNull { it.first.region == TextRegion.DIALOGUE_BOX }
        val choiceRegions = translatableRegions.filter { it.first.region == TextRegion.CHOICE_BUTTON }
        val sceneTranslation = translator.translateScene(
            SceneTranslateInput(
                name = nameRegion?.second,
                dialogue = dialogueRegion?.second,
                choices = choiceRegions.map { it.second }
            )
        )
        val translatedChoicesByRegion = choiceRegions
            .zip(sceneTranslation.choices)
            .associate { (regionAndText, result) -> regionAndText.first to result.translatedText }

        return translatableRegions.mapNotNull { regionAndText ->
            val translatedText = when (regionAndText.first.region) {
                TextRegion.NAME_LABEL -> sceneTranslation.name?.translatedText
                TextRegion.DIALOGUE_BOX -> sceneTranslation.dialogue?.translatedText
                TextRegion.CHOICE_BUTTON -> translatedChoicesByRegion[regionAndText.first]
            } ?: return@mapNotNull null
            RenderInstruction(
                region = regionAndText.first,
                translatedText = translatedText,
                textColor = sampleOriginalTextColor(source, regionAndText.first)
            )
        }
    }

    private fun sourceTextFor(region: ClassifiedRegion): String {
        return when (region.region) {
            TextRegion.DIALOGUE_BOX -> formatDialogueWithRubyAnnotations(region.lines)
            else -> region.lines
                .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
                .joinToString("\n") { it.text }
                .trim()
        }
    }

    private fun formatDialogueWithRubyAnnotations(lines: List<OcrTextLine>): String {
        if (lines.size < 2) {
            return lines.joinToString("\n") { it.text }.trim()
        }

        val sorted = lines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        if (sorted.size < 2) return sorted.joinToString("\n") { it.text }.trim()

        val heights = sorted.map { it.boundingBox.height().coerceAtLeast(1) }.sorted()
        val medianHeight = heights[heights.size / 2]
        val rubyLines = sorted.filter { line ->
            val height = line.boundingBox.height().coerceAtLeast(1)
            height <= medianHeight * 0.72f &&
                line.text.length <= 12 &&
                line.text.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
        }.toSet()
        if (rubyLines.isEmpty()) return sorted.joinToString("\n") { it.text }.trim()

        val mainLines = sorted.filterNot { it in rubyLines }.toMutableList()
        if (mainLines.isEmpty()) return sorted.joinToString("\n") { it.text }.trim()

        val rubyByMain = mutableMapOf<OcrTextLine, MutableList<OcrTextLine>>()
        for (ruby in rubyLines) {
            val main = mainLines
                .filter { it.boundingBox.top >= ruby.boundingBox.bottom - medianHeight / 3 }
                .filter { horizontalOverlap(ruby.boundingBox, it.boundingBox) > 0 || ruby.boundingBox.centerX() in it.boundingBox.left..it.boundingBox.right }
                .minByOrNull { it.boundingBox.top - ruby.boundingBox.bottom }
            if (main != null) {
                rubyByMain.getOrPut(main) { mutableListOf() }.add(ruby)
            } else {
                mainLines.add(ruby)
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
                        insertRubyAnnotation(text, main.boundingBox, ruby)
                    }
                }
            }
            .trim()
    }

    private fun horizontalOverlap(a: Rect, b: Rect): Int {
        return (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
    }

    private fun insertRubyAnnotation(mainText: String, mainBounds: Rect, ruby: OcrTextLine): String {
        if (mainText.isBlank()) return mainText
        val rubyText = ruby.text.trim()
        if (rubyText.isBlank() || mainText.contains("($rubyText)")) return mainText

        val approximateCharWidth = mainBounds.width().toFloat() / mainText.length.coerceAtLeast(1)
        val rawIndex = ((ruby.boundingBox.centerX() - mainBounds.left) / approximateCharWidth)
            .toInt()
            .coerceIn(1, mainText.length)
        val insertIndex = refineRubyInsertIndex(mainText, rawIndex)
        return mainText.substring(0, insertIndex) + "($rubyText)" + mainText.substring(insertIndex)
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
        val now = SystemClock.elapsedRealtime()
        val choiceBounds = withContext(Dispatchers.Default) {
            backgroundDetector.detectChoiceButtons(source, screenRegions.choiceSearch)
        }
        if (choiceBounds.isEmpty()) return emptyList()

        val choiceBoundsKey = choiceBounds.joinToString("|") { it.flattenToString() }
        if (now < choiceOcrSuppressedUntil && choiceBoundsKey == suppressedChoiceBoundsKey) {
            FgoLogger.debug(tag, "Skipping same empty choice panel during cooldown")
            return emptyList()
        }

        val choiceRegions = coroutineScope {
            choiceBounds.map { bounds ->
                async { recognizeScreenRegion(source, bounds, TextRegion.CHOICE_BUTTON) }
            }.awaitAll().filterNotNull()
        }
        if (choiceRegions.isEmpty()) {
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
            choiceOcrSuppressedUntil = 0L
            suppressedChoiceBoundsKey = ""
            emptyChoiceOcrStreak = 0
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
                if (backgroundDetector.detectChoiceButtons(currentScreenshot, screenRegions.choiceSearch).isNotEmpty()) {
                    return false
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
                processScreen(forceRefresh = false)
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
