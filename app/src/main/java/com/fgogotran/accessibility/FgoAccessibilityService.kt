package com.fgogotran.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.overlay.*
import com.fgogotran.story.StoryDetector
import com.fgogotran.translation.Translator
import com.fgogotran.data.UserProfile
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.security.MessageDigest
import kotlin.coroutines.resume
import javax.inject.Inject

/**
 * The central orchestrator of the FGO translation pipeline.
 *
 * ## Lifecycle
 * 1. [onServiceConnected] — singleton reference + overlay init + screen metrics
 * 2. [onAccessibilityEvent] — dispatches TYPE_WINDOW_STATE_CHANGED and TYPE_TOUCH_INTERACTION_START
 * 3. [onInterrupt] / [onDestroy] — teardown: hides overlay, cancels coroutine scope
 *
 * ## 9-step pipeline (per touch event after 350ms debounce)
 * 1. Capture screenshot via AccessibilityService.takeScreenshot()
 * 2. OCR (ML Kit Japanese) to extract text lines with bounding boxes
 * 3. Story detection (spatial heuristic: are text lines concentrated in dialogue zone?)
 * 4. Content dedup via SHA-256 hash — skip if same dialogue as last frame
 * 5. Region classification (dialogue/name/choice by screen position)
 * 6. Extract dialogue + choice text for translation
 * 7. Translate (LLM API with RAG terminology injection + caching)
 * 8. Render CN text onto bitmap (erase JP, paint CN with sampled colors)
 * 9. Show rendered bitmap in the TYPE_ACCESSIBILITY_OVERLAY window
 *
 * ## Threading model
 * - Event dispatch runs on main thread; [processScreen] is a suspend function
 * - OCR, detection, classification, translation, and rendering all run on Dispatchers.Default/IO
 * - Overlay window updates must happen on Dispatchers.Main
 */
