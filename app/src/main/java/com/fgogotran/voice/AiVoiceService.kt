package com.fgogotran.voice

import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TextNormalizer
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
        japaneseDialogue: String?,
        translatedDialogue: String?
    ) {
        if (!settingsRepository.aiVoiceEnabled.first()) return

        val voiceLanguage = settingsRepository.aiVoiceLanguage.first()
        val speaker = speakerName
            ?.let(TextNormalizer::stripRubyAnnotations)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val profile = characterVoiceRepository.resolveProfileOrNull(speaker, voiceLanguage) ?: run {
            FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
            return
        }
        FgoLogger.debug(
            tag,
            "AI voice profile speaker=$speaker profile=${profile.profileId} voice=${profile.voiceName}"
        )

        val dialogue = voiceTextFor(
            voiceLanguage = voiceLanguage,
            japaneseDialogue = japaneseDialogue,
            translatedDialogue = translatedDialogue
        )
            ?.takeIf { TextNormalizer.hasTranslatableContent(it) }
            ?: return

        val speechKey = settingsRepository.azureSpeechKey.first().trim()
        if (speechKey.isBlank()) {
            FgoLogger.warn(tag, "AI voice enabled but Azure Speech key is blank")
            return
        }

        val speechRegion = settingsRepository.azureSpeechRegion.first()
            .trim()
            .ifBlank { SettingsRepository.DEFAULT_AZURE_SPEECH_REGION }
        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        val styleOverride = emotionStyleFor(
            voiceLanguage = voiceLanguage,
            profile = profile,
            dialogue = dialogue
        )
        val request = VoiceSynthesisRequest(
            speakerName = speaker,
            spokenText = dialogue,
            profile = profile,
            styleOverride = styleOverride
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
                            styleOverride = styleOverride
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
        voiceLanguage: String,
        japaneseDialogue: String?,
        translatedDialogue: String?
    ): String? {
        val sourceText = when (SettingsRepository.normalizeAiVoiceLanguage(voiceLanguage)) {
            SettingsRepository.AI_VOICE_LANGUAGE_CN_TRANSLATION -> translatedDialogue
            else -> japaneseDialogue
        }
        return sourceText
            ?.let(TextNormalizer::stripRubyAnnotations)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun emotionStyleFor(
        voiceLanguage: String,
        profile: VoiceProfile,
        dialogue: String
    ): String? {
        if (SettingsRepository.normalizeAiVoiceLanguage(voiceLanguage) !=
            SettingsRepository.AI_VOICE_LANGUAGE_CN_TRANSLATION
        ) {
            return null
        }
        return ChineseVoiceEmotionStyle.styleFor(profile, dialogue)
            ?.takeUnless { it == profile.style }
    }

    fun stop() {
        playbackEngine.stop()
    }
}
