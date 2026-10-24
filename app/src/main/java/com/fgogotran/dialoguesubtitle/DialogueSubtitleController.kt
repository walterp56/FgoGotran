package com.fgogotran.dialoguesubtitle

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import com.fgogotran.R
import com.fgogotran.analytics.AnalyticsUsageOrigin
import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.data.SettingsRepository
import com.fgogotran.ocr.OcrContentKind
import com.fgogotran.ocr.OcrEngine
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.subtitle.ScreenOcrWorkflowState
import com.fgogotran.translation.BattleHistoryReservation
import com.fgogotran.translation.SceneTranslateInput
import com.fgogotran.translation.SessionTranslationHistory
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.TranslateResult
import com.fgogotran.translation.TranslationPromptProfile
import com.fgogotran.translation.TranslationRubyPolicy
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Independent voiced-panel observer. Advancing source text never cancels already captured lines. */
class DialogueSubtitleController @Inject constructor(
    private val ocr: OcrEngine,
    private val translator: Translator,
    private val workflow: ScreenOcrWorkflowState,
    private val settings: SettingsRepository,
    private val analytics: AppAnalytics
) {
    private data class Input(val source: String, val references: List<String>, val history: BattleHistoryReservation)
    private class Delivered(val result: TranslateResult, var usageReported: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assembler = DialogueSubtitleAssembler()
    private val pixels = DialogueSubtitlePixelGate()
    private val delivery = DialogueSubtitleDeliveryQueue<Delivered>()
    private val requests = mutableMapOf<Long, Job>()
    private val inputs = mutableMapOf<Long, Input>()
    private val referenceSources = ArrayDeque<String>()
    private var overlay: DialogueSubtitleOverlay? = null
    private var userContext: Context? = null
    private var generation = 0L
    private var observationGeneration = 0L
    private var paused = true
    private var width = 0
    private var height = 0
    private var includeRuby = SettingsRepository.DEFAULT_TRANSLATION_INCLUDE_RUBY
    private var contextEnabled = SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_ENABLED
    private var contextCount = SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_SCENE_COUNT
    private var currentTargetLocale = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    private var backlogWarned = false

    fun init(context: Context) {
        userContext = context
        overlay = DialogueSubtitleOverlay(context)
        scope.launch {
            var previous: List<String>? = null
            combine(
                settings.targetLanguage, settings.translationBackend, settings.apiModel,
                settings.apiBaseUrl, settings.ocrEngine, settings.translationIncludeRuby.map { it.toString() },
                settings.translationContextEnabled.map { it.toString() }, settings.translationContextSceneCount.map { it.toString() }
            ) { it.toList() }.distinctUntilChanged().collect { values ->
                if (previous != null && workflow.isDialogue) reset()
                currentTargetLocale = values[0]
                includeRuby = values[5].toBooleanStrict()
                contextEnabled = values[6].toBooleanStrict()
                contextCount = values[7].toInt().coerceIn(0, 2)
                previous = values
            }
        }
    }

    fun resume() { paused = false }

    suspend fun inspect(source: Bitmap, capturedAt: Long) {
        if (paused || !workflow.isDialogue) return
        if (source.width < source.height || source.height < 240) { suspendObservation(); return }
        if (width != source.width || height != source.height) {
            observationGeneration++
            width = source.width; height = source.height
            pixels.reset()
        }
        val version = observationGeneration
        val panel = withContext(Dispatchers.Default) {
            DialogueSubtitleLayout.panelPresent(source.width, source.height, source::getPixel)
        }
        if (version != observationGeneration || paused || !workflow.isDialogue) return
        // A bounded text backlog protects a long unattended session. Never evict captured occurrences.
        if (panel && delivery.size >= MAX_PENDING) {
            if (!backlogWarned) {
                FgoLogger.warn(TAG, "Dialogue capture paused: $MAX_PENDING captured captions await delivery; open LOG to review")
                userContext?.let { Toast.makeText(AppLanguageManager.wrap(it), R.string.dialogue_subtitle_backlog, Toast.LENGTH_LONG).show() }
            }
            backlogWarned = true
            refreshCaption()
            return
        }
        backlogWarned = false
        val shouldRead = panel && withContext(Dispatchers.Default) {
            pixels.shouldRecognize(source.width, source.height, source::getPixel, capturedAt, assembler.needsConfirmation)
        }
        val rows = if (shouldRead) recognize(source) else emptyList()
        if (version != observationGeneration || paused || !workflow.isDialogue) return
        if (!panel || shouldRead) {
            if (panel && rows.isEmpty() && pixels.hasTextInk) assembler.observationUnavailable()
            else accept(assembler.observe(rows, panel, capturedAt))
            if (assembler.playbackEnded) {
                referenceSources.clear()
                pixels.reset()
                FgoLogger.debug(TAG, "Dialogue panel ended; draining captured captions")
            }
        }
        refreshCaption()
    }

    private suspend fun recognize(source: Bitmap): List<DialogueSubtitleRow> {
        val bounds = DialogueSubtitleLayout.map(DialogueSubtitleLayout.text, source.width, source.height)
        val crop = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
        val started = SystemClock.elapsedRealtime()
        return try {
            val result = ocr.recognize(crop, contentKind = OcrContentKind.DIALOGUE)
            val scale = bounds.height.toFloat() / DialogueSubtitleLayout.text.height
            val rows = DialogueSubtitleText.extract(result.lines.map {
                DialogueSubtitleTextLine(it.text, it.boundingBox.left / scale, it.boundingBox.top / scale,
                    it.boundingBox.right / scale, it.boundingBox.bottom / scale, it.confidence)
            })
            FgoLogger.debug(TAG, "OCR ${SystemClock.elapsedRealtime() - started}ms, rows=${rows.size}")
            rows
        } finally { if (crop !== source) crop.recycle() }
    }

    private fun accept(events: List<DialogueSubtitleEvent>) {
        for (event in events) {
            val prepared = TranslationRubyPolicy.prepare(SceneTranslateInput(null, event.source, emptyList()), includeRuby)
                .dialogue.orEmpty()
            val references = if (contextEnabled) referenceSources.takeLast(contextCount) else emptyList()
            inputs[event.id] = Input(prepared, references,
                SessionTranslationHistory.reserveDialogueSubtitleEntry("dialogue:$generation:${event.id}", event.source))
            delivery.enqueue(event)
            referenceSources.addLast(prepared)
            while (referenceSources.size > 2) referenceSources.removeFirst()
            FgoLogger.debug(TAG, "Queued ${event.id}, backlog=${delivery.size}: ${event.source}")
        }
        pumpTranslations()
    }

    private fun pumpTranslations() {
        while (requests.size < 2) {
            val event = delivery.nextTranslation() ?: break
            val input = inputs[event.id] ?: break
            val version = generation
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val started = SystemClock.elapsedRealtime()
                    val result = if (DialogueSubtitleText.isExpressivePunctuationOnly(input.source)) {
                        TranslateResult(input.source, "source-punctuation", true,
                            targetLocale = currentTargetLocale, trustedForContext = false, countsAsUsage = false)
                    } else withContext(Dispatchers.IO) {
                        translator.translate(input.source, maxTokens = 512,
                            preserveRubyMeaning = TextNormalizer.hasRubyAnnotations(input.source),
                            promptProfile = TranslationPromptProfile.DIALOGUE_SUBTITLE,
                            subtitleReferenceJapanese = input.references)
                    }
                    if (version != generation || !workflow.isDialogue) return@launch
                    if (result.isFailure || result.backend == "none" || result.translatedText.isBlank()) {
                        delivery.fail(event.id)
                        recordFailure(input)
                        FgoLogger.warn(TAG, "Translation failed for captured occurrence ${event.id}; source retained in diagnostic log")
                    } else if (delivery.complete(event.id, Delivered(result), result.translatedText)) {
                        SessionTranslationHistory.completeDialogueSubtitleEntry(input.history, result.translatedText, result.targetLocale)
                        FgoLogger.debug(TAG, "Translation ready ${event.id}: ${SystemClock.elapsedRealtime() - started}ms, cache=${result.cached}")
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (version == generation) { delivery.fail(event.id); recordFailure(input) }
                    FgoLogger.warn(TAG, "Dialogue subtitle request failed: ${event.id}", error)
                } finally {
                    if (version == generation) {
                        requests.remove(event.id); inputs.remove(event.id)
                        pumpTranslations(); refreshCaption()
                    }
                }
            }
            requests[event.id] = job
            job.start()
        }
    }

    fun refreshCaption() {
        val now = SystemClock.elapsedRealtime()
        if (paused || !workflow.isDialogue || TranslationTrigger.isUiBlockingOcr() || width <= 0 || height <= 0) {
            delivery.pauseDisplay(now); overlay?.hide(); return
        }
        val item = delivery.candidate(now)
        val delivered = item?.result
        if (item == null || delivered == null) { overlay?.hide(); return }
        val version = generation
        val shown = overlay?.show(delivered.result.translatedText, width, height) {
            if (!paused && workflow.isDialogue && version == generation && delivery.markVisible(item.event.id, SystemClock.elapsedRealtime())) {
                if (!delivered.usageReported) {
                    delivered.usageReported = true
                    analytics.reportTranslationDisplayed(AnalyticsUsageOrigin(SettingsRepository.GAME_SERVER_JP), listOf(delivered.result))
                }
                FgoLogger.debug(TAG, "Rendered ${item.event.id}: ${SystemClock.elapsedRealtime() - item.event.startedAt}ms, cache=${delivered.result.cached}")
            }
        } == true
        if (!shown) delivery.pauseDisplay(now)
    }

    fun observationUnavailable() { assembler.observationUnavailable(); refreshCaption() }

    /** Retain the captured Japanese for troubleshooting, but never render/count an error as translation. */
    private fun recordFailure(input: Input) {
        userContext?.let {
            SessionTranslationHistory.completeDialogueSubtitleEntry(input.history,
                AppLanguageManager.wrap(it).getString(R.string.dialogue_subtitle_failed),
                currentTargetLocale)
        }
    }

    fun pause() {
        if (paused) return
        paused = true; observationGeneration++
        accept(assembler.suspendContinuations(SystemClock.elapsedRealtime()))
        assembler.observationUnavailable()
        refreshCaption()
    }
    fun suspendObservation() {
        pause()
        assembler.clear(); referenceSources.clear(); pixels.reset()
        width = 0; height = 0
    }
    fun reset() {
        generation++; observationGeneration++; paused = true
        requests.values.toList().forEach { it.cancel() }
        requests.clear(); inputs.clear(); delivery.clear(); assembler.clear(); referenceSources.clear(); pixels.reset()
        width = 0; height = 0; backlogWarned = false
        overlay?.hide()
    }
    fun destroy() { reset(); overlay?.destroy(); overlay = null; userContext = null; scope.cancel() }

    companion object { private const val TAG = "DialogueSubtitle"; private const val MAX_PENDING = 256 }
}
