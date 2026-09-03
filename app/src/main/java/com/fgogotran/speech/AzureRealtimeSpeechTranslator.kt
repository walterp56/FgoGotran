package com.fgogotran.speech

import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger
import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AzureRealtimeSpeechTranslator @Inject constructor() {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var session: Session? = null

    suspend fun start(
        config: AzureRealtimeTranslationConfig,
        onEvent: (AzureTranslationEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            closeSession(detachSession())
            val key = config.key.trim().ifBlank {
                throw IllegalArgumentException("Azure Speech Key 为空")
            }
            val region = SettingsRepository.normalizeAzureSpeechRegion(config.region)
            val targetLanguage = SettingsRepository.normalizeTargetChineseLocale(config.targetLanguage)
            val translationConfig = if (region == SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3) {
                val endpoint = AzureSpeechEndpointPolicy.normalizeChinaResourceEndpoint(config.chinaEndpoint)
                SpeechTranslationConfig.fromEndpoint(URI(endpoint), key)
            } else {
                SpeechTranslationConfig.fromSubscription(key, region)
            }
            translationConfig.speechRecognitionLanguage = SOURCE_LANGUAGE
            translationConfig.addTargetLanguage(targetLanguage)

            val newSession = createSession(translationConfig)
            val recognizer = newSession.recognizer

            try {
                recognizer.sessionStarted.addEventListener { _, _ ->
                    onEvent(AzureTranslationEvent.SessionStarted)
                }
                recognizer.recognizing.addEventListener { _, event ->
                    event.result.translations[targetLanguage]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { onEvent(AzureTranslationEvent.Partial(it)) }
                }
                recognizer.recognized.addEventListener { _, event ->
                    event.result.translations[targetLanguage]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { onEvent(AzureTranslationEvent.Final(it)) }
                }
                recognizer.canceled.addEventListener { _, event ->
                    val details = if (event.reason == CancellationReason.Error) {
                        event.errorDetails.orEmpty()
                    } else {
                        event.reason.toString()
                    }
                    onEvent(
                        AzureTranslationEvent.Canceled(
                            errorCode = event.errorCode.toString(),
                            details = details
                        )
                    )
                }
                recognizer.sessionStopped.addEventListener { _, _ ->
                    onEvent(AzureTranslationEvent.SessionStopped)
                }
                recognizer.startContinuousRecognitionAsync()
                    .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                synchronized(stateLock) { session = newSession }
                FgoLogger.info(tag, "Azure streaming translation started: ja-JP -> $targetLanguage, region=$region")
            } catch (t: Throwable) {
                closeSession(newSession)
                throw t
            }
        }
    }

    fun writePcm(frame: ByteArray) {
        val stream = synchronized(stateLock) { session?.pushStream } ?: return
        stream.write(frame)
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            closeSession(detachSession())
        }
    }

    private fun detachSession(): Session? {
        return synchronized(stateLock) {
            val detached = session
            session = null
            detached
        }
    }

    private fun createSession(translationConfig: SpeechTranslationConfig): Session {
        var streamFormat: AudioStreamFormat? = null
        var pushStream: PushAudioInputStream? = null
        var audioConfig: AudioConfig? = null
        var recognizer: TranslationRecognizer? = null
        try {
            streamFormat = AudioStreamFormat.getWaveFormatPCM(
                SAMPLE_RATE_HZ.toLong(),
                BITS_PER_SAMPLE.toShort(),
                CHANNEL_COUNT.toShort()
            )
            pushStream = AudioInputStream.createPushStream(streamFormat)
            audioConfig = AudioConfig.fromStreamInput(pushStream)
            recognizer = TranslationRecognizer(translationConfig, audioConfig)
            return Session(
                translationConfig = translationConfig,
                streamFormat = streamFormat,
                pushStream = pushStream,
                audioConfig = audioConfig,
                recognizer = recognizer
            )
        } catch (t: Throwable) {
            runCatching { recognizer?.close() }
            runCatching { audioConfig?.close() }
            runCatching { pushStream?.close() }
            runCatching { streamFormat?.close() }
            runCatching { translationConfig.close() }
            throw t
        }
    }

    private fun closeSession(session: Session?) {
        if (session == null) return
        runCatching {
            session.recognizer.stopContinuousRecognitionAsync()
                .get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.onFailure { FgoLogger.warn(tag, "Azure streaming translation stop failed", it) }
        runCatching { session.recognizer.close() }
        runCatching { session.audioConfig.close() }
        runCatching { session.pushStream.close() }
        runCatching { session.streamFormat.close() }
        runCatching { session.translationConfig.close() }
        FgoLogger.info(tag, "Azure streaming translation stopped")
    }

    private data class Session(
        val translationConfig: SpeechTranslationConfig,
        val streamFormat: AudioStreamFormat,
        val pushStream: PushAudioInputStream,
        val audioConfig: AudioConfig,
        val recognizer: TranslationRecognizer
    )

    private companion object {
        const val tag = "AzureRealtime"
        const val SOURCE_LANGUAGE = "ja-JP"
        const val SAMPLE_RATE_HZ = 16_000
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL_COUNT = 1
        const val START_TIMEOUT_SECONDS = 10L
        const val STOP_TIMEOUT_SECONDS = 2L
    }
}
