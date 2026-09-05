package com.fgogotran.battle

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.overlay.BackgroundDetector
import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.translation.BattleHistoryReservation
import com.fgogotran.translation.SessionTranslationHistory
import com.fgogotran.translation.TranslateResult
import com.fgogotran.translation.TranslationPromptProfile
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Service-owned observer. Detection, translation and ordered delivery have separate lifetimes. */
class BattleSubtitleController @Inject constructor(
    private val ocr: OcrEngine,
    private val translator: Translator,
    private val background: BackgroundDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scene = BattleSceneTracker()
    private val subtitles = BattleSubtitleTracker()
    private val delivery = BattleSubtitleDeliveryQueue<TranslateResult>()
    private val requests = mutableMapOf<Long, Job>()
    private val history = mutableMapOf<Long, BattleHistoryReservation>()
    private var overlay: BattleSubtitleOverlay? = null
    private var generation = 0L
    private var sessionGeneration = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var nextResultCheckAt = 0L
    private var paused = true
    private var subtitleCandidateSeen = false
    private var hudMatch: BattleHudMatch? = null

    // Finish queued battle lines before allowing an automatic story overlay to cover them.
    val blocksStory: Boolean get() = scene.blocksStory || delivery.hasPending
    val scanIntervalMs: Long get() = when {
        !scene.blocksStory -> 600L
        scene.mode == BattleSceneMode.WAITING -> 300L
        scene.mode == BattleSceneMode.STORY || subtitleCandidateSeen || subtitles.current != null -> 120L
        else -> 300L
    }

    fun init(context: Context) { overlay = BattleSubtitleOverlay(context) }

    fun resume() { paused = false }

    // Live game and package-bypassed tests share this exact frame path.
    suspend fun inspect(source: Bitmap, capturedAt: Long): Boolean {
        if (paused) return blocksStory
        if (source.width < source.height || source.height < 240) {
            suspendObservation()
            return blocksStory
        }
        if (source.width != screenWidth || source.height != screenHeight) {
            generation++
            screenWidth = source.width
            screenHeight = source.height
            hudMatch = null
        }
        val version = generation
        val detectedHud = withContext(Dispatchers.Default) {
            BattleHudDetector.locate(source.width, source.height, source::getPixel, hudMatch)
        }
        var hudVisible = detectedHud != null
        if (version != generation) return blocksStory
        if (hudVisible && scene.mode != BattleSceneMode.BATTLE && hudMatch == null) {
            hudVisible = BattleHudDetector.confirmsLabels(
                recognize(source, BattleLayout.hudSearch).joinToString(" ") { it.text }
            )
        }
        if (hudVisible && detectedHud != null) hudMatch = detectedHud
        else if (!scene.blocksStory) hudMatch = null
        if (version != generation) return blocksStory
        val now = SystemClock.elapsedRealtime()
        var storyVisible = false
        var resultVisible = false
        var choiceVisible = false
        if (scene.mode != BattleSceneMode.STORY) {
            val storyRegions = FgoViewportLayout.regionsForScreen(source.width, source.height)
            // The position-specific story signature is authoritative even if a
            // battle-coloured effect happens to satisfy the broad HUD prefilter.
            storyVisible = background.isDialogueCompleteMarkerVisible(source, storyRegions.dialogueComplete) &&
                background.isSkipButtonVisible(source, storyRegions.skip)
            if (!storyVisible && !hudVisible &&
                scene.mode == BattleSceneMode.BATTLE && now >= nextResultCheckAt) {
                val header = recognize(source, BattleLayout.resultHeader)
                    .joinToString("") { it.text }.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
                resultVisible = header == "RESULT" || header == "リザルト"
                // Once RESULT is seen, verify the next captured frame without a
                // cooldown so the two-observation transition cannot be reset by timing.
                nextResultCheckAt = if (resultVisible) 0L else now + 1_500L
            } else if (!storyVisible && !hudVisible && scene.mode == BattleSceneMode.WAITING) {
                val rawChoices = withContext(Dispatchers.Default) {
                    background.detectChoiceButtons(source, storyRegions.choiceSearch)
                }
                choiceVisible = withContext(Dispatchers.Default) {
                    background.snapChoiceButtonsToFixedSlots(
                        bitmap = source,
                        rawButtons = rawChoices,
                        fixedSlotLayouts = storyRegions.choiceSlotLayouts
                    ).isNotEmpty()
                }
            }
        }
        if (version != generation) return blocksStory
        val previousMode = scene.mode
        scene.observe(
            hudVisible = hudVisible,
            storyVisible = storyVisible,
            resultVisible = resultVisible,
            choiceVisible = choiceVisible
        )
        val currentMode = scene.mode
        if (currentMode == BattleSceneMode.STORY && !scene.blocksStory && !hudVisible) {
            hudMatch = null
        }
        if (previousMode == BattleSceneMode.BATTLE && currentMode != BattleSceneMode.BATTLE) {
            delivery.endAllSources(capturedAt)
            subtitles.clear()
            subtitleCandidateSeen = false
            hudMatch = null
        }
        if (previousMode != currentMode) {
            nextResultCheckAt = 0L
            val detail = when (currentMode) {
                BattleSceneMode.BATTLE -> "dynamic HUD confirmed, screen=" + screenWidth + "x" + screenHeight +
                    ", anchor=" + hudMatch?.referenceLeft + "," + hudMatch?.referenceTopOffset
                BattleSceneMode.WAITING -> "result confirmed; waiting for story or another battle"
                BattleSceneMode.STORY -> "story UI confirmed; finishing queued battle subtitles"
            }
            FgoLogger.info("BattleSubtitle", "Scene mode $previousMode -> $currentMode: $detail")
        }
        if (!scene.inBattle || storyVisible || resultVisible) {
            refreshCaption()
            return blocksStory
        }

        val lines = recognize(source, BattleLayout.subtitle)
        if (version != generation || paused) return blocksStory
        val text = BattleSubtitleText.extract(lines)
        val uncertain = text == null && BattleSubtitleText.hasUncertainSubtitle(lines)
        subtitleCandidateSeen = text != null || uncertain
        val event = if (uncertain) {
            subtitles.observationUnavailable()
            null
        } else subtitles.observe(text, capturedAt)
        subtitles.ended?.let { delivery.endSource(it.id, it.at) }
        if (event != null) {
            delivery.enqueue(event)
            history[event.id] = SessionTranslationHistory.reserveBattleEntry(
                "battle:" + sessionGeneration + ":" + event.id, event.source
            )
            FgoLogger.debug("BattleSubtitle", "Queued " + event.id + ": " + event.source)
            pumpTranslations()
        }
        refreshCaption()
        return blocksStory
    }

    private suspend fun recognize(source: Bitmap, reference: FgoReferenceRect): List<BattleTextLine> {
        val bounds = BattleLayout.map(reference, source.width, source.height)
        val crop = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
        try {
            val result = ocr.recognize(crop)
            val scale = bounds.height.toFloat() / reference.height
            return result.lines.map {
                BattleTextLine(it.text, it.boundingBox.left / scale, it.boundingBox.top / scale,
                    it.boundingBox.right / scale, it.boundingBox.bottom / scale, it.confidence)
            }
        } finally { if (crop !== source) crop.recycle() }
    }

    private fun pumpTranslations() {
        while (requests.size < MAX_CONCURRENT_TRANSLATIONS) {
            val event = delivery.nextTranslation() ?: break
            val version = sessionGeneration
            // Register before starting: even a cache hit cannot finish an unregistered worker.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(8_000L) {
                            translator.translate(
                                japaneseText = event.source,
                                maxApiAttempts = 1,
                                maxTokens = 512,
                                restoreSourcePunctuation = true,
                                promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE
                            )
                        }
                    }
                    if (version != sessionGeneration) return@launch
                    if (result == null || !result.trustedForContext || result.backend == "none" ||
                        result.translatedText.isBlank()) {
                        delivery.fail(event.id)
                        FgoLogger.warn("BattleSubtitle", "Translation failed/timed out for " + event.id + ": " + event.source)
                    } else if (delivery.complete(event.id, result)) {
                        history[event.id]?.let {
                            SessionTranslationHistory.completeBattleEntry(it, result.translatedText, result.targetLocale)
                        }
                        FgoLogger.debug("BattleSubtitle", "Translation ready " + event.id + ": queued for ordered display")
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (version == sessionGeneration) delivery.fail(event.id)
                    FgoLogger.warn("BattleSubtitle", "Battle subtitle translation failed: " + event.id, error)
                } finally {
                    if (version == sessionGeneration) {
                        requests.remove(event.id)
                        history.remove(event.id)
                        pumpTranslations()
                        refreshCaption()
                    }
                }
            }
            requests[event.id] = job
            job.start()
        }
    }

    fun refreshCaption() {
        val now = SystemClock.elapsedRealtime()
        if (paused || screenWidth <= 0 || screenHeight <= 0) {
            delivery.pauseDisplay(now)
            overlay?.hide()
            return
        }
        val item = delivery.candidate(now)
        val result = item?.result
        if (item == null || result == null) {
            overlay?.hide()
            return
        }
        val version = sessionGeneration
        val shown = overlay?.show(result.translatedText, screenWidth, screenHeight) {
            if (!paused && version == sessionGeneration &&
                delivery.markVisible(item.event.id, SystemClock.elapsedRealtime())) {
                FgoLogger.debug("BattleSubtitle", "Rendered " + item.event.id + ": " +
                    (SystemClock.elapsedRealtime() - item.event.startedAt) + "ms, cache=" + result.cached)
            }
        } == true
        if (!shown) delivery.pauseDisplay(now)
    }

    fun observationUnavailable() {
        subtitles.observationUnavailable()
        // A failed OCR read must not erase or hide an already captured translation.
        refreshCaption()
    }

    /** Menus/LOG hide the overlay and freeze reading time, but do not cancel translations. */
    fun pause() {
        if (paused) return
        paused = true
        generation++
        subtitles.observationUnavailable()
        refreshCaption()
    }

    /** Foreground loss/orientation changes stop observation, not delivery of captured lines. */
    fun suspendObservation() {
        pause()
        scene.reset()
        delivery.endAllSources(SystemClock.elapsedRealtime())
        subtitles.clear()
        screenWidth = 0
        screenHeight = 0
        hudMatch = null
        nextResultCheckAt = 0
    }

    /** Explicit service/feature shutdown: old callbacks cannot refill the next session. */
    fun reset() {
        sessionGeneration++
        generation++
        paused = true
        requests.values.toList().forEach { it.cancel() }
        requests.clear()
        history.clear()
        delivery.clear()
        subtitles.clear()
        scene.reset()
        subtitleCandidateSeen = false
        hudMatch = null
        nextResultCheckAt = 0
        screenWidth = 0
        screenHeight = 0
        overlay?.hide()
    }

    fun destroy() { reset(); overlay?.destroy(); overlay = null; scope.cancel() }

    companion object { private const val MAX_CONCURRENT_TRANSLATIONS = 2 }
}
