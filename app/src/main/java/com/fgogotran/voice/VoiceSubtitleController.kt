package com.fgogotran.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.Translator
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceSubtitleController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subtitleOverlay: VoiceSubtitleOverlay,
    private val settingsRepository: SettingsRepository,
    private val translator: Translator
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speechRecognizer: SpeechRecognizer? = null
    private var restartRunnable: Runnable? = null
    private var captureJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var audioRecord: AudioRecord? = null
    private var activeReadPipe: ParcelFileDescriptor? = null
    private var activeWritePipe: ParcelFileDescriptor? = null
    private var translationJob: Job? = null
    private var fallbackSubmissionRunnable: Runnable? = null
    private var lastPartialText = ""
    private var currentListeningSubmitted = false
    @Volatile private var audioOutput: OutputStream? = null
    @Volatile private var running = false
    private var listening = false
    private var lastRenderedText = ""
    private var lastAudioActivityLogMs = 0L
    @Volatile private var translationRequestId = 0
    private val tag = "VoiceSubtitle"

    fun isRunning(): Boolean = running

    fun start(resultCode: Int, resultData: Intent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            subtitleOverlay.showTemporaryStatus("Android 13+ 才支持非麦克风语音字幕")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            subtitleOverlay.showTemporaryStatus("本机语音识别不可用")
            return false
        }
        if (!hasSpeechRecognizerPermission()) {
            subtitleOverlay.showTemporaryStatus("语音识别权限未授权")
            return false
        }

        stopInternal(hideOverlay = true)

        val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, resultData)
        }.getOrElse { error ->
            FgoLogger.warn(tag, "Failed to get media projection", error)
            subtitleOverlay.showTemporaryStatus("无法读取游戏声音")
            return false
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!running) {
                    FgoLogger.debug(tag, "Media projection stop callback ignored; voice subtitle already stopped")
                    return
                }
                handleMediaProjectionStoppedBySystem()
            }
        }
        projection.registerCallback(callback, mainHandler)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val record = createAudioRecord(captureConfig)
        if (record == null) {
            projection.unregisterCallback(callback)
            projection.stop()
            subtitleOverlay.showTemporaryStatus("无法读取游戏声音")
            return false
        }

        mediaProjection = projection
        mediaProjectionCallback = callback
        audioRecord = record
        running = true
        lastRenderedText = ""
        lastPartialText = ""
        lastAudioActivityLogMs = 0L

        return runCatching {
            record.startRecording()
            captureJob = captureScope.launch {
                captureAudioLoop(record)
            }
            mainHandler.post { startRecognizerOnMain() }
            FgoLogger.info(tag, "Voice subtitles started with playback audio capture")
            true
        }.getOrElse { error ->
            FgoLogger.warn(tag, "Failed to start playback audio capture", error)
            subtitleOverlay.showTemporaryStatus("无法读取游戏声音")
            stopInternal(hideOverlay = true)
            false
        }
    }

    fun stop() {
        stopInternal(hideOverlay = true)
    }

    private fun handleMediaProjectionStoppedBySystem() {
        FgoLogger.warn(tag, "Media projection stopped by system; disabling voice subtitle")
        captureScope.launch {
            settingsRepository.setVoiceSubtitleEnabled(false)
        }
        stopInternal(hideOverlay = false, stopProjection = false)
        subtitleOverlay.showTemporaryStatus(SYSTEM_STOP_STATUS)
    }

    private fun hasSpeechRecognizerPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun stopInternal(
        hideOverlay: Boolean,
        stopProjection: Boolean = true
    ) {
        running = false
        listening = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        cancelFallbackSubmission()
        lastPartialText = ""
        currentListeningSubmitted = false
        lastAudioActivityLogMs = 0L
        closeActivePipe()

        captureJob?.cancel()
        captureJob = null
        translationJob?.cancel()
        translationJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        if (stopProjection) {
            runCatching { projection?.stop() }
        }

        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            lastRenderedText = ""
            if (hideOverlay) {
                subtitleOverlay.hide()
            }
            FgoLogger.info(tag, "Voice subtitles stopped")
        }
    }

    private fun createAudioRecord(
        captureConfig: AudioPlaybackCaptureConfiguration
    ): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            FgoLogger.warn(tag, "Invalid audio capture buffer size: $minBufferSize")
            return null
        }
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()
        }.getOrElse { error ->
            FgoLogger.warn(tag, "Failed to create playback AudioRecord", error)
            null
        }?.takeIf { record ->
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                true
            } else {
                FgoLogger.warn(tag, "Playback AudioRecord is not initialized")
                runCatching { record.release() }
                false
            }
        }
    }

    private fun captureAudioLoop(record: AudioRecord) {
        val buffer = ByteArray(AUDIO_BUFFER_BYTES)
        FgoLogger.debug(tag, "Playback audio capture loop started")
        while (running) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (error: Exception) {
                FgoLogger.warn(tag, "Playback audio read failed", error)
                break
            }
            if (read <= 0) continue
            logAudioActivityIfNeeded(buffer, read)
            val output = audioOutput ?: continue
            runCatching {
                output.write(buffer, 0, read)
            }.onFailure {
                audioOutput = null
            }
        }
        FgoLogger.debug(tag, "Playback audio capture loop ended")
    }

    private fun logAudioActivityIfNeeded(buffer: ByteArray, length: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAudioActivityLogMs < AUDIO_ACTIVITY_LOG_INTERVAL_MS) return
        var peak = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF))
                .toShort()
                .toInt()
            val absolute = if (sample < 0) -sample else sample
            if (absolute > peak) peak = absolute
            index += 2
        }
        if (peak >= AUDIO_ACTIVITY_PEAK_THRESHOLD) {
            lastAudioActivityLogMs = now
            FgoLogger.debug(tag, "Playback audio activity: peak=$peak")
        }
    }

    private fun startRecognizerOnMain() {
        if (!running) return
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }
        }
        startListeningSoon(START_DELAY_MS)
    }

    private fun startListeningSoon(delayMs: Long) {
        if (!running) return
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = Runnable {
            startListening()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun startListening() {
        if (!running || listening) return
        val recognizer = speechRecognizer ?: return
        closeActivePipe()
        val pipe = runCatching {
            ParcelFileDescriptor.createPipe()
        }.getOrElse { error ->
            FgoLogger.warn(tag, "Failed to create speech audio pipe", error)
            startListeningSoon(ERROR_RETRY_DELAY_MS)
            return
        }
        activeReadPipe = pipe[0]
        activeWritePipe = pipe[1]
        audioOutput = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        cancelFallbackSubmission()
        lastPartialText = ""
        currentListeningSubmitted = false
        listening = true
        runCatching {
            recognizer.startListening(buildRecognizerIntent(pipe[0]))
            FgoLogger.debug(tag, "Speech recognizer listening with playback audio source")
        }.onFailure { error ->
            listening = false
            closeActivePipe()
            FgoLogger.warn(tag, "Failed to start speech recognizer", error)
            startListeningSoon(ERROR_RETRY_DELAY_MS)
        }
    }

    private fun buildRecognizerIntent(audioSource: ParcelFileDescriptor): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, JAPANESE_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, JAPANESE_LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, CHANNEL_COUNT)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
            closeActivePipe()
            schedulePartialFallback("partial-end")
        }

        override fun onError(error: Int) {
            listening = false
            closeActivePipe()
            if (!running) return

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    FgoLogger.warn(tag, "Speech recognizer missing required RECORD_AUDIO permission")
                    subtitleOverlay.showTemporaryStatus("语音识别权限未授权")
                    stopInternal(hideOverlay = false)
                }
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_AUDIO -> {
                    FgoLogger.warn(tag, "Speech recognizer transient error: $error")
                    startListeningSoon(ERROR_RETRY_DELAY_MS)
                }
                else -> {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && lastPartialText.isNotBlank()) {
                        FgoLogger.debug(tag, "Speech recognizer no final result; using stable partial")
                        cancelFallbackSubmission()
                        submitCurrentListeningText("partial-no-match")
                    } else {
                        FgoLogger.debug(tag, "Speech recognizer retry after error=$error")
                    }
                    startListeningSoon(SILENCE_RETRY_DELAY_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            closeActivePipe()
            cancelFallbackSubmission()
            if (currentListeningSubmitted) {
                FgoLogger.debug(tag, "Speech recognizer final result ignored; current voice chunk already submitted")
            } else {
                val finalText = normalizeRecognizedText(results?.bestText())
                if (finalText.isNotBlank()) {
                    submitTextForTranslation(finalText, source = "final")
                } else {
                    submitCurrentListeningText("partial-final-empty")
                }
            }
            startListeningSoon(RESULT_RESTART_DELAY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = normalizeRecognizedText(partialResults?.bestText())
            if (text.isBlank() || text == lastPartialText) return
            lastPartialText = text
            FgoLogger.debug(tag, "Voice subtitle partial: ${text.take(LOG_TEXT_MAX_CHARS)}")
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle.bestText(): String? {
        return getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
    }

    private fun schedulePartialFallback(source: String) {
        if (currentListeningSubmitted || lastPartialText.isBlank()) return
        cancelFallbackSubmission()
        fallbackSubmissionRunnable = Runnable {
            submitCurrentListeningText(source)
        }.also { mainHandler.postDelayed(it, PARTIAL_FALLBACK_DELAY_MS) }
    }

    private fun cancelFallbackSubmission() {
        fallbackSubmissionRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackSubmissionRunnable = null
    }

    private fun submitCurrentListeningText(source: String) {
        if (currentListeningSubmitted || lastPartialText.isBlank()) return
        currentListeningSubmitted = true
        FgoLogger.debug(tag, "Voice subtitle submitting one chunk from $source")
        submitTextForTranslation(lastPartialText, source = source)
    }

    private fun normalizeRecognizedText(rawText: String?): String {
        return rawText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun submitTextForTranslation(rawText: String?, source: String): Boolean {
        val text = rawText
            ?.let { normalizeRecognizedText(it) }
            .orEmpty()
        if (text.isBlank()) return false
        if (text == lastRenderedText) {
            currentListeningSubmitted = true
            return true
        }
        currentListeningSubmitted = true
        lastRenderedText = text
        val requestId = ++translationRequestId
        translationJob?.cancel()
        translationJob = captureScope.launch {
            FgoLogger.debug(tag, "Voice subtitle source[$source]: ${text.take(LOG_TEXT_MAX_CHARS)}")
            val result = try {
                translator.translateVoiceSubtitle(text)
            } catch (error: Exception) {
                FgoLogger.warn(tag, "Voice subtitle translation failed", error)
                return@launch
            }
            if (!running || requestId != translationRequestId) return@launch
            val translatedText = result.translatedText.trim()
            if (translatedText.isBlank()) {
                FgoLogger.debug(tag, "Voice subtitle translation skipped empty result")
                return@launch
            }
            subtitleOverlay.showText(translatedText)
            FgoLogger.debug(
                tag,
                "Voice subtitle rendered: backend=${result.backend}, chars=${translatedText.length}"
            )
        }
        return true
    }

    private fun closeActivePipe() {
        val output = audioOutput
        val readPipe = activeReadPipe
        val writePipe = activeWritePipe
        audioOutput = null
        activeReadPipe = null
        activeWritePipe = null
        runCatching { output?.close() }
        runCatching { readPipe?.close() }
        runCatching { writePipe?.close() }
    }

    private companion object {
        const val JAPANESE_LANGUAGE = "ja-JP"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val AUDIO_BUFFER_BYTES = 4096
        const val START_DELAY_MS = 200L
        const val RESULT_RESTART_DELAY_MS = 250L
        const val SILENCE_RETRY_DELAY_MS = 450L
        const val ERROR_RETRY_DELAY_MS = 1_500L
        const val PARTIAL_FALLBACK_DELAY_MS = 180L
        const val AUDIO_ACTIVITY_LOG_INTERVAL_MS = 2_000L
        const val AUDIO_ACTIVITY_PEAK_THRESHOLD = 500
        const val LOG_TEXT_MAX_CHARS = 80
        const val SYSTEM_STOP_STATUS =
            "\u8bed\u97f3\u5b57\u5e55\u5df2\u505c\u6b62\uff0c\u8bf7\u91cd\u65b0\u5f00\u542f"
    }
}
