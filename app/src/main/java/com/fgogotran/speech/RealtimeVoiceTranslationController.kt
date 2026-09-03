package com.fgogotran.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeVoiceTranslationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val audioCapture: FgoPlaybackAudioCapture,
    private val azureTranslator: Lazy<AzureRealtimeSpeechTranslator>,
    private val subtitleOverlay: VoiceSubtitleOverlay,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)
    private val stateLock = Any()
    private var sessionJob: Job? = null
    private val _state = MutableStateFlow<RealtimeVoiceTranslationState>(RealtimeVoiceTranslationState.Disabled)
    val state: StateFlow<RealtimeVoiceTranslationState> = _state.asStateFlow()

    suspend fun start(projection: MediaProjection?) {
        val startRequestId = generation.incrementAndGet()
        if (!settingsRepository.liveVoiceTranslationEnabled.first()) {
            if (isCurrent(startRequestId)) stop()
            return
        }
        if (!isCurrent(startRequestId)) return
        subtitleOverlay.prepare()
        if (!isCurrent(startRequestId)) return
        val activeProjection = projection
        if (activeProjection == null) {
            failConfigurationIfCurrent(
                startRequestId,
                "屏幕捕获会话不可用，请重新启动悬浮服务",
                "media_projection_missing"
            )
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failConfigurationIfCurrent(
                startRequestId,
                "未授予播放声音捕获权限",
                "record_audio_permission_missing"
            )
            return
        }
        if (Build.SUPPORTED_ABIS.none(AZURE_SPEECH_SUPPORTED_ABIS::contains)) {
            failConfigurationIfCurrent(
                startRequestId,
                "当前设备架构不受 Azure Speech SDK 支持",
                "azure_speech_abi_unsupported"
            )
            return
        }

        val config = AzureRealtimeTranslationConfig(
            key = settingsRepository.azureSpeechKey.first(),
            region = settingsRepository.azureSpeechRegion.first(),
            chinaEndpoint = settingsRepository.azureSpeechEndpoint.first(),
            targetLanguage = settingsRepository.targetChineseLocale.first()
        )
        if (!isCurrent(startRequestId)) return
        if (config.key.isBlank()) {
            failConfigurationIfCurrent(startRequestId, "Azure Speech Key 为空", "azure_speech_key_missing")
            return
        }
        if (config.region == SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3) {
            val endpointError = runCatching {
                AzureSpeechEndpointPolicy.normalizeChinaResourceEndpoint(config.chinaEndpoint)
            }.exceptionOrNull()
            if (endpointError != null) {
                failConfigurationIfCurrent(
                    startRequestId,
                    endpointError.message ?: "中国 Azure 资源端点无效",
                    "azure_china_endpoint_invalid"
                )
                return
            }
        }

        stopAndAwaitForRestart()
        if (!isCurrent(startRequestId)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runReconnectLoop(startRequestId, activeProjection, config)
        }
        val accepted = synchronized(stateLock) {
            if (isCurrent(startRequestId)) {
                sessionJob = job
                true
            } else {
                false
            }
        }
        if (accepted) job.start() else job.cancel()
    }

    fun stop() {
        generation.incrementAndGet()
        val job = synchronized(stateLock) { sessionJob }
        job?.cancel()
        audioCapture.stop()
        subtitleOverlay.hide()
        _state.value = RealtimeVoiceTranslationState.Disabled
    }

    fun destroyOverlay() {
        subtitleOverlay.destroy()
    }

    fun onDisplayChanged() {
        subtitleOverlay.onDisplayChanged()
    }

    private suspend fun stopAndAwaitForRestart() {
        val job = synchronized(stateLock) {
            sessionJob
        }
        job?.cancelAndJoin()
        synchronized(stateLock) {
            if (sessionJob === job) sessionJob = null
        }
        audioCapture.stop()
        subtitleOverlay.hide()
        _state.value = RealtimeVoiceTranslationState.Disabled
    }

    private fun failConfigurationIfCurrent(requestId: Long, message: String, eventId: String) {
        if (isCurrent(requestId)) failConfiguration(message, eventId)
    }

    private suspend fun runReconnectLoop(
        sessionId: Long,
        projection: MediaProjection,
        config: AzureRealtimeTranslationConfig
    ) {
        var retryIndex = 0
        while (isCurrent(sessionId) && kotlinx.coroutines.currentCoroutineContext().isActive) {
            val terminal = runAttempt(sessionId, projection, config)
            if (!isCurrent(sessionId) || terminal is AttemptTerminal.Stopped) return
            if (!terminal.retryable) {
                val message = terminal.message.ifBlank { "Azure 实时语音翻译失败" }
                _state.value = RealtimeVoiceTranslationState.Error(message)
                subtitleOverlay.showStatus(message, isError = true)
                recordFailure("live_voice_translation_failed", message, terminal.errorCode)
                return
            }

            val delayMs = RECONNECT_DELAYS_MS[retryIndex.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
            retryIndex = (retryIndex + 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
            _state.value = RealtimeVoiceTranslationState.Reconnecting(delayMs)
            FgoLogger.warn(tag, "Azure stream interrupted; reconnecting in ${delayMs}ms: ${terminal.message}")
            delay(delayMs)
        }
    }

    private suspend fun runAttempt(
        sessionId: Long,
        projection: MediaProjection,
        config: AzureRealtimeTranslationConfig
    ): AttemptTerminal {
        if (!isCurrent(sessionId)) return AttemptTerminal.Stopped
        val terminal = CompletableDeferred<AttemptTerminal>()
        val pcmFrames = Channel<ByteArray>(
            capacity = PCM_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        var writerJob: Job? = null
        _state.value = RealtimeVoiceTranslationState.Starting
        subtitleOverlay.activate()
        if (!isCurrent(sessionId)) {
            subtitleOverlay.hide()
            _state.value = RealtimeVoiceTranslationState.Disabled
            pcmFrames.close()
            return AttemptTerminal.Stopped
        }
        subtitleOverlay.showStatus("实时语音翻译正在连接…")

        return try {
            azureTranslator.get().start(config) eventHandler@{ event ->
                if (!isCurrent(sessionId)) return@eventHandler
                when (event) {
                    AzureTranslationEvent.SessionStarted -> Unit
                    AzureTranslationEvent.SessionStopped -> {
                        terminal.complete(
                            AttemptTerminal.Failed(
                                message = "Azure 会话已结束",
                                errorCode = "session_stopped",
                                retryable = true
                            )
                        )
                    }
                    is AzureTranslationEvent.Partial -> {
                        _state.value = RealtimeVoiceTranslationState.Translating(event.text)
                        subtitleOverlay.showPartial(event.text)
                    }
                    is AzureTranslationEvent.Final -> {
                        _state.value = RealtimeVoiceTranslationState.Listening
                        subtitleOverlay.showFinal(event.text)
                    }
                    is AzureTranslationEvent.Canceled -> {
                        terminal.complete(
                            AttemptTerminal.Failed(
                                message = cancellationMessage(event),
                                errorCode = event.errorCode,
                                retryable = isRetryableCancellation(event)
                            )
                        )
                    }
                }
            }
            if (!isCurrent(sessionId)) return AttemptTerminal.Stopped

            writerJob = scope.launch {
                try {
                    for (frame in pcmFrames) {
                        azureTranslator.get().writePcm(frame)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    terminal.complete(
                        AttemptTerminal.Failed(
                            message = t.message ?: "发送播放声音失败",
                            errorCode = "audio_stream_write_failed",
                            retryable = true
                        )
                    )
                }
            }
            val captureResult = audioCapture.start(
                projection = projection,
                onPcmFrame = { frame -> pcmFrames.trySend(frame) },
                onFailure = { error ->
                    terminal.complete(
                        AttemptTerminal.Failed(
                            message = error.message ?: "播放声音捕获失败",
                            errorCode = "audio_capture_failed",
                            retryable = false
                        )
                    )
                }
            )
            captureResult.exceptionOrNull()?.let { throw it }
            _state.value = RealtimeVoiceTranslationState.Listening
            FgoLogger.info(tag, "Realtime voice translation is listening")
            terminal.await()
        } catch (e: CancellationException) {
            AttemptTerminal.Stopped
        } catch (t: Throwable) {
            FgoLogger.warn(tag, "Realtime voice translation attempt failed", t)
            AttemptTerminal.Failed(
                message = userVisibleError(t),
                errorCode = "azure_start_failed",
                retryable = isRetryableThrowable(t)
            )
        } finally {
            withContext(NonCancellable) {
                audioCapture.stop()
                pcmFrames.close()
                writerJob?.cancelAndJoin()
                azureTranslator.get().stop()
            }
        }
    }

    private fun isCurrent(sessionId: Long): Boolean = generation.get() == sessionId

    private fun failConfiguration(message: String, eventId: String) {
        stop()
        _state.value = RealtimeVoiceTranslationState.Error(message)
        subtitleOverlay.activate()
        subtitleOverlay.showStatus(message, isError = true)
        recordFailure(eventId, message, "configuration")
    }

    private fun recordFailure(eventId: String, message: String, errorCode: String) {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_LIVE_VOICE_TRANSLATION,
            eventId = eventId,
            title = "实时语音翻译不可用",
            message = message,
            apiBackend = "azure_speech_translation",
            errorCode = errorCode
        )
    }

    private fun cancellationMessage(event: AzureTranslationEvent.Canceled): String {
        val details = event.details.trim()
        return if (details.isBlank()) {
            "Azure 语音会话已取消（${event.errorCode}）"
        } else {
            "Azure 语音会话已取消：${details.take(ERROR_DETAIL_MAX_CHARS)}"
        }
    }

    private fun isRetryableCancellation(event: AzureTranslationEvent.Canceled): Boolean {
        val value = "${event.errorCode} ${event.details}".lowercase()
        return NON_RETRYABLE_MARKERS.none(value::contains)
    }

    private fun isRetryableThrowable(t: Throwable): Boolean {
        if (t is IllegalArgumentException || t is SecurityException || t is LinkageError) return false
        val value = generateSequence(t) { it.cause }
            .joinToString(" ") { "${it::class.java.simpleName} ${it.message.orEmpty()}" }
            .lowercase()
        return NON_RETRYABLE_MARKERS.none(value::contains)
    }

    private fun userVisibleError(t: Throwable): String {
        val message = generateSequence(t) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
        return message?.take(ERROR_DETAIL_MAX_CHARS) ?: "Azure 实时语音翻译启动失败"
    }

    private sealed interface AttemptTerminal {
        val retryable: Boolean
        val message: String
        val errorCode: String

        data object Stopped : AttemptTerminal {
            override val retryable = false
            override val message = ""
            override val errorCode = "stopped"
        }

        data class Failed(
            override val message: String,
            override val errorCode: String,
            override val retryable: Boolean
        ) : AttemptTerminal
    }

    private companion object {
        const val tag = "RealtimeVoice"
        const val PCM_QUEUE_CAPACITY = 8
        const val ERROR_DETAIL_MAX_CHARS = 140
        val RECONNECT_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
        val NON_RETRYABLE_MARKERS = listOf(
            "authentication",
            "forbidden",
            "badrequest",
            "bad request",
            "invalid argument",
            "invalid subscription",
            "401",
            "403"
        )
        val AZURE_SPEECH_SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
}
