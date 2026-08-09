package com.fgogotran.voice

import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiVoiceService @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val characterVoiceRepository: CharacterVoiceRepository,
    private val tempVoiceProfileRepository: TempVoiceProfileRepository,
    private val tempVoiceProfileBuilder: TempVoiceProfileBuilder,
    private val diagnosticEventStore: DiagnosticEventStore,
    private val azureTtsClient: AzureTtsClient,
    private val audioCache: VoiceAudioCache,
    private val playbackEngine: VoicePlaybackEngine
) {
    private val tag = "AiVoice"
    private val speakMutex = Mutex()
    private val tempProfileMutex = Mutex()
    private val tempProfileFailureRetryAt = mutableMapOf<String, Long>()
    private var lastSpokenCacheMaterial: String? = null
    private var lastSpokenLineKey: String? = null

    suspend fun speakDialogue(
        speakerName: String?,
        sourceDialogue: String?,
        translatedDialogue: String?,
        voiceHint: VoiceLineHint? = null
    ) {
        if (!settingsRepository.aiVoiceEnabled.first()) return

        val speaker = speakerName
            ?.let(TextNormalizer::stripRubyAnnotations)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val dialogue = voiceTextFor(
            translatedDialogue = translatedDialogue
        )
            ?.takeIf { TextNormalizer.hasTranslatableContent(it) }
            ?: return
        val speechKey = settingsRepository.azureSpeechKey.first().trim()
        if (speechKey.isBlank()) {
            FgoLogger.warn(tag, "AI voice enabled but Azure Speech key is blank")
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "azure_key_missing",
                title = "Azure Speech Key 未设置",
                message = "AI语音已开启，但 Azure Speech Key 为空",
                speaker = speaker,
                textPreview = dialogue.previewText()
            )
            return
        }

        val gameServer = settingsRepository.getGameServer()
        val normalizedServer = SettingsRepository.normalizeGameServer(gameServer)
        val lineKey = voiceLineKey(normalizedServer, speaker, dialogue)
        val profile = resolveVoiceProfile(
            gameServer = normalizedServer,
            speaker = speaker,
            dialogue = dialogue
        ) ?: run {
            FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
            return
        }
        FgoLogger.debug(
            tag,
            "AI voice profile speaker=$speaker profile=${profile.profileId} " +
                "voice=${profile.voiceName} source=translated_chinese"
        )

        val speechRegion = settingsRepository.azureSpeechRegion.first()
            .trim()
            .ifBlank { SettingsRepository.DEFAULT_AZURE_SPEECH_REGION }
        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        val expression = voiceExpressionFor(
            profile = profile,
            dialogue = dialogue,
            voiceHint = voiceHint
        )
        val request = VoiceSynthesisRequest(
            speakerName = speaker,
            spokenText = dialogue,
            profile = profile,
            styleOverride = expression?.styleOverride,
            rateOverride = expression?.rateOverride,
            pitchOverride = expression?.pitchOverride,
            styleDegree = expression?.styleDegree,
            pauseScale = expression?.pauseScale,
            ssmlModeVersion = expression?.ssmlModeVersion
        )
        val cacheMaterial = request.cacheMaterial()

        speakMutex.withLock {
            if (lineKey == lastSpokenLineKey || cacheMaterial == lastSpokenCacheMaterial) {
                FgoLogger.debug(tag, "AI voice duplicate skipped: speaker=$speaker")
                return@withLock
            }
            lastSpokenLineKey = lineKey
            lastSpokenCacheMaterial = cacheMaterial
            runCatching {
                val audioFile = withContext(Dispatchers.IO) {
                    audioCache.cachedFile(cacheMaterial) ?: audioCache.write(
                        cacheMaterial = cacheMaterial,
                        audio = azureTtsClient.synthesize(
                            config = AzureSpeechConfig(key = speechKey, region = speechRegion),
                            profile = profile,
                            text = dialogue,
                            styleOverride = expression?.styleOverride,
                            rateOverride = expression?.rateOverride,
                            pitchOverride = expression?.pitchOverride,
                            styleDegree = expression?.styleDegree,
                            pauseScale = expression?.pauseScale
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    playbackEngine.play(audioFile, voiceVolumePercent)
                }
            }.onFailure { e ->
                if (lastSpokenCacheMaterial == cacheMaterial) {
                    lastSpokenCacheMaterial = null
                }
                if (lastSpokenLineKey == lineKey) {
                    lastSpokenLineKey = null
                }
                val errorMessage = e.message.orEmpty()
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                    eventId = if (errorMessage.contains("Azure TTS", ignoreCase = true)) {
                        "azure_tts_failed"
                    } else {
                        "voice_playback_failed"
                    },
                    title = if (errorMessage.contains("Azure TTS", ignoreCase = true)) {
                        "Azure 语音合成失败"
                    } else {
                        "语音播放失败"
                    },
                    message = errorMessage.ifBlank { e::class.java.simpleName },
                    server = normalizedServer,
                    speaker = speaker,
                    detail = "profile=${profile.profileId}",
                    voiceType = profile.description,
                    voiceName = profile.voiceName,
                    textPreview = dialogue.previewText()
                )
                FgoLogger.warn(tag, "AI voice playback skipped", e)
            }
        }
    }

    private suspend fun resolveVoiceProfile(
        gameServer: String,
        speaker: String,
        dialogue: String
    ): VoiceProfile? {
        characterVoiceRepository.resolveProfileOrNull(speaker)?.let { return it }

        val normalizedServer = SettingsRepository.normalizeGameServer(gameServer)
        tempVoiceProfileRepository.resolveProfileOrNull(normalizedServer, speaker)?.let { return it }

        val normalizedSpeaker = VoiceNameNormalizer.normalize(speaker)
        val tempKey = "$normalizedServer|$normalizedSpeaker"
        val now = System.currentTimeMillis()
        val retryAt = tempProfileFailureRetryAt[tempKey] ?: 0L
        if (retryAt > now) {
            FgoLogger.debug(tag, "Temp voice profile API cooldown active: server=$normalizedServer speaker=$speaker")
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_api_cooldown",
                title = "临时语音 API 冷却中",
                message = "上次建立失败，暂时不重复请求",
                server = normalizedServer,
                speaker = speaker,
                textPreview = dialogue.previewText()
            )
            return null
        }

        return tempProfileMutex.withLock {
            characterVoiceRepository.resolveProfileOrNull(speaker)?.let { return@withLock it }
            tempVoiceProfileRepository.resolveProfileOrNull(normalizedServer, speaker)?.let { return@withLock it }

            FgoLogger.info(tag, "Temp voice profile miss: server=$normalizedServer speaker=$speaker")
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_WARNING,
                category = DiagnosticEventStore.CATEGORY_MISSING_VOICE,
                eventId = "voice_profile_missing",
                title = "找不到语音档案",
                message = "主语音表与临时语音表都未命中",
                server = normalizedServer,
                speaker = speaker,
                detail = "curated=miss temp=miss",
                textPreview = dialogue.previewText()
            )
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_INFO,
                category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                eventId = "temp_voice_api_request",
                title = "请求建立临时语音档案",
                server = normalizedServer,
                speaker = speaker,
                textPreview = dialogue.previewText()
            )
            runCatching {
                val row = tempVoiceProfileBuilder.build(
                    server = normalizedServer,
                    nameBox = speaker,
                    dialogue = dialogue
                ) ?: throw IllegalStateException("API returned no temp voice profile")
                val profile = tempVoiceProfileRepository.upsert(normalizedServer, row)
                    ?: throw IllegalStateException("Temp voice profile could not be stored")
                row to profile
            }.onSuccess { (row, profile) ->
                tempProfileFailureRetryAt.remove(tempKey)
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_INFO,
                    category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                    eventId = "temp_voice_api_built",
                    title = "已建立临时语音档案",
                    message = "rate=${row.rate} pitch=${row.pitch}",
                    server = normalizedServer,
                    speaker = speaker,
                    detail = listOfNotNull(
                        row.style.takeIf { style -> style.isNotBlank() }?.let { style -> "style=$style" },
                        row.reason.takeIf { reason -> reason.isNotBlank() }?.let { reason -> "reason=$reason" }
                    ).joinToString(" "),
                    voiceType = row.voiceType,
                    voiceName = row.voiceName,
                    textPreview = dialogue.previewText()
                )
                profile
            }.onFailure { e ->
                tempProfileFailureRetryAt[tempKey] = System.currentTimeMillis() + TEMP_PROFILE_FAILURE_COOLDOWN_MS
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_TEMP_VOICE_API,
                    eventId = "temp_voice_api_failed",
                    title = "建立临时语音档案失败",
                    message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                    server = normalizedServer,
                    speaker = speaker,
                    textPreview = dialogue.previewText()
                )
                FgoLogger.warn(tag, "Temp voice profile generation failed: server=$normalizedServer speaker=$speaker", e)
            }.getOrNull()?.second
        }
    }

    private fun voiceTextFor(
        translatedDialogue: String?
    ): String? {
        return translatedDialogue
            ?.let(TextNormalizer::stripRubyAnnotations)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun voiceLineKey(server: String, speaker: String, dialogue: String): String {
        return listOf(
            SettingsRepository.normalizeGameServer(server),
            compactVoiceKeyText(speaker),
            compactVoiceKeyText(dialogue)
        ).joinToString("|")
    }

    private fun compactVoiceKeyText(text: String): String {
        val normalized = Normalizer.normalize(
            TextNormalizer.stripRubyAnnotations(text).trim(),
            Normalizer.Form.NFKC
        )
        val compact = buildString(normalized.length) {
            normalized.forEach { char ->
                if (char.isLetterOrDigit()) {
                    append(char.lowercaseChar())
                }
            }
        }
        return compact.ifBlank {
            normalized.replace(Regex("\\s+"), "")
                .lowercase(Locale.US)
        }
    }

    private fun String.previewText(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(TEXT_PREVIEW_CHARS)
    }

    private fun voiceExpressionFor(
        profile: VoiceProfile,
        dialogue: String,
        voiceHint: VoiceLineHint?
    ): VoiceExpression? {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) {
            return null
        }
        return ChineseVoiceEmotionStyle.expressionFor(profile, dialogue, voiceHint)
    }

    fun stop() {
        playbackEngine.stop()
    }

    private companion object {
        const val TEMP_PROFILE_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
        const val TEXT_PREVIEW_CHARS = 80
    }
}
