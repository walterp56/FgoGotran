package com.fgogotran.voice

import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.translation.TextNormalizer
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class AzureVoiceTestResult(
    val speakerName: String,
    val dialogue: String,
    val voiceName: String,
    val profileId: String,
    val voiceHintApplied: Boolean
)

@Singleton
class AiVoiceService @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appAnalytics: AppAnalytics,
    private val characterVoiceRepository: CharacterVoiceRepository,
    private val voiceDataUpdateManager: VoiceDataUpdateManager,
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
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val voiceRequestLock = Any()
    private val tempProfileFailureRetryAt = mutableMapOf<String, Long>()
    private var latestVoiceRequestId = 0L
    private var lastRequestedCacheMaterial: String? = null
    private var lastRequestedLineKey: String? = null

    /** Local lookup only: never create a temporary voice just to request an acting hint. */
    suspend fun prepareKnownVoiceTarget(
        gameServer: String,
        speakerName: String
    ): PreparedVoiceTarget? = withContext(Dispatchers.IO) {
        val server = SettingsRepository.normalizeGameServer(gameServer)
        val speaker = normalizeVisibleSpeakerName(speakerName).takeIf(String::isNotBlank)
            ?: return@withContext null
        val profiles = linkedMapOf<String, VoiceProfile>()
        for (part in splitVoiceSpeakers(speaker)) {
            profiles[part] = findKnownVoiceProfile(server, part) ?: return@withContext null
        }
        PreparedVoiceTarget(server, profiles.toMap())
    }

    private data class PreparedVoiceLine(
        val speaker: String,
        val profile: VoiceProfile,
        val expression: VoiceExpression?,
        val cacheMaterial: String
    )

    suspend fun speakDialogue(
        speakerName: String?,
        sourceDialogue: String?,
        translatedDialogue: String?,
        voiceHint: VoiceLineHint? = null,
        preparedTarget: PreparedVoiceTarget? = null
    ) {
        if (!settingsRepository.aiVoiceEnabled.first()) return
        val targetLanguage = settingsRepository.targetLanguage.first()
        if (!SettingsRepository.readTextAvailableFor(targetLanguage)) {
            FgoLogger.info(tag, "AI voice skipped: no read text for target=$targetLanguage")
            return
        }
        val readTextLanguage = SettingsRepository.resolveReadTextLanguage(
            targetLanguage,
            settingsRepository.aiVoiceLanguage.first()
        )
        val readTextGameServer =
            SettingsRepository.normalizeGameServer(settingsRepository.getGameServer())
        val readTextSource = when (readTextGameServer) {
            SettingsRepository.GAME_SERVER_EN -> "game_en"
            SettingsRepository.GAME_SERVER_CN,
            SettingsRepository.GAME_SERVER_TW -> "game_cn"
            else -> when (readTextLanguage) {
                SettingsRepository.AI_VOICE_LANGUAGE_EN_TRANSLATION -> "translated_en"
                else -> "translated_cn"
            }
        }
        FgoLogger.debug(
            tag,
            "AI voice read text: source=$readTextSource target=$targetLanguage"
        )

        val speaker = speakerName
            ?.let(::normalizeVisibleSpeakerName)
            ?.takeIf(String::isNotBlank)
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
        val speakers = splitVoiceSpeakers(speaker)
        val lineKey = voiceLineKey(normalizedServer, speakers.joinToString("|"), dialogue)
        val speechRegion = SettingsRepository.normalizeAzureSpeechRegion(
            settingsRepository.azureSpeechRegion.first()
        )
        val voiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        val emotionEnhancementEnabled = settingsRepository.aiVoiceApiHintsEnabled.first()
        val preparedLines = prepareVoiceLines(
            gameServer = normalizedServer,
            speakers = speakers,
            dialogue = dialogue,
            voiceHint = voiceHint.takeIf { emotionEnhancementEnabled },
            azureSpeechRegion = speechRegion,
            aiVoiceSpeedPercent = voiceSpeedPercent,
            emotionEnhancementEnabled = emotionEnhancementEnabled,
            readTextSource = readTextSource,
            preparedTarget = preparedTarget?.takeIf { it.matches(normalizedServer, speakers) }
        )
        if (preparedLines.isEmpty()) {
            FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
            return
        }
        if (preparedLines.size > 1) {
            FgoLogger.debug(
                tag,
                "AI multi-speaker voice split original=$speaker speakers=${preparedLines.joinToString("|") { it.speaker }}"
            )
        }

        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        val cacheMaterial = preparedLines.joinToString("||") { it.cacheMaterial }
        val requestId = reserveVoiceRequest(
            lineKey = lineKey,
            cacheMaterial = cacheMaterial,
            speaker = speaker
        ) ?: return

        speakMutex.withLock {
            runCatching {
                if (!isLatestVoiceRequest(requestId)) {
                    FgoLogger.debug(tag, "AI voice stale skipped before synthesis: speaker=$speaker")
                    return@runCatching
                }
                val audioFiles = synthesizeVoiceLines(
                    config = AzureSpeechConfig(key = speechKey, region = speechRegion),
                    dialogue = dialogue,
                    lines = preparedLines
                )
                if (!isLatestVoiceRequest(requestId)) {
                    FgoLogger.debug(tag, "AI voice stale skipped after synthesis: speaker=$speaker")
                    return@runCatching
                }
                val playbackStarted = withContext(Dispatchers.Main) {
                    if (!isLatestVoiceRequest(requestId)) {
                        FgoLogger.debug(tag, "AI voice stale skipped before playback: speaker=$speaker")
                        return@withContext false
                    }
                    val started = if (audioFiles.size == 1) {
                        playbackEngine.play(audioFiles.single(), voiceVolumePercent)
                    } else {
                        playbackEngine.playTogether(audioFiles, voiceVolumePercent)
                    }
                    started
                }
                if (playbackStarted) {
                    analyticsScope.launch {
                        appAnalytics.reportVoiceServerUsed(normalizedServer)
                    }
                }
            }.onFailure { e ->
                clearFailedVoiceRequest(requestId, lineKey, cacheMaterial)
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
                    detail = preparedLines.joinToString("|") { "${it.speaker}:${it.profile.profileId}" },
                    voiceType = preparedLines.joinToString(",") { it.profile.description },
                    voiceName = preparedLines.joinToString(",") { it.profile.voiceName },
                    textPreview = dialogue.previewText()
                )
                FgoLogger.warn(tag, "AI voice playback skipped", e)
            }
        }
    }

    suspend fun prepareAzureVoiceTest(speakerName: String): PreparedVoiceTarget {
        val cleanSpeaker = normalizeVisibleSpeakerName(speakerName).ifBlank { TEST_VOICE_SPEAKER_JP }
        // Keep the existing test refresh, but do it before choosing the hint's style list.
        voiceDataUpdateManager.updateIfNeeded(force = true)
        val profile = withContext(Dispatchers.IO) {
            characterVoiceRepository.reload()
            resolveCuratedTestProfile(cleanSpeaker)
        } ?: throw IllegalStateException("Mash voice profile not found in CDN voice data")
        return PreparedVoiceTarget(SettingsRepository.GAME_SERVER_JP, mapOf(cleanSpeaker to profile))
    }

    suspend fun playAzureVoiceTest(
        speakerName: String,
        dialogue: String,
        voiceHint: VoiceLineHint? = null,
        preparedTarget: PreparedVoiceTarget? = null
    ): AzureVoiceTestResult {
        val speechKey = settingsRepository.azureSpeechKey.first().trim()
        if (speechKey.isBlank()) {
            throw IllegalArgumentException("Azure Speech key is blank")
        }

        val cleanSpeaker = normalizeVisibleSpeakerName(speakerName)
            .ifBlank { TEST_VOICE_SPEAKER_JP }
        val cleanDialogue = voiceTextFor(dialogue)
            ?: throw IllegalArgumentException("Test dialogue is blank")

        val target = preparedTarget?.takeIf {
            it.matches(SettingsRepository.GAME_SERVER_JP, listOf(cleanSpeaker))
        } ?: prepareAzureVoiceTest(cleanSpeaker)
        val profile = target.profilesBySpeaker.getValue(cleanSpeaker)
        val voiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        val emotionEnhancementEnabled = settingsRepository.aiVoiceApiHintsEnabled.first()
        val effectiveVoiceHint = voiceHint.takeIf { emotionEnhancementEnabled }
        val expression = voiceExpressionFor(
            profile = profile,
            dialogue = cleanDialogue,
            voiceHint = effectiveVoiceHint,
            aiVoiceSpeedPercent = voiceSpeedPercent,
            emotionEnhancementEnabled = emotionEnhancementEnabled
        )
        logVoiceExpression(
            speaker = cleanSpeaker,
            profile = profile,
            voiceHint = effectiveVoiceHint,
            expression = expression,
            emotionEnhancementEnabled = emotionEnhancementEnabled
        )
        val speechRegion = SettingsRepository.normalizeAzureSpeechRegion(
            settingsRepository.azureSpeechRegion.first()
        )
        val request = VoiceSynthesisRequest(
            speakerName = cleanSpeaker,
            spokenText = cleanDialogue,
            profile = profile,
            styleOverride = expression?.styleOverride,
            rateOverride = expression?.rateOverride,
            pitchOverride = expression?.pitchOverride,
            styleDegree = expression?.styleDegree,
            pauseScale = expression?.pauseScale,
            ssmlModeVersion = expression?.ssmlModeVersion,
            azureSpeechRegion = speechRegion,
            aiVoiceSpeedPercent = voiceSpeedPercent
        )
        val voiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        val audioFile = withContext(Dispatchers.IO) {
            audioCache.cachedFile(request.cacheMaterial()) ?: audioCache.write(
                cacheMaterial = request.cacheMaterial(),
                audio = azureTtsClient.synthesize(
                    config = AzureSpeechConfig(key = speechKey, region = speechRegion),
                    profile = profile,
                    text = cleanDialogue,
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
        val voiceHintApplied = expression?.voiceHintApplied == true
        FgoLogger.info(
            tag,
            "Azure voice test played speaker=$cleanSpeaker voice=${profile.voiceName} hintApplied=$voiceHintApplied"
        )
        return AzureVoiceTestResult(
            speakerName = cleanSpeaker,
            dialogue = cleanDialogue,
            voiceName = profile.voiceName,
            profileId = profile.profileId,
            voiceHintApplied = voiceHintApplied
        )
    }

    private fun reserveVoiceRequest(lineKey: String, cacheMaterial: String, speaker: String): Long? {
        synchronized(voiceRequestLock) {
            if (lineKey == lastRequestedLineKey || cacheMaterial == lastRequestedCacheMaterial) {
                FgoLogger.debug(tag, "AI voice duplicate skipped: speaker=$speaker")
                return null
            }
            latestVoiceRequestId += 1
            lastRequestedLineKey = lineKey
            lastRequestedCacheMaterial = cacheMaterial
            return latestVoiceRequestId
        }
    }

    private fun isLatestVoiceRequest(requestId: Long): Boolean {
        return synchronized(voiceRequestLock) {
            requestId == latestVoiceRequestId
        }
    }

    private fun clearFailedVoiceRequest(requestId: Long, lineKey: String, cacheMaterial: String) {
        synchronized(voiceRequestLock) {
            if (requestId != latestVoiceRequestId) return
            if (lastRequestedLineKey == lineKey) {
                lastRequestedLineKey = null
            }
            if (lastRequestedCacheMaterial == cacheMaterial) {
                lastRequestedCacheMaterial = null
            }
        }
    }

    private suspend fun prepareVoiceLines(
        gameServer: String,
        speakers: List<String>,
        dialogue: String,
        voiceHint: VoiceLineHint?,
        azureSpeechRegion: String,
        aiVoiceSpeedPercent: Int,
        emotionEnhancementEnabled: Boolean,
        readTextSource: String,
        preparedTarget: PreparedVoiceTarget?
    ): List<PreparedVoiceLine> {
        return speakers.mapNotNull { speaker ->
            val profile = preparedTarget?.profilesBySpeaker?.get(speaker) ?: resolveVoiceProfile(
                gameServer = gameServer,
                speaker = speaker,
                dialogue = dialogue,
                allowTemporaryProfile = emotionEnhancementEnabled
            ) ?: run {
                FgoLogger.debug(tag, "No AI voice profile for speaker: $speaker")
                return@mapNotNull null
            }
            FgoLogger.debug(
                tag,
                "AI voice profile speaker=$speaker profile=${profile.profileId} " +
                    "voice=${profile.voiceName} source=$readTextSource"
            )
            val expression = voiceExpressionFor(
                profile = profile,
                dialogue = dialogue,
                voiceHint = voiceHint,
                aiVoiceSpeedPercent = aiVoiceSpeedPercent,
                emotionEnhancementEnabled = emotionEnhancementEnabled
            )
            logVoiceExpression(
                speaker = speaker,
                profile = profile,
                voiceHint = voiceHint,
                expression = expression,
                emotionEnhancementEnabled = emotionEnhancementEnabled
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
                ssmlModeVersion = expression?.ssmlModeVersion,
                azureSpeechRegion = azureSpeechRegion,
                aiVoiceSpeedPercent = aiVoiceSpeedPercent
            )
            PreparedVoiceLine(
                speaker = speaker,
                profile = profile,
                expression = expression,
                cacheMaterial = request.cacheMaterial()
            )
        }
    }

    private suspend fun synthesizeVoiceLines(
        config: AzureSpeechConfig,
        dialogue: String,
        lines: List<PreparedVoiceLine>
    ): List<File> {
        return coroutineScope {
            lines.map { line ->
                async(Dispatchers.IO) {
                    audioCache.cachedFile(line.cacheMaterial) ?: audioCache.write(
                        cacheMaterial = line.cacheMaterial,
                        audio = azureTtsClient.synthesize(
                            config = config,
                            profile = line.profile,
                            text = dialogue,
                            styleOverride = line.expression?.styleOverride,
                            rateOverride = line.expression?.rateOverride,
                            pitchOverride = line.expression?.pitchOverride,
                            styleDegree = line.expression?.styleDegree,
                            pauseScale = line.expression?.pauseScale
                        )
                    )
                }
            }.awaitAll()
        }
    }

    private suspend fun findKnownVoiceProfile(gameServer: String, speaker: String): VoiceProfile? {
        val candidates = voiceSpeakerLookupCandidates(speaker)
        candidates.firstNotNullOfOrNull { candidate ->
            characterVoiceRepository.resolveProfileOrNull(candidate, gameServer)
        }?.let { return it }
        return candidates.firstNotNullOfOrNull { candidate ->
            tempVoiceProfileRepository.resolveProfileOrNull(gameServer, candidate)
        }
    }

    private suspend fun resolveVoiceProfile(
        gameServer: String,
        speaker: String,
        dialogue: String,
        allowTemporaryProfile: Boolean
    ): VoiceProfile? {
        val normalizedServer = SettingsRepository.normalizeGameServer(gameServer)
        findKnownVoiceProfile(normalizedServer, speaker)?.let { return it }
        if (!allowTemporaryProfile) {
            FgoLogger.debug(tag, "Preset-only voice skipped: no saved profile server=$normalizedServer speaker=$speaker")
            return null
        }

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
            findKnownVoiceProfile(normalizedServer, speaker)?.let { return@withLock it }

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
            compactSpeakerKeyText(speaker),
            compactVoiceKeyText(dialogue)
        ).joinToString("|")
    }

    private fun splitVoiceSpeakers(speaker: String): List<String> {
        val speakers = MULTI_SPEAKER_SEPARATOR_REGEX.split(speaker)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(::compactSpeakerKeyText)
        return speakers.takeIf { it.size > 1 } ?: listOf(speaker)
    }

    private fun resolveCuratedTestProfile(speakerName: String): VoiceProfile? {
        val candidates = (
            voiceSpeakerLookupCandidates(speakerName) +
                listOf(
                    TEST_VOICE_SPEAKER_TRADITIONAL,
                    TEST_VOICE_SPEAKER_SIMPLIFIED,
                    TEST_VOICE_SPEAKER_JP
                )
            ).distinctBy(::compactSpeakerKeyText)
        return candidates.firstNotNullOfOrNull { candidate ->
            characterVoiceRepository.resolveProfileOrNull(candidate)
        }
    }

    private fun voiceSpeakerLookupCandidates(speaker: String): List<String> {
        val visibleSpeaker = normalizeVisibleSpeakerName(speaker)
        val strippedSpeaker = TextNormalizer.stripRubyAnnotations(speaker).trim()
        return listOf(visibleSpeaker, strippedSpeaker)
            .filter(String::isNotBlank)
            .distinctBy { candidate -> VoiceNameNormalizer.normalize(candidate) }
    }

    private fun normalizeVisibleSpeakerName(speaker: String): String {
        return TextNormalizer.normalizeForTranslation(speaker).trim()
    }

    private fun compactSpeakerKeyText(text: String): String {
        return compactKeyText(TextNormalizer.normalizeForTranslation(text).trim())
    }

    private fun compactVoiceKeyText(text: String): String {
        return compactKeyText(TextNormalizer.stripRubyAnnotations(text).trim())
    }

    private fun compactKeyText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
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
        voiceHint: VoiceLineHint?,
        aiVoiceSpeedPercent: Int,
        emotionEnhancementEnabled: Boolean
    ): VoiceExpression? {
        if (!VoiceLocaleSupport.isChineseLocale(profile.locale)) {
            return null
        }
        return VoiceEmotionStyle.expressionFor(
            profile = profile,
            text = dialogue,
            voiceHint = voiceHint,
            baseSpeedMultiplier = SettingsRepository.normalizeAiVoiceSpeedPercent(aiVoiceSpeedPercent) / 100.0,
            emotionEnhancementEnabled = emotionEnhancementEnabled
        )
    }

    private fun logVoiceExpression(
        speaker: String,
        profile: VoiceProfile,
        voiceHint: VoiceLineHint?,
        expression: VoiceExpression?,
        emotionEnhancementEnabled: Boolean
    ) {
        val appliedStyle = VoiceEmotionStyle.resolveStyle(profile, expression?.styleOverride).ifBlank { "-" }
        val hintStyles = voiceHint?.styles.orEmpty().ifEmpty { listOf("-") }.joinToString("/")
        val expressionMode = if (emotionEnhancementEnabled) "enhanced_or_local" else "presets_only"
        FgoLogger.debug(
            tag,
            "AI voice expression speaker=$speaker voice=${profile.voiceName} " +
                "profileStyle=${profile.style.ifBlank { "-" }} hintStyles=$hintStyles " +
                "appliedStyle=$appliedStyle rate=${expression?.rateOverride ?: "-"} " +
                "pitch=${expression?.pitchOverride ?: "-"} pause=${expression?.pauseScale ?: "-"} " +
                "hintApplied=${expression?.voiceHintApplied == true} expressionMode=$expressionMode"
        )
    }

    fun stop() {
        playbackEngine.stop()
    }

    private companion object {
        const val TEMP_PROFILE_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
        const val TEXT_PREVIEW_CHARS = 80
        const val TEST_VOICE_SPEAKER_JP = "マシュ"
        const val TEST_VOICE_SPEAKER_SIMPLIFIED = "玛修"
        const val TEST_VOICE_SPEAKER_TRADITIONAL = "瑪修"
        val MULTI_SPEAKER_SEPARATOR_REGEX = Regex("[&/\\uFF06\\uFF0F]")
    }
}
