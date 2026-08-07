package com.fgogotran.voice

import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiVoiceService @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val characterVoiceRepository: CharacterVoiceRepository,
    private val azureTtsClient: AzureTtsClient,
    private val audioCache: VoiceAudioCache,
    private val playbackEngine: VoicePlaybackEngine
) {
    private val tag = "AiVoice"
    private val speakMutex = Mutex()
    private var lastSpokenCacheMaterial: String? = null

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
        val profile = characterVoiceRepository.resolveProfileOrNull(speaker) ?: run {
            FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
            return
        }
        val voiceLanguage = settingsRepository.aiVoiceLanguage.first()
        val dialogue = voiceTextFor(
            profile = profile,
            voiceLanguage = voiceLanguage,
            sourceDialogue = sourceDialogue,
            translatedDialogue = translatedDialogue
        )
            ?.takeIf { TextNormalizer.hasTranslatableContent(it) }
            ?: return
        FgoLogger.debug(
            tag,
            "AI voice profile speaker=$speaker profile=${profile.profileId} " +
                "voice=${profile.voiceName} source=${voiceTextSourceFor(profile, voiceLanguage)}"
        )

        val speechKey = settingsRepository.azureSpeechKey.first().trim()
        if (speechKey.isBlank()) {
            FgoLogger.warn(tag, "AI voice enabled but Azure Speech key is blank")
            return
        }

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
        if (cacheMaterial == lastSpokenCacheMaterial) return
        lastSpokenCacheMaterial = cacheMaterial

        speakMutex.withLock {
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
                FgoLogger.warn(tag, "AI voice playback skipped", e)
            }
        }
    }

    private fun voiceTextFor(
        profile: VoiceProfile,
        voiceLanguage: String,
        sourceDialogue: String?,
        translatedDialogue: String?
    ): String? {
        val source = voiceTextSourceFor(profile, voiceLanguage)
        val preferredText = when (source) {
            VoiceTextSource.TRANSLATED_CHINESE -> translatedDialogue
            VoiceTextSource.GAME_TEXT -> sourceDialogue
        }
        val fallbackText = when (source) {
            VoiceTextSource.TRANSLATED_CHINESE -> sourceDialogue
            VoiceTextSource.GAME_TEXT -> translatedDialogue
        }
        return (preferredText?.takeIf { it.isNotBlank() } ?: fallbackText)
            ?.let(TextNormalizer::stripRubyAnnotations)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun voiceTextSourceFor(
        profile: VoiceProfile,
        voiceLanguage: String
    ): VoiceTextSource {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) {
            return VoiceTextSource.GAME_TEXT
        }
        return when (SettingsRepository.normalizeAiVoiceLanguage(voiceLanguage)) {
            SettingsRepository.AI_VOICE_LANGUAGE_CN_TRANSLATION -> VoiceTextSource.TRANSLATED_CHINESE
            else -> VoiceTextSource.GAME_TEXT
        }
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

    private enum class VoiceTextSource {
        GAME_TEXT,
        TRANSLATED_CHINESE
    }
}
