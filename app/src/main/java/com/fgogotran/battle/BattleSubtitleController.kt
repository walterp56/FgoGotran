package com.fgogotran.battle

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.overlay.FgoReferenceRect
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

/** Context/cache trust must not delay or suppress a real first-pass battle translation. */
internal fun TranslateResult.isDisplayableBattleResponse(): Boolean =
    !isFailure && backend != "none" && translatedText.isNotBlank()

/** Service-owned observer. Detection, translation and ordered delivery have separate lifetimes. */
class BattleSubtitleController @Inject constructor(
    private val ocr: OcrEngine,
    private val translator: Translator,
    private val battleModeState: BattleModeState
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subtitles = BattleSubtitleTracker()
    private val subtitlePixelGate = BattleSubtitlePixelGate()
    private val delivery = BattleSubtitleDeliveryQueue<TranslateResult>()
    private val requests = mutableMapOf<Long, Job>()
    private val history = mutableMapOf<Long, BattleHistoryReservation>()

    private var overlay: BattleSubtitleOverlay? = null
    private var generation = 0L
    private var sessionGeneration = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var paused = true

    val scanIntervalMs: Long
        get() = 120L

    fun init(context: Context) {
        overlay = BattleSubtitleOverlay(context)
    }

    fun resume() {
        paused = false
    }

    // Manual battle mode owns this path. It intentionally performs no story-marker
    // or battle-HUD detection; the user selects when this OCR pipeline is active.
    suspend fun inspect(
        source: Bitmap,
        capturedAt: Long
    ) {
        if (paused || !battleModeState.active.value) return

        if (source.width < source.height || source.height < 240) {
            suspendObservation()
            return
        }

        if (source.width != screenWidth || source.height != screenHeight) {
            generation++
            screenWidth = source.width
            screenHeight = source.height
            subtitlePixelGate.reset()
        }

        val version = generation

        val shouldRecognize = withContext(Dispatchers.Default) {
            subtitlePixelGate.shouldRecognize(
                width = source.width,
                height = source.height,
                pixel = source::getPixel,
                now = capturedAt,
                confirmationRequired = subtitles.needsConfirmation
            )
        }

        if (!shouldRecognize) {
            refreshCaption()
            return
        }

        val lines = recognize(
            source,
            BattleLayout.subtitle
        )

        if (version != generation || paused || !battleModeState.active.value) {
            return
        }

        val candidate = BattleSubtitleText.extractCandidate(lines)
        val uncertain =
            candidate == null &&
                    BattleSubtitleText.hasUncertainSubtitle(lines)

        val event = if (uncertain) {
            subtitles.observationUnavailable()
            null
        } else if (candidate != null) {
            subtitles.observeCandidate(candidate, capturedAt)
        } else {
            subtitles.observe(null, capturedAt)
        }

        if (event != null) {
            enqueueDetectedEvent(event)
        }

        // A disappearance-confirmed one-frame subtitle is both created and ended by
        // the same observation, so enqueue it before applying its source end time.
        subtitles.endedEvents.forEach {
            delivery.endSource(it.id, it.at)
        }

        if (event != null) {
            pumpTranslations()
        }

        refreshCaption()
    }

    private fun enqueueDetectedEvent(event: BattleSubtitleEvent) {
        delivery.enqueue(event)

        history[event.id] =
            SessionTranslationHistory.reserveBattleEntry(
                "battle:" + sessionGeneration + ":" + event.id,
                event.source
            )

        FgoLogger.debug(
            "BattleSubtitle",
            "Queued " +
                    event.id +
                    " after " +
                    subtitles.lastConfirmationObservations +
                    " observation(s), " +
                    subtitles.lastConfirmationReason +
                    ": " +
                    event.source
        )
    }

    private suspend fun recognize(
        source: Bitmap,
        reference: FgoReferenceRect
    ): List<BattleTextLine> {
        val bounds = BattleLayout.map(
            reference,
            source.width,
            source.height
        )

        val crop = Bitmap.createBitmap(
            source,
            bounds.left,
            bounds.top,
            bounds.width,
            bounds.height
        )

        try {
            val result = ocr.recognize(crop)
            val scale = bounds.height.toFloat() / reference.height

            return result.lines.map {
                BattleTextLine(
                    it.text,
                    it.boundingBox.left / scale,
                    it.boundingBox.top / scale,
                    it.boundingBox.right / scale,
                    it.boundingBox.bottom / scale,
                    it.confidence
                )
            }
        } finally {
            if (crop !== source) {
                crop.recycle()
            }
        }
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
                                promptProfile =
                                    TranslationPromptProfile.BATTLE_SUBTITLE
                            )
                        }
                    }

                    if (version != sessionGeneration) {
                        return@launch
                    }

                    if (
                        result == null ||
                        !result.isDisplayableBattleResponse()
                    ) {
                        delivery.fail(event.id)

                        FgoLogger.warn(
                            "BattleSubtitle",
                            "Translation failed/timed out for " +
                                    event.id +
                                    ": " +
                                    event.source
                        )
                    } else if (delivery.complete(event.id, result)) {
                        if (!result.trustedForContext) {
                            FgoLogger.warn(
                                "BattleSubtitle",
                                "Rendering first response without cache/context trust for " +
                                        event.id
                            )
                        }

                        history[event.id]?.let {
                            SessionTranslationHistory.completeBattleEntry(
                                it,
                                result.translatedText,
                                result.targetLocale
                            )
                        }

                        FgoLogger.debug(
                            "BattleSubtitle",
                            "Translation ready " +
                                    event.id +
                                    ": queued for ordered display"
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (version == sessionGeneration) {
                        delivery.fail(event.id)
                    }

                    FgoLogger.warn(
                        "BattleSubtitle",
                        "Battle subtitle translation failed: " + event.id,
                        error
                    )
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

        val shown = overlay?.show(
            result.translatedText,
            screenWidth,
            screenHeight
        ) {
            if (
                !paused &&
                version == sessionGeneration &&
                delivery.markVisible(
                    item.event.id,
                    SystemClock.elapsedRealtime()
                )
            ) {
                FgoLogger.debug(
                    "BattleSubtitle",
                    "Rendered " +
                            item.event.id +
                            ": " +
                            (
                                    SystemClock.elapsedRealtime() -
                                            item.event.startedAt
                                    ) +
                            "ms, cache=" +
                            result.cached
                )
            }
        } == true

        if (!shown) {
            delivery.pauseDisplay(now)
        }
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
        delivery.endAllSources(SystemClock.elapsedRealtime())
        subtitles.clear()
        screenWidth = 0
        screenHeight = 0
        subtitlePixelGate.reset()
    }

    /** Explicit service/feature shutdown: old callbacks cannot refill the next session. */
    fun reset() {
        sessionGeneration++
        generation++
        paused = true

        requests.values.toList().forEach {
            it.cancel()
        }

        requests.clear()
        history.clear()
        delivery.clear()
        subtitles.clear()
        subtitlePixelGate.reset()
        screenWidth = 0
        screenHeight = 0
        overlay?.hide()
    }

    fun destroy() {
        reset()
        overlay?.destroy()
        overlay = null
        scope.cancel()
    }

    companion object {
        private const val MAX_CONCURRENT_TRANSLATIONS = 2
    }
}