@AndroidEntryPoint
class FgoAccessibilityService : AccessibilityService() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var storyDetector: StoryDetector
    @Inject lateinit var translator: Translator
    @Inject lateinit var regionClassifier: RegionClassifier
    @Inject lateinit var colorSampler: ColorSampler
    @Inject lateinit var overlayRenderer: OverlayRenderer
    @Inject lateinit var translationOverlay: TranslationOverlay
    @Inject lateinit var userProfile: UserProfile

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastDialogueHash: String? = null
    private var isProcessing = false
    private var screenWidth = 0
    private var screenHeight = 0

    companion object {
        /** FGO JP package name — used to filter events from other apps. */
        const val FGO_PACKAGE = "com.aniplex.fategrandorder"

        /**
         * Delay from touch event to screenshot capture (ms).
         * FGO's dialogue text rendering completes ~250ms after tap;
         * 350ms provides a safety margin for varying device speeds.
         */
        private const val TOUCH_DEBOUNCE_MS = 350L

        /**
         * Minimum interval between processing consecutive taps (ms).
         * Prevents rapid-fire processing during animation transitions.
         */
        private const val MIN_TOUCH_INTERVAL_MS = 800L

        private val _serviceStarted = mutableStateOf(false)
        /** Observable state: whether the accessibility service is bound and running. */
        val serviceStarted: State<Boolean> = _serviceStarted

        /** Singleton reference — allows external components (e.g., UI) to interact with the service. */
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChange(event)
            // TYPE_TOUCH_INTERACTION_START not used — Unity (FGO's engine) doesn't dispatch it
        }
    }

    /**
     * Called by the system when another accessibility service requests interruption.
     * We hide the overlay immediately to avoid obstructing the user.
     */
    override fun onInterrupt() {
        FgoLogger.info(tag, "Service interrupted, hiding overlay")
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

    // ─── Event handlers ───────────────────────────────────────────────

    /**
     * Tracks whether FGO is in the foreground and triggers translation.
     * FGO uses Unity which doesn't dispatch TYPE_TOUCH_INTERACTION_START,
     * so we trigger the pipeline on window state changes instead.
     * The SHA-256 dedup + StoryDetector prevent redundant/non-story translations.
     */
    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName == FGO_PACKAGE) {
            FgoLogger.debug(tag, "FGO entered foreground")
            translationOverlay.showIndicator()
            // Trigger translation pipeline when FGO appears (e.g. after a tap advances dialogue)
            if (!isProcessing) {
                serviceScope.launch {
                    delay(TOUCH_DEBOUNCE_MS)
                    processScreen()
                }
            }
        } else if (packageName == "com.fgogotran") {
            // Ignore our own app's windows (overlay, floating button, dialogs)
            // — they should not hide the translation overlay
        } else {
            FgoLogger.info(tag, "Non-FGO package: $packageName, hiding overlay")
            translationOverlay.hideAll()
            lastDialogueHash = null
        }
    }

    // ─── Pipeline ─────────────────────────────────────────────────────

    /**
     * Runs the full 9-step OCR → translate → render pipeline.
     * Guarded by [isProcessing] to prevent concurrent executions.
     */
    private suspend fun processScreen() {
        if (isProcessing) return
        isProcessing = true
        val startTime = System.currentTimeMillis()

        try {
            // ═══ 1. Capture screen ═══
            val screenshot = takeScreenshotCompat() ?: run {
                FgoLogger.warn(tag, "Screenshot returned null, aborting pipeline")
                isProcessing = false
                return
            }
            // HARDWARE bitmap from HardwareBuffer — copy to ARGB_8888 for getPixel() support
            val screenshotCopy = screenshot.copy(Bitmap.Config.ARGB_8888, false)
            screenWidth = screenshotCopy.width
            screenHeight = screenshotCopy.height
            FgoLogger.debug(tag, "1. Screenshot captured: ${screenWidth}x${screenHeight}")

            // ═══ 2. OCR ═══
            val ocrResult = withContext(Dispatchers.Default) {
                ocrEngine.recognize(screenshotCopy)
            }
            if (ocrResult.lines.isEmpty()) {
                FgoLogger.debug(tag, "2. OCR returned 0 lines — not a text screen, aborting")
                isProcessing = false
                return
            }

            // ═══ 3. Story scene detection ═══
            // Uses spatial distribution of text lines to determine if we're looking at
            // a story/dialogue scene (vs gameplay, battle, or menus).
            val detectionResult = storyDetector.detect(
                ocrResult.lines, screenshotCopy.width, screenshotCopy.height
            )
            if (!detectionResult.isStoryScene) {
                FgoLogger.debug(tag, "3. Not a story scene (conf=${detectionResult.confidence}, " +
                    "${detectionResult.reason}), hiding overlay")
                withContext(Dispatchers.Main) { translationOverlay.hide() }
                lastDialogueHash = null
                isProcessing = false
                return
            }

            // ═══ 4. Content dedup via SHA-256 ═══
            // Skip translation if the dialogue text hasn't changed since last frame.
            // This avoids redundant API calls during animations or repeated taps.
            val currentHash = hashText(ocrResult.fullText)
            if (currentHash == lastDialogueHash) {
                FgoLogger.debug(tag, "4. Content unchanged (hash=${currentHash.take(8)}...), skipping")
                isProcessing = false
                return
            }
            lastDialogueHash = currentHash

            // ═══ 5. Region classification ═══
            val regions = withContext(Dispatchers.Default) {
                regionClassifier.classify(ocrResult.lines, screenshotCopy.width, screenshotCopy.height)
            }
            if (regions.isEmpty()) {
                FgoLogger.debug(tag, "5. No classifiable regions found, aborting")
                isProcessing = false
                return
            }
            val regionSummary = regions.groupBy { it.region }.mapValues { it.value.size }
            FgoLogger.debug(tag, "5. Classified regions: $regionSummary")

            // ═══ 6. Extract text per region ═══
            val dialogueRegions = regions.filter { it.region == TextRegion.DIALOGUE_BOX }
            val dialogueText = dialogueRegions.joinToString("\n") { region ->
                region.lines.joinToString("") { it.text }
            }
            val choiceTexts = regions.filter { it.region == TextRegion.CHOICE_BUTTON }
                .map { region -> region.lines.joinToString(" ") { it.text } }
            FgoLogger.debug(tag, "6. Dialogue text: ${dialogueText.length} chars, " +
                "choices: ${choiceTexts.size}")

            // ═══ 7. Translate (LLM API + RAG + cache) ═══
            val translateResult = withContext(Dispatchers.IO) {
                translator.translate(dialogueText, choiceTexts)
            }
            FgoLogger.info(tag, "7. Translation: backend=${translateResult.backend}, " +
                "cached=${translateResult.cached}, length=${translateResult.translatedText.length}")

            // ═══ 8. Render CN overlay onto screenshot bitmap ═══
            val instructions = buildRenderInstructions(
                bitmap = screenshotCopy,
                regions = regions,
                translatedText = translateResult.translatedText
            )
            val renderedBitmap = withContext(Dispatchers.Default) {
                overlayRenderer.render(screenshotCopy, instructions, screenshotCopy.width, screenshotCopy.height)
            }

            // ═══ 9. Show overlay ═══
            withContext(Dispatchers.Main) {
                translationOverlay.updateImage(renderedBitmap)
            }

            val elapsed = System.currentTimeMillis() - startTime
            FgoLogger.info(tag, "9. Pipeline complete in ${elapsed}ms")

        } catch (e: Exception) {
            FgoLogger.error(tag, "Pipeline error: ${e.message}", e)
        } finally {
            isProcessing = false
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Builds render instructions by sampling text/background colors from the screenshot
     * and mapping each classified region to its translated text.
     *
     * - Dialogue box: rendered with multi-line CN text split by LF
     * - Name label: rendered with the first line of translation (character name)
     * - Choice buttons: rendered with original JP text (choice translations handled separately)
     */
    private fun buildRenderInstructions(
        bitmap: Bitmap,
        regions: List<ClassifiedRegion>,
        translatedText: String
    ): List<RenderInstruction> {
        val instructions = mutableListOf<RenderInstruction>()

        // Split translated text into lines matching dialogue regions
        val translatedLines = translatedText.split("\n").filter { it.isNotBlank() }

        for (region in regions) {
            val textColor = colorSampler.sampleTextColor(bitmap, region.lines)
            val bgColor = colorSampler.sampleBackgroundColor(bitmap, region.boundingBox)

            when (region.region) {
                TextRegion.DIALOGUE_BOX -> {
                    // Use full multi-line CN translation for the dialogue box
                    val cnText = if (translatedLines.isNotEmpty()) {
                        translatedLines.joinToString("\n")
                    } else {
                        translatedText
                    }
                    instructions.add(
                        RenderInstruction(region, cnText, textColor, bgColor)
                    )
                }
                TextRegion.NAME_LABEL -> {
                    // Character name is typically the first line of the LLM output
                    val nameText = translatedLines.firstOrNull() ?: translatedText
                    instructions.add(
                        RenderInstruction(region, nameText, textColor, bgColor)
                    )
                }
                TextRegion.CHOICE_BUTTON -> {
                    // Choice buttons: use the original text since choices come from
                    // choiceTexts which have their own translation pass
                    val choiceText = region.lines.joinToString(" ") { it.text }
                    instructions.add(
                        RenderInstruction(region, choiceText, textColor, bgColor)
                    )
                }
            }
        }

        FgoLogger.debug(tag, "Built ${instructions.size} render instructions")
        return instructions
    }

    /**
     * Captures a screenshot via the AccessibilityService API.
     *
     * Uses [kotlin.coroutines.suspendCoroutine] to bridge the callback-based
     * [takeScreenshot] API into a suspend function, so the pipeline reads as
     * sequential steps rather than nested callbacks.
     *
     * The returned [Bitmap] is created from the screenshot's HardwareBuffer —
     * we call [HardwareBuffer.close] immediately since the Bitmap holds its own copy.
     */
    private suspend fun takeScreenshotCompat(): Bitmap? {
        return kotlin.coroutines.suspendCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace
                        )
                        result.hardwareBuffer.close()
                        cont.resume(bitmap)
                    }
                    override fun onFailure(errorCode: Int) {
                        FgoLogger.warn(tag, "Screenshot failed, errorCode=$errorCode")
                        cont.resume(null)
                    }
                }
            )
        }
    }

    /** Reads the device's raw screen dimensions (includes status bar / nav bar areas). */
    private fun initScreenSize() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    /**
     * Computes a SHA-256 hex digest of [text].
     * SHA-256 is used over MD5/SHA-1 for collision resistance —
     * while not a security concern here, it avoids false matches on large
     * volumes of structurally similar game text.
     */
    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
