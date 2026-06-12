package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.localmodel.LocalLlamaTranslator
import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.TermDao
import com.fgogotran.terminology.TermEntity
import com.fgogotran.util.FgoLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.security.MessageDigest
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int = 1024,
    val temperature: Double = 0.3
)

@Serializable
data class ChatChoice(
    val message: ChatMessage
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>
)

data class TranslateResult(
    val translatedText: String,
    val backend: String,
    val cached: Boolean
)

data class SceneTranslateInput(
    val name: String?,
    val dialogue: String?,
    val choices: List<String>
)

data class SceneTranslateResult(
    val name: TranslateResult?,
    val dialogue: TranslateResult?,
    val choices: List<TranslateResult>
)

/**
 * Orchestrates Japanese-to-Chinese translation with cache, glossary injection,
 * and provider-specific API calls.
 */
@Singleton
class Translator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val userProfile: UserProfile,
    private val termDao: TermDao,
    private val promptBuilder: PromptBuilder,
    private val cacheDb: TranslationCacheDb,
    private val translationMemory: TranslationMemory,
    private val localLlamaTranslator: LocalLlamaTranslator
) {
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val cacheDao = cacheDb.cacheDao()
    private val tag = "Translator"
    private val responseJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private var cachedRuntimeConfig: RuntimeConfig? = null
    private var cachedRuntimeConfigAt = 0L
    private var cachedTermRows: List<TermEntity>? = null
    private var cachedTerms: List<TermEntity>? = null
    private var cachedCharacterNames: List<CharacterNameEntity>? = null
    private var cachedTermLookup: Map<String, String>? = null
    private var cachedCharacterNameLookup: Map<String, String>? = null
    private var cachedCharacterNameVariants: List<NormalizedCharacterNameVariant>? = null
    private val memoryCacheLock = Any()
    private val memoryTranslationCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MEMORY_TRANSLATION_CACHE_MAX_ENTRIES
        }
    }

    fun clearGlossaryCache() {
        cachedRuntimeConfig = null
        cachedRuntimeConfigAt = 0L
        cachedTermRows = null
        cachedTerms = null
        cachedCharacterNames = null
        cachedTermLookup = null
        cachedCharacterNameLookup = null
        cachedCharacterNameVariants = null
        synchronized(memoryCacheLock) {
            memoryTranslationCache.clear()
        }
        FgoLogger.info(tag, "Glossary and memory translation cache cleared")
    }

    private data class RuntimeConfig(
        val backend: String,
        val apiKey: String,
        val apiBaseUrl: String,
        val apiModel: String,
        val playerName: String,
        val cacheEnabled: Boolean,
        val glossaryCacheKey: String
    ) {
        val requiresApiKey: Boolean
            get() = SettingsRepository.requiresApiKey(backend)
        val isLocalLlama: Boolean
            get() = backend == SettingsRepository.BACKEND_LOCAL_LLAMA
    }

    private data class CharacterNameVariant(
        val jpName: String,
        val cnName: String
    )

    private data class NormalizedCharacterNameVariant(
        val jpName: String,
        val cnName: String,
        val lookupKey: String,
        val cnLookupKey: String
    )

    companion object {
        private const val RUNTIME_CONFIG_CACHE_TTL_MS = 0L
        private const val MEMORY_TRANSLATION_CACHE_MAX_ENTRIES = 256
        private const val UNTRANSLATED_FALLBACK = ""
        private const val LOCAL_LLAMA_RENDER_FALLBACK = "本地模型未能翻译"
        private val unresolvedPlaceholderPattern =
            Regex("""__FGO(?:TERM|PLAYER)_\d+(?:_PLURAL)?__""")
        private val unresolvedPlayerPluralPattern =
            Regex("""__FGOPLAYER_\d+_PLURAL__""")
        private val unresolvedPlayerPattern =
            Regex("""__FGOPLAYER_\d+__""")
        private val nameSanHonorificPattern =
            Regex("([\\p{IsHan}\\u30A0-\\u30FF\\uFF66-\\uFF9DA-Za-z0-9_・ー〇○-]{1,32})さん")
        private val wrongSanHonorificPattern =
            Regex("([\\p{L}\\p{N}_·・ー〇○-]{1,32})(?:先生|小姐|女士|大人|阁下)")
        private val sanHonorificExceptionPhrases = setOf(
            "皆さん",
            "みなさん",
            "たくさん",
            "お父さん",
            "父さん",
            "お母さん",
            "母さん",
            "お兄さん",
            "兄さん",
            "お姉さん",
            "姉さん",
            "お客さん",
            "おじさん",
            "おばさん",
            "叔父さん",
            "叔母さん"
        )
        private val NAME_PLURAL_ZU_SUFFIXES = listOf("ズ", "ず")
        private val assistantMetaReplyPatterns = listOf(
            Regex("请提供.{0,16}翻译"),
            Regex("请提供.{0,16}原文"),
            Regex("请提供.{0,16}文本"),
            Regex("需要翻译的.{0,16}文本"),
            Regex("Fate/GrandOrder游戏对话文本")
        )
    }

    suspend fun warmUp() {
        try {
            getRuntimeConfig()
            getCachedTerms()
            getCachedTermLookup()
            getCachedCharacterNameVariants()
            getCachedCharacterNameLookup()
            FgoLogger.info(tag, "Translator warm-up complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Translator warm-up failed; manual translation will load caches on demand", e)
        }
    }

    suspend fun renderContextKey(): String {
        val config = getRuntimeConfig()
        return hashText(
            listOf(
                PromptBuilder.PROMPT_VERSION,
                config.backend,
                config.apiBaseUrl,
                config.apiModel,
                config.glossaryCacheKey,
                TextNormalizer.normalizeForTranslation(config.playerName)
            ).joinToString("\u001F")
        )
    }

    suspend fun translate(
        japaneseText: String,
        choiceTexts: List<String> = emptyList(),
        preserveRubyMeaning: Boolean = false,
        allowLocalFailureFallback: Boolean = false
    ): TranslateResult {
        val normalizedText = TextNormalizer.normalizeForTranslation(japaneseText)
        val normalizedChoices = choiceTexts.map(TextNormalizer::normalizeForTranslation)
            .filter { it.isNotBlank() }

        if (normalizedText.isBlank()) {
            return TranslateResult("", "none", true)
        }

        translationMemory.lookupNormalized(normalizedText)?.let {
            FgoLogger.info(tag, "Official CN memory HIT")
            return TranslateResult(sanitizeTranslation(normalizedText, it), "official-cn", true)
        }

        findCharacterNameTranslation(normalizedText)?.let {
            FgoLogger.info(tag, "Character exact HIT")
            return TranslateResult(sanitizeCharacterNameResult(it), "character-db", true)
        }

        findTermTranslation(normalizedText)?.let {
            FgoLogger.info(tag, "Glossary exact HIT")
            return TranslateResult(sanitizeTranslation(normalizedText, it), "glossary", true)
        }

        val config = getRuntimeConfig()
        val backend = config.backend
        val apiKey = config.apiKey
        val playerName = config.playerName
        val cacheEnabled = config.cacheEnabled
        val rubyPolicyKey = if (preserveRubyMeaning) "ruby-meaning-v1" else ""
        val hash = cacheKey(normalizedText, normalizedChoices, config, rubyPolicyKey)

        FgoLogger.debug(tag, "translate: textLen=${normalizedText.length}, choices=${normalizedChoices.size}")

        if (cacheEnabled) {
            lookupCachedTranslation(hash, normalizedText, playerName, "Cache")?.let { cached ->
                return TranslateResult(cached, "cache", true)
            }
        }
        FgoLogger.debug(tag, "Cache miss, hash=${hash.take(8)}...")

        if (config.requiresApiKey && apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured; returning placeholder")
            return TranslateResult(
                "[未配置 API Key]\n请打开设置并输入 API Key。",
                "none",
                false
            )
        }

        val matchedTerms = try {
            val allTerms = getCachedTerms()
            val matches = promptBuilder.extractTermMatches(normalizedText, allTerms)
            FgoLogger.debug(tag, "RAG: matched ${matches.size} of ${allTerms.size} terms")
            matches
        } catch (e: Exception) {
            FgoLogger.warn(tag, "RAG term lookup failed, continuing without glossary", e)
            emptyList()
        }
        val protectedInput = protectText(normalizedText, matchedTerms, playerName)

        val messages = listOf(
            ChatMessage("system", promptBuilder.buildSystemPrompt(matchedTerms, playerName)),
            ChatMessage(
                "user",
                buildSingleUserPrompt(
                    japaneseText = protectedInput.text,
                    choiceTexts = normalizedChoices,
                    preserveRubyMeaning = preserveRubyMeaning
                )
            )
        )

        FgoLogger.info(tag, "Calling $backend API")
        val result = try {
            callTranslationBackend(config, messages)
        } catch (e: Exception) {
            FgoLogger.error(tag, "$backend API call failed: ${e.message}", e)
            return TranslateResult(
                "[翻译失败：${e.message}]\n请检查 API Key 和网络连接。",
                backend,
                false
            )
        }

        var simplifiedResult = sanitizeTranslation(
            normalizedText,
            restoreProtectedTerms(result, protectedInput.terms, playerName)
        )
        if (containsUnresolvedPlaceholder(simplifiedResult) ||
            looksUntranslated(normalizedText, simplifiedResult, playerName)
        ) {
            if (config.isLocalLlama) {
                if (allowLocalFailureFallback) {
                    FgoLogger.warn(
                        tag,
                        "Local llama returned untranslated/invalid text; rendering fallback without strict retry"
                    )
                    return TranslateResult(localFailureFallbackText(config, true), backend, false)
                }
                FgoLogger.warn(tag, "Local llama returned untranslated/invalid text; retrying once with strict prompt")
                val retryResult = retryUntranslatedSingle(
                    config = config,
                    playerName = playerName,
                    normalizedText = normalizedText,
                    normalizedChoices = normalizedChoices,
                    matchedTerms = matchedTerms,
                    protectedInput = protectedInput
                )
                if (retryResult == null) {
                    val fallback = localFailureFallbackText(config, allowLocalFailureFallback)
                    if (fallback.isBlank()) {
                        FgoLogger.warn(tag, "Local llama strict retry failed; skipping unsafe render")
                    } else {
                        FgoLogger.warn(tag, "Local llama strict retry failed; rendering simplified fallback")
                    }
                    return TranslateResult(fallback, backend, false)
                }
                simplifiedResult = retryResult
            } else {
                FgoLogger.warn(tag, "API returned untranslated Japanese; retrying with strict Chinese-only prompt")
                val retryResult = retryUntranslatedSingle(
                    config = config,
                    playerName = playerName,
                    normalizedText = normalizedText,
                    normalizedChoices = normalizedChoices,
                    matchedTerms = matchedTerms,
                    protectedInput = protectedInput
                )
                if (retryResult == null) {
                    FgoLogger.warn(tag, "Retry still looked untranslated; skipping overlay render")
                    return TranslateResult(UNTRANSLATED_FALLBACK, backend, false)
                }
                simplifiedResult = retryResult
            }
        }

        if (cacheEnabled) {
            cacheTranslatedText(hash, japaneseText, normalizedText, simplifiedResult, backend, playerName)
        }

        FgoLogger.info(tag, "Translation complete: backend=$backend, chars=${simplifiedResult.length}")
        return TranslateResult(simplifiedResult, backend, false)
    }

    suspend fun translateBatch(
        japaneseTexts: List<String>,
        allowLocalFailureFallback: Boolean = false
    ): List<TranslateResult> {
        if (japaneseTexts.isEmpty()) return emptyList()

        val normalizedTexts = japaneseTexts.map(TextNormalizer::normalizeForTranslation)
        val config = getRuntimeConfig()
        val backend = config.backend
        val apiKey = config.apiKey
        val playerName = config.playerName
        val cacheEnabled = config.cacheEnabled
        val hashes = normalizedTexts.map { cacheKey(it, emptyList(), config) }
        val results = MutableList<TranslateResult?>(japaneseTexts.size) { null }
        val uncachedIndices = mutableListOf<Int>()

        FgoLogger.debug(
            tag,
            "translateBatch: count=${japaneseTexts.size}, chars=${normalizedTexts.sumOf { it.length }}"
        )

        for (index in japaneseTexts.indices) {
            val normalizedText = normalizedTexts[index]
            if (normalizedText.isBlank()) {
                results[index] = TranslateResult("", "none", true)
                continue
            }

            val officialMemory = translationMemory.lookupNormalized(normalizedText)
            if (officialMemory != null) {
                FgoLogger.info(tag, "Batch official CN memory HIT[$index]")
                results[index] = TranslateResult(sanitizeTranslation(normalizedText, officialMemory), "official-cn", true)
                continue
            }

            val characterTranslation = findCharacterNameTranslation(normalizedText)
            if (characterTranslation != null) {
                FgoLogger.info(tag, "Batch character exact HIT[$index]")
                results[index] = TranslateResult(sanitizeCharacterNameResult(characterTranslation), "character-db", true)
                continue
            }

            val termTranslation = findTermTranslation(normalizedText)
            if (termTranslation != null) {
                FgoLogger.info(tag, "Batch term exact HIT[$index]")
                results[index] = TranslateResult(sanitizeTranslation(normalizedText, termTranslation), "glossary", true)
                continue
            }

            if (cacheEnabled) {
                val cached = lookupCachedTranslation(hashes[index], normalizedText, playerName, "Batch")
                if (cached != null) {
                    results[index] = TranslateResult(cached, "cache", true)
                    continue
                }
            }
            uncachedIndices.add(index)
        }

        if (uncachedIndices.isEmpty()) {
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        if (config.requiresApiKey && apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured; returning placeholders for batch")
            val placeholder = "[未配置 API Key]\n请打开设置并输入 API Key。"
            uncachedIndices.forEach { index ->
                results[index] = TranslateResult(placeholder, "none", false)
            }
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        val uncachedTexts = uncachedIndices.map { normalizedTexts[it] }
        if (config.isLocalLlama) {
            FgoLogger.info(tag, "Local llama batch path: translating ${uncachedIndices.size} item(s) individually")
            for (index in uncachedIndices) {
                results[index] = translate(
                    japaneseTexts[index],
                    allowLocalFailureFallback = allowLocalFailureFallback
                )
            }
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        val matchedTerms = try {
            val allTerms = getCachedTerms()
            val combinedText = uncachedTexts.joinToString("\n")
            val matches = promptBuilder.extractTermMatches(combinedText, allTerms)
            FgoLogger.debug(tag, "Batch RAG: matched ${matches.size} of ${allTerms.size} terms")
            matches
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Batch RAG term lookup failed, continuing without glossary", e)
            emptyList()
        }
        val protectedTexts = uncachedTexts.map { protectText(it, matchedTerms, playerName) }

        val messages = listOf(
            ChatMessage("system", promptBuilder.buildSystemPrompt(matchedTerms, playerName)),
            ChatMessage("user", buildBatchUserPrompt(protectedTexts.map { it.text }))
        )

        FgoLogger.info(tag, "Calling $backend API for batch (${uncachedTexts.size} items)")
        val translatedTexts = try {
            val rawResult = callTranslationBackend(config, messages)
            parseBatchResult(rawResult, uncachedTexts.size)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Batch translation failed, falling back to single calls", e)
            for (index in uncachedIndices) {
                results[index] = translate(
                    japaneseTexts[index],
                    allowLocalFailureFallback = allowLocalFailureFallback
                )
            }
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        for ((batchIndex, originalIndex) in uncachedIndices.withIndex()) {
            val restored = restoreProtectedTerms(
                translatedTexts[batchIndex],
                protectedTexts[batchIndex].terms,
                playerName
            )
            val sanitized = sanitizeTranslation(normalizedTexts[originalIndex], restored)
            val wasUntranslated = containsUnresolvedPlaceholder(sanitized) ||
                    looksUntranslated(normalizedTexts[originalIndex], sanitized, playerName)
            if (wasUntranslated) {
                FgoLogger.warn(tag, "Batch item[$originalIndex] returned untranslated Japanese; retrying single strict path")
                val retryResult = translate(
                    japaneseTexts[originalIndex],
                    allowLocalFailureFallback = allowLocalFailureFallback
                )
                if (retryResult.translatedText.isNotBlank()) {
                    results[originalIndex] = retryResult
                    continue
                }
                FgoLogger.warn(tag, "Batch item[$originalIndex] retry produced no renderable translation")
            }
            val translated = if (wasUntranslated) {
                localFailureFallbackText(config, allowLocalFailureFallback)
            } else {
                sanitized
            }
            results[originalIndex] = TranslateResult(translated, backend, false)
            if (cacheEnabled && !wasUntranslated) {
                cacheTranslatedText(
                    hashes[originalIndex],
                    japaneseTexts[originalIndex],
                    normalizedTexts[originalIndex],
                    translated,
                    backend,
                    playerName
                )
            }
        }

        FgoLogger.info(tag, "Batch translation complete: backend=$backend, items=${uncachedTexts.size}")
        return results.map { it ?: TranslateResult("", "none", true) }
    }

    suspend fun translateScene(input: SceneTranslateInput): SceneTranslateResult {
        val normalizedName = input.name?.let(TextNormalizer::normalizeForTranslation)?.takeIf { it.isNotBlank() }
        val normalizedDialogue = input.dialogue?.let(TextNormalizer::normalizeForTranslation)?.takeIf { it.isNotBlank() }
        val normalizedChoices = input.choices
            .map(TextNormalizer::normalizeForTranslation)
            .map { it.takeIf(String::isNotBlank) }

        val config = getRuntimeConfig()
        val backend = config.backend
        val apiKey = config.apiKey
        val cacheEnabled = config.cacheEnabled
        val playerName = config.playerName

        var nameResult: TranslateResult? = null
        var dialogueResult: TranslateResult? = null
        val choiceResults = MutableList<TranslateResult?>(input.choices.size) { null }
        val nameForLlm = normalizedName?.takeIf { shouldTranslateUnknownNameWithLlm(it, playerName) }

        normalizedName?.let { normalized ->
            findCharacterNameTranslation(normalized, allowOcrWrappedMatch = true)?.let {
                FgoLogger.info(tag, "Character TSV HIT name")
                nameResult = TranslateResult(sanitizeCharacterNameResult(it), "character-db", true)
            } ?: run {
                if (nameForLlm != null) {
                    FgoLogger.info(tag, "Character TSV MISS name; will ask LLM")
                } else {
                    FgoLogger.info(tag, "Character TSV MISS name; skipping player/plain name API")
                }
            }
        }
        normalizedDialogue?.let { normalized ->
            translationMemory.lookupNormalized(normalized)?.let {
                FgoLogger.info(tag, "Official CN memory HIT dialogue")
                dialogueResult = TranslateResult(sanitizeTranslation(normalized, it), "official-cn", true)
            }
            if (dialogueResult == null) {
                findTermTranslation(normalized)?.let {
                    FgoLogger.info(tag, "Term exact HIT dialogue")
                    dialogueResult = TranslateResult(sanitizeTranslation(normalized, it), "glossary", true)
                }
            }
        }
        normalizedChoices.forEachIndexed { index, normalized ->
            if (normalized == null) return@forEachIndexed
            translationMemory.lookupNormalized(normalized)?.let {
                FgoLogger.info(tag, "Official CN memory HIT choice[$index]")
                choiceResults[index] = TranslateResult(sanitizeTranslation(normalized, it), "official-cn", true)
            }
            if (choiceResults[index] == null) {
                findTermTranslation(normalized)?.let {
                    FgoLogger.info(tag, "Term exact HIT choice[$index]")
                    choiceResults[index] = TranslateResult(sanitizeTranslation(normalized, it), "glossary", true)
                }
            }
            if (choiceResults[index] == null) {
                findCharacterNameTranslation(normalized)?.let {
                    FgoLogger.info(tag, "Character exact HIT choice[$index]")
                    choiceResults[index] = TranslateResult(sanitizeCharacterNameResult(it), "character-db", true)
                }
            }
        }

        val nameHash = nameForLlm?.let { cacheKey(it, emptyList(), config) }
        val dialogueHash = normalizedDialogue?.let { cacheKey(it, emptyList(), config) }
        val choiceHashes = normalizedChoices.map { it?.let { text -> cacheKey(text, emptyList(), config) } }

        if (cacheEnabled) {
            if (nameForLlm != null && nameHash != null && nameResult == null) {
                lookupCachedTranslation(nameHash, nameForLlm, playerName, "Scene name")?.let { cached ->
                    val cachedName = sanitizeNameTranslation(nameForLlm, cached)
                    if (isBadLlmNameTranslation(nameForLlm, cachedName, playerName)) {
                        FgoLogger.warn(tag, "Dropping unsafe cached name translation, hash=${nameHash.take(8)}...")
                        removeMemoryCachedTranslation(nameHash)
                        cacheDao.deleteByHash(nameHash)
                    } else {
                        nameResult = TranslateResult(cachedName, "cache", true)
                    }
                }
            }
            if (normalizedDialogue != null && dialogueHash != null && dialogueResult == null) {
                lookupCachedTranslation(dialogueHash, normalizedDialogue, playerName, "Scene dialogue")?.let { cached ->
                    dialogueResult = TranslateResult(cached, "cache", true)
                }
            }
            for (index in normalizedChoices.indices) {
                if (choiceResults[index] != null) continue
                val hash = choiceHashes[index] ?: continue
                val source = normalizedChoices[index] ?: continue
                lookupCachedTranslation(hash, source, playerName, "Scene choice[$index]")?.let { cached ->
                    choiceResults[index] = TranslateResult(cached, "cache", true)
                }
            }
        }

        val needsName = nameForLlm != null && nameResult == null
        val needsDialogue = normalizedDialogue != null && dialogueResult == null
        val neededChoiceIndices = normalizedChoices.indices
            .filter { normalizedChoices[it] != null && choiceResults[it] == null }

        if (!needsName && !needsDialogue && neededChoiceIndices.isEmpty()) {
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        if (needsDialogue && !needsName && neededChoiceIndices.isEmpty()) {
            FgoLogger.info(tag, "Scene fast path: dialogue only")
            dialogueResult = translate(
                input.dialogue.orEmpty(),
                allowLocalFailureFallback = true
            )
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        if (!needsName && !needsDialogue && neededChoiceIndices.isNotEmpty()) {
            FgoLogger.info(tag, "Scene fast path: choices only (${neededChoiceIndices.size})")
            val translatedChoices = translateBatch(
                neededChoiceIndices.map { input.choices[it] },
                allowLocalFailureFallback = true
            )
            translatedChoices.forEachIndexed { batchIndex, result ->
                val choiceIndex = neededChoiceIndices[batchIndex]
                choiceResults[choiceIndex] = result
            }
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        if (config.isLocalLlama) {
            FgoLogger.info(tag, "Local llama scene path: translating fields individually")
            if (needsDialogue) {
                dialogueResult = translate(
                    input.dialogue.orEmpty(),
                    allowLocalFailureFallback = true
                )
            }
            if (needsName) {
                nameResult = validateLlmNameResult(
                    normalizedName = nameForLlm!!,
                    result = translate(input.name.orEmpty()),
                    playerName = playerName
                )
            }
            if (neededChoiceIndices.isNotEmpty()) {
                val translatedChoices = translateBatch(
                    neededChoiceIndices.map { input.choices[it] },
                    allowLocalFailureFallback = true
                )
                translatedChoices.forEachIndexed { batchIndex, result ->
                    val choiceIndex = neededChoiceIndices[batchIndex]
                    choiceResults[choiceIndex] = result
                }
            }
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        if (config.requiresApiKey && apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured; returning placeholders for scene")
            val placeholder = "[未配置 API Key]\n请打开设置并输入 API Key。"
            if (needsName) nameResult = TranslateResult("", "none", false)
            if (needsDialogue) dialogueResult = TranslateResult(placeholder, "none", false)
            neededChoiceIndices.forEach { choiceResults[it] = TranslateResult(placeholder, "none", false) }
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        val uncachedName = if (needsName) nameForLlm else null
        val uncachedDialogue = normalizedDialogue.takeIf { needsDialogue }
        val uncachedChoices = neededChoiceIndices.mapNotNull { normalizedChoices[it] }
        val combinedText = listOfNotNull(uncachedName, uncachedDialogue)
            .plus(uncachedChoices)
            .joinToString("\n")
        val matchedTerms = try {
            val allTerms = getCachedTerms()
            val matches = promptBuilder.extractTermMatches(combinedText, allTerms)
            FgoLogger.debug(tag, "Scene RAG: matched ${matches.size} of ${allTerms.size} terms")
            matches
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Scene RAG term lookup failed, continuing without glossary", e)
            emptyList()
        }
        val protectedName = uncachedName?.let { protectText(it, matchedTerms, playerName) }
        val protectedDialogue = uncachedDialogue?.let { protectText(it, matchedTerms, playerName) }
        val protectedChoices = uncachedChoices.map { protectText(it, matchedTerms, playerName) }

        val messages = listOf(
            ChatMessage("system", promptBuilder.buildSystemPrompt(matchedTerms, playerName)),
            ChatMessage(
                "user",
                buildSceneUserPrompt(
                    protectedName?.text,
                    protectedDialogue?.text,
                    protectedChoices.map { it.text }
                )
            )
        )

        FgoLogger.info(
            tag,
            "Calling $backend API for structured scene (name=$needsName, dialogue=$needsDialogue, choices=${uncachedChoices.size})"
        )
        val translatedScene = try {
            val rawResult = callTranslationBackend(config, messages)
            parseSceneResult(rawResult, needsName, needsDialogue, uncachedChoices.size)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Structured scene translation failed, falling back to one batch call", e)
            val fallbackTexts = mutableListOf<String>()
            var nameFallbackIndex: Int? = null
            var dialogueFallbackIndex: Int? = null
            val choiceFallbackIndices = mutableListOf<Pair<Int, Int>>()
            if (needsName) {
                nameFallbackIndex = fallbackTexts.size
                fallbackTexts += input.name.orEmpty()
            }
            if (needsDialogue) {
                dialogueFallbackIndex = fallbackTexts.size
                fallbackTexts += input.dialogue.orEmpty()
            }
            for (index in neededChoiceIndices) {
                choiceFallbackIndices += fallbackTexts.size to index
                fallbackTexts += input.choices[index]
            }
            val fallbackResults = translateBatch(fallbackTexts)
            nameFallbackIndex?.let { index ->
                fallbackResults.getOrNull(index)?.let { result ->
                    nameResult = validateLlmNameResult(nameForLlm!!, result, playerName)
                }
            }
            dialogueFallbackIndex?.let { index ->
                dialogueResult = fallbackResults.getOrNull(index)
            }
            choiceFallbackIndices.forEach { (fallbackIndex, choiceIndex) ->
                choiceResults[choiceIndex] = fallbackResults.getOrNull(fallbackIndex)
            }
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        if (needsName) {
            val translatedName = translatedScene.name
                ?: throw IllegalStateException("Structured scene response missing parsed name")
            val restoredName = restoreProtectedTerms(
                translatedName,
                protectedName?.terms.orEmpty(),
                playerName
            )
            val sourceName = nameForLlm!!
            val simplifiedName = sanitizeNameTranslation(sourceName, restoredName)
            if (isBadLlmNameTranslation(sourceName, simplifiedName, playerName)) {
                FgoLogger.warn(tag, "Structured scene name returned unsafe/wrong name; skipping name render")
                nameResult = TranslateResult(UNTRANSLATED_FALLBACK, backend, false)
            } else {
                nameResult = TranslateResult(simplifiedName, backend, false)
                if (cacheEnabled) {
                    cacheTranslatedText(nameHash!!, input.name.orEmpty(), sourceName, simplifiedName, backend, playerName)
                }
            }
        }
        if (needsDialogue) {
            val translatedDialogue = translatedScene.dialogue
                ?: throw IllegalStateException("Structured scene response missing parsed dialogue")
            val simplifiedDialogue = sanitizeTranslation(
                normalizedDialogue!!,
                restoreProtectedTerms(
                    translatedDialogue,
                    protectedDialogue?.terms.orEmpty(),
                    playerName
                )
            )
            val dialogueUntranslated = looksUntranslated(normalizedDialogue, simplifiedDialogue, playerName)
                    || containsUnresolvedPlaceholder(simplifiedDialogue)
            if (dialogueUntranslated) {
                FgoLogger.warn(tag, "Structured scene dialogue returned untranslated Japanese")
            }
            val renderedDialogue = if (dialogueUntranslated) UNTRANSLATED_FALLBACK else simplifiedDialogue
            dialogueResult = TranslateResult(renderedDialogue, backend, false)
            if (cacheEnabled && !dialogueUntranslated) {
                cacheTranslatedText(dialogueHash!!, input.dialogue.orEmpty(), normalizedDialogue, simplifiedDialogue, backend, playerName)
            }
        }
        for ((batchIndex, originalIndex) in neededChoiceIndices.withIndex()) {
            val normalizedChoice = normalizedChoices[originalIndex] ?: continue
            val hash = choiceHashes[originalIndex] ?: continue
            val restoredChoice = restoreProtectedTerms(
                translatedScene.choices[batchIndex],
                protectedChoices[batchIndex].terms,
                playerName
            )
            val translatedChoice = sanitizeTranslation(normalizedChoice, restoredChoice)
            if (containsUnresolvedPlaceholder(translatedChoice) ||
                looksUntranslated(normalizedChoice, translatedChoice, playerName)
            ) {
                FgoLogger.warn(tag, "Structured scene choice[$originalIndex] returned untranslated Japanese")
                choiceResults[originalIndex] = TranslateResult(UNTRANSLATED_FALLBACK, backend, false)
                continue
            }
            choiceResults[originalIndex] = TranslateResult(translatedChoice, backend, false)
            if (cacheEnabled) {
                cacheTranslatedText(hash, input.choices[originalIndex], normalizedChoice, translatedChoice, backend, playerName)
            }
        }

        FgoLogger.info(tag, "Structured scene translation complete: backend=$backend")
        return SceneTranslateResult(
            name = nameResult,
            dialogue = dialogueResult,
            choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
        )
    }

    private suspend fun getRuntimeConfig(): RuntimeConfig {
        val now = System.currentTimeMillis()
        cachedRuntimeConfig?.let { cached ->
            if (now - cachedRuntimeConfigAt < RUNTIME_CONFIG_CACHE_TTL_MS) {
                return cached
            }
        }

        val requestedBackend = settingsRepository.translationBackend.first()
        val backend = if (requestedBackend == SettingsRepository.BACKEND_LOCAL_LLAMA &&
            !LocalLlamaTranslator.RUNTIME_AVAILABLE
        ) {
            FgoLogger.warn(tag, "Local llama runtime unavailable; using DeepSeek runtime config")
            SettingsRepository.BACKEND_DEEPSEEK
        } else {
            requestedBackend
        }
        val storedApiModel = settingsRepository.apiModel.first()
        val apiModel = if (requestedBackend != backend) {
            SettingsRepository.defaultApiModel(backend)
        } else {
            storedApiModel.ifBlank { SettingsRepository.defaultApiModel(backend) }
        }
        val loaded = RuntimeConfig(
            backend = backend,
            apiKey = settingsRepository.apiKey.first(),
            apiBaseUrl = settingsRepository.apiBaseUrl.first()
                .ifBlank { SettingsRepository.defaultApiBaseUrl(backend) },
            apiModel = apiModel,
            playerName = userProfile.getPlayerName(),
            cacheEnabled = settingsRepository.cacheEnabled.first(),
            glossaryCacheKey = settingsRepository.dbSha256.first().ifBlank { "bundled-db" }
        )
        cachedRuntimeConfig = loaded
        cachedRuntimeConfigAt = now
        FgoLogger.debug(tag, "Runtime config refreshed")
        return loaded
    }

    private suspend fun getCachedTerms(): List<TermEntity> {
        cachedTerms?.let { return it }
        val loaded = (getCachedCharacterNames().flatMap(::characterTermEntries) + getCachedTermRows())
            .distinctBy { TextNormalizer.normalizeForTranslation(it.jpTerm) }
        cachedTerms = loaded
        if (loaded.isEmpty()) {
            FgoLogger.warn(tag, "Glossary database is empty; RAG and name protection are disabled")
        } else {
            FgoLogger.debug(tag, "Glossary terms cached: ${loaded.size}")
        }
        return loaded
    }

    private suspend fun getCachedTermRows(): List<TermEntity> {
        cachedTermRows?.let { return it }
        val loaded = termDao.getAllTerms()
        cachedTermRows = loaded
        FgoLogger.debug(tag, "Term rows cached: ${loaded.size}")
        return loaded
    }

    private suspend fun getCachedCharacterNames(): List<CharacterNameEntity> {
        cachedCharacterNames?.let { return it }
        val loaded = termDao.getAllCharacterNames()
        cachedCharacterNames = loaded
        FgoLogger.debug(tag, "Character names cached: ${loaded.size}")
        return loaded
    }

    private suspend fun getCachedTermLookup(): Map<String, String> {
        cachedTermLookup?.let { return it }
        val loaded = LinkedHashMap<String, String>()
        getCachedTermRows().forEach { term ->
            val keys = listOf(term.jpTerm) + aliases(term.aliases)
            keys.forEach { key ->
                val normalizedKey = TextNormalizer.normalizeForTranslation(key)
                if (normalizedKey.isNotBlank()) {
                    loaded.putIfAbsent(normalizedKey, term.cnTerm)
                }
            }
        }
        cachedTermLookup = loaded
        FgoLogger.debug(tag, "Term lookup cached: ${loaded.size}")
        return loaded
    }

    private suspend fun getCachedCharacterNameLookup(): Map<String, String> {
        cachedCharacterNameLookup?.let { return it }
        val loaded = LinkedHashMap<String, String>()
        getCachedCharacterNameVariants().forEach { variant ->
            if (variant.lookupKey.isNotBlank()) {
                loaded.putIfAbsent(variant.lookupKey, variant.cnName)
            }
        }
        cachedCharacterNameLookup = loaded
        FgoLogger.debug(tag, "Character name lookup cached: ${loaded.size}")
        return loaded
    }

    private suspend fun getCachedCharacterNameVariants(): List<NormalizedCharacterNameVariant> {
        cachedCharacterNameVariants?.let { return it }
        val loaded = getCachedCharacterNames()
            .flatMap(::characterNameVariants)
            .mapNotNull { variant ->
                val lookupKey = normalizeNameLookup(variant.jpName)
                val cnLookupKey = normalizeNameLookup(variant.cnName)
                if (lookupKey.isBlank() || variant.cnName.isBlank()) {
                    null
                } else {
                    NormalizedCharacterNameVariant(
                        jpName = variant.jpName,
                        cnName = variant.cnName,
                        lookupKey = lookupKey,
                        cnLookupKey = cnLookupKey
                    )
                }
            }
            .distinctBy { it.lookupKey }
        cachedCharacterNameVariants = loaded
        FgoLogger.debug(tag, "Character name variants cached: ${loaded.size}")
        return loaded
    }

    private suspend fun findCharacterNameTranslation(
        normalizedText: String,
        allowOcrWrappedMatch: Boolean = false
    ): String? {
        val lookupCandidates = exactLookupCandidates(normalizedText)
            .map { normalizeNameLookup(TextNormalizer.stripRubyAnnotations(it)) }
            .filter { it.isNotBlank() }
            .distinct()
        val nameLookup = getCachedCharacterNameLookup()
        for (candidate in lookupCandidates) {
            nameLookup[candidate]?.let { return it }
        }

        val lookupText = lookupCandidates.firstOrNull() ?: return null
        val variants = getCachedCharacterNameVariants()

        if (allowOcrWrappedMatch) {
            findOcrWrappedCharacterNameTranslation(lookupText, variants)?.let { return it }
        }

        translateCharacterNameComponents(normalizedText, variants)?.let { return it }

        if (isShortKanaOnlyName(lookupText)) {
            FgoLogger.info(tag, "Character fuzzy skipped for short kana name: $normalizedText")
            return null
        }

        variants.firstOrNull { variant ->
            isLikelyOcrNameMatch(lookupText, variant.lookupKey)
        }
            ?.let {
                FgoLogger.info(tag, "Character fuzzy HIT: $normalizedText -> ${it.jpName}")
                return it.cnName
            }

        return null
    }

    private fun findOcrWrappedCharacterNameTranslation(
        lookupText: String,
        variants: List<NormalizedCharacterNameVariant>
    ): String? {
        return variants.asSequence()
            .filter { variant -> isLikelyOcrWrappedName(lookupText, variant.lookupKey) }
            .maxByOrNull { variant -> variant.lookupKey.length }
            ?.also { variant ->
                FgoLogger.info(tag, "Character OCR-wrapped HIT: $lookupText -> ${variant.jpName}")
            }
            ?.cnName
    }

    private fun isLikelyOcrWrappedName(input: String, candidate: String): Boolean {
        if (candidate.length < 3) return false
        if (input == candidate) return false
        if (input.length !in (candidate.length + 1)..(candidate.length + 3)) return false

        val start = input.indexOf(candidate)
        if (start < 0) return false

        val prefix = input.substring(0, start)
        val suffix = input.substring(start + candidate.length)
        val extra = prefix + suffix
        return extra.length in 1..3 && extra.all { isJapaneseKana(it) || it.isNameOcrNoisePunctuation() }
    }

    private fun translateCharacterNameComponents(
        normalizedText: String,
        variants: List<NormalizedCharacterNameVariant>
    ): String? {
        val parts = splitNameComponents(TextNormalizer.stripRubyAnnotations(normalizedText))
        if (parts.size < 2) return null

        val translatedParts = parts.map { part ->
            val lookupPart = normalizeNameLookup(part)
            variants.firstOrNull { variant -> lookupPart == variant.lookupKey }
                ?.cnName
                ?: return null
        }
        return translatedParts.joinToString("\u00B7")
    }

    private suspend fun findTermTranslation(normalizedText: String): String? {
        val lookupCandidates = exactLookupCandidates(normalizedText)
            .map(TextNormalizer::normalizeForTranslation)
            .filter { it.isNotBlank() }
            .distinct()
        val termLookup = getCachedTermLookup()
        return lookupCandidates.firstNotNullOfOrNull { termLookup[it] }
    }

    private fun exactLookupCandidates(normalizedText: String): List<String> {
        return listOf(
            normalizedText,
            TextNormalizer.stripRubyAnnotations(normalizedText)
        )
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun aliases(rawAliases: String?): List<String> {
        return rawAliases.orEmpty()
            .trim('[', ']')
            .split(',')
            .map { it.trim('"', ' ', '\t', '\r', '\n') }
            .filter { it.isNotBlank() }
    }

    private fun characterTermEntries(character: CharacterNameEntity): List<TermEntity> {
        return buildList {
            add(
                TermEntity(
                    jpTerm = character.jpName,
                    cnTerm = character.cnName,
                    category = "character",
                    aliases = character.aliases
                )
            )
            characterNameComponents(character.jpName, character.cnName).forEach { variant ->
                add(
                    TermEntity(
                        jpTerm = variant.jpName,
                        cnTerm = variant.cnName,
                        category = "character_part",
                        aliases = "[]"
                    )
                )
            }
        }.distinctBy { normalizeNameLookup(it.jpTerm) }
    }

    private fun characterNameVariants(character: CharacterNameEntity): List<CharacterNameVariant> {
        return buildList {
            add(CharacterNameVariant(character.jpName, character.cnName))
            addAll(characterNameComponents(character.jpName, character.cnName))
            aliases(character.aliases).forEach { alias ->
                add(CharacterNameVariant(alias, character.cnName))
            }
        }
            .filter { it.jpName.isNotBlank() && it.cnName.isNotBlank() }
            .distinctBy { normalizeNameLookup(it.jpName) }
    }

    private fun characterNameComponents(jpName: String, cnName: String): List<CharacterNameVariant> {
        val jpParts = splitNameComponents(jpName)
        val cnParts = splitNameComponents(cnName)
        if (jpParts.size < 2 || jpParts.size != cnParts.size) return emptyList()
        return jpParts.zip(cnParts)
            .mapNotNull { (jpPart, cnPart) ->
                val jpKey = normalizeNameLookup(jpPart)
                when {
                    jpKey.length < 2 -> null
                    !containsJapaneseScript(jpPart) -> null
                    cnPart.isBlank() -> null
                    else -> CharacterNameVariant(jpPart, cnPart)
                }
            }
            .distinctBy { normalizeNameLookup(it.jpName) }
    }

    private fun splitNameComponents(text: String): List<String> {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .split(Regex("""[\u30FB\uFF65\u00B7\uFF0F/\uFF06&\uFF1D=\s]+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun containsJapaneseScript(text: String): Boolean {
        return text.any { char ->
            char in '\u3040'..'\u30FF' ||
                char in '\uFF66'..'\uFF9D' ||
                char in '\u3400'..'\u9FFF'
        }
    }

    private fun characterLookupKeys(character: CharacterNameEntity): List<String> {
        return characterNameVariants(character)
            .map { it.jpName }
            .map(::normalizeNameLookup)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizeNameLookup(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("""[\s　]+"""), "")
            .filterNot { it.isNameLookupPunctuation() }
            .replace('一', 'ー')
            .replace('二', 'ニ')
            .replace('工', 'エ')
            .replace('－', 'ー')
            .replace('-', 'ー')
            .replace('ァ', 'ア')
            .replace('ィ', 'イ')
            .replace('ゥ', 'ウ')
            .replace('ェ', 'エ')
            .replace('ォ', 'オ')
            .replace('ャ', 'ヤ')
            .replace('ュ', 'ユ')
            .replace('ョ', 'ヨ')
    }

    private fun sanitizeCharacterNameResult(name: String): String {
        return toSimplifiedChinese(name)
            .trim()
            .trimEnd { it.isNameTrailingPunctuation() }
    }

    private fun shouldTranslateUnknownNameWithLlm(normalizedName: String, playerName: String): Boolean {
        if (normalizedName.isBlank()) return false
        val normalizedPlayerName = TextNormalizer.normalizeForTranslation(playerName)
        if (normalizedPlayerName.isNotBlank() &&
            normalizeNameLookup(normalizedName) == normalizeNameLookup(normalizedPlayerName)
        ) {
            return false
        }
        return true
    }

    private suspend fun validateLlmNameResult(
        normalizedName: String,
        result: TranslateResult,
        playerName: String
    ): TranslateResult {
        val simplifiedName = sanitizeNameTranslation(normalizedName, result.translatedText)
        return if (isBadLlmNameTranslation(normalizedName, simplifiedName, playerName)) {
            FgoLogger.warn(tag, "LLM name fallback returned unsafe/wrong name; skipping name render")
            result.copy(translatedText = UNTRANSLATED_FALLBACK)
        } else {
            result.copy(translatedText = simplifiedName)
        }
    }

    private fun sanitizeNameTranslation(sourceText: String, translatedText: String): String {
        return sanitizeTranslation(sourceText, translatedText)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .trimEnd { it.isNameTrailingPunctuation() }
    }

    private suspend fun isBadLlmNameTranslation(
        sourceText: String,
        translatedText: String,
        playerName: String
    ): Boolean {
        val translated = translatedText.trim()
        if (translated.isBlank()) return true
        if (translated == LOCAL_LLAMA_RENDER_FALLBACK) return true
        if (containsUnresolvedPlaceholder(translated)) return true
        if (translated.length > 32) return true
        if (translated.any(::isJapaneseKana)) return true
        if (translated.any { it in setOf('\n', '\r', '。', '！', '？', '!', '?') }) return true
        if (normalizeNameLookup(sourceText) == normalizeNameLookup(translated)) return true

        val normalizedPlayerName = TextNormalizer.normalizeForTranslation(playerName)
        if (normalizedPlayerName.isNotBlank() &&
            normalizeNameLookup(sourceText) == normalizeNameLookup(normalizedPlayerName)
        ) {
            return true
        }

        return isKnownCharacterTranslationForDifferentName(sourceText, translated)
    }

    private suspend fun isKnownCharacterTranslationForDifferentName(
        sourceText: String,
        translatedText: String
    ): Boolean {
        val translatedKey = normalizeNameLookup(translatedText)
        if (translatedKey.isBlank()) return false
        val sourceKey = normalizeNameLookup(sourceText)
        val matchingTranslatedNames = getCachedCharacterNameVariants()
            .filter { variant -> variant.cnLookupKey == translatedKey }
        return matchingTranslatedNames.isNotEmpty() &&
            matchingTranslatedNames.none { variant -> variant.lookupKey == sourceKey }
    }

    private fun isShortKanaOnlyName(lookupText: String): Boolean {
        return lookupText.length in 2..4 && lookupText.all(::isJapaneseKana)
    }

    private fun Char.isNameLookupPunctuation(): Boolean {
        return this in setOf(
            '・', '･', '·', '•', '.', '．', '。', '、', ',', '，',
            '!', '！', '?', '？', ':', '：', ';', '；',
            '(', ')', '（', '）', '[', ']', '［', '］',
            '{', '}', '｛', '｝', '「', '」', '『', '』',
            '<', '>', '＜', '＞', '《', '》'
        )
    }

    private fun Char.isNameOcrNoisePunctuation(): Boolean {
        return isNameLookupPunctuation() || this in setOf('(', ')', '（', '）')
    }

    private fun Char.isNameTrailingPunctuation(): Boolean {
        return this in setOf('。', '.', '．', '、', ',', '，', '!', '！', '?', '？')
    }

    private fun isLikelyOcrNameMatch(input: String, candidate: String): Boolean {
        if (input.length !in 3..8 || candidate.length !in 3..8) return false
        if (kotlin.math.abs(input.length - candidate.length) > 1) return false
        if (input.firstOrNull() != candidate.firstOrNull()) return false
        return editDistanceAtMostOne(input, candidate)
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false

        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            edits++
            if (edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    private suspend fun lookupCachedTranslation(
        hash: String,
        sourceText: String,
        playerName: String,
        label: String
    ): String? {
        getMemoryCachedTranslation(hash)?.let { cached ->
            validateCachedTranslation(hash, sourceText, cached, playerName)?.let { validCached ->
                FgoLogger.info(tag, "$label memory HIT, hash=${hash.take(8)}...")
                return validCached
            }
        }

        val cached = cacheDao.getCached(hash) ?: return null
        return validateCachedTranslation(hash, sourceText, cached, playerName)?.also { validCached ->
            putMemoryCachedTranslation(hash, validCached)
            FgoLogger.info(tag, "$label cache HIT, hash=${hash.take(8)}...")
        }
    }

    private fun getMemoryCachedTranslation(hash: String): String? {
        return synchronized(memoryCacheLock) {
            memoryTranslationCache[hash]
        }
    }

    private fun putMemoryCachedTranslation(hash: String, translatedText: String) {
        if (translatedText.isBlank()) return
        synchronized(memoryCacheLock) {
            memoryTranslationCache[hash] = translatedText
        }
    }

    private fun removeMemoryCachedTranslation(hash: String) {
        synchronized(memoryCacheLock) {
            memoryTranslationCache.remove(hash)
        }
    }

    private suspend fun validateCachedTranslation(
        hash: String,
        sourceText: String,
        cachedText: String,
        playerName: String
    ): String? {
        val simplified = sanitizeTranslation(sourceText, cachedText)
        if (simplified.isBlank()) {
            FgoLogger.warn(tag, "Dropping blank or non-translation cache entry, hash=${hash.take(8)}...")
            removeMemoryCachedTranslation(hash)
            cacheDao.deleteByHash(hash)
            return null
        }
        if (containsUnresolvedPlaceholder(simplified)) {
            FgoLogger.warn(tag, "Dropping cached translation with unresolved placeholder, hash=${hash.take(8)}...")
            removeMemoryCachedTranslation(hash)
            cacheDao.deleteByHash(hash)
            return null
        }
        if (!looksUntranslated(sourceText, simplified, playerName)) {
            return simplified
        }

        FgoLogger.warn(tag, "Dropping untranslated cache entry, hash=${hash.take(8)}...")
        removeMemoryCachedTranslation(hash)
        cacheDao.deleteByHash(hash)
        return null
    }

    private fun looksUntranslated(
        sourceText: String,
        translatedText: String,
        playerName: String = ""
    ): Boolean {
        val source = TextNormalizer.normalizeForTranslation(sourceText)
        val translated = TextNormalizer.normalizeForTranslation(translatedText)
        if (source.isBlank() || translated.isBlank()) return false
        val allowedFragments = allowedJapaneseFragments(source, playerName)
        val sourceForCheck = removeAllowedJapaneseFragments(source, allowedFragments)
        val translatedForCheck = removeAllowedJapaneseFragments(translated, allowedFragments)
        if (sourceForCheck.isBlank() && translatedForCheck.isBlank()) return false
        if (sourceForCheck == translatedForCheck && sourceForCheck.any(::isJapaneseKana)) return true
        val kanaCount = translatedForCheck.count(::isJapaneseKana)
        if (kanaCount > 0) return true
        val sourceKanaCount = sourceForCheck.count(::isJapaneseKana)
        return sourceKanaCount >= 2 && translatedForCheck.contains(sourceForCheck)
    }

    private fun allowedJapaneseFragments(sourceText: String, playerName: String): List<String> {
        val normalizedPlayerName = TextNormalizer.normalizeForTranslation(playerName)
        if (normalizedPlayerName.isBlank()) return emptyList()
        if (!normalizedPlayerName.any(::isJapaneseKana)) return emptyList()
        if (!sourceText.contains(normalizedPlayerName)) return emptyList()
        return listOf(normalizedPlayerName)
    }

    private fun removeAllowedJapaneseFragments(text: String, allowedFragments: List<String>): String {
        var result = text
        for (fragment in allowedFragments.sortedByDescending { it.length }) {
            result = result.replace(fragment, "")
        }
        return result.trim()
    }

    private fun isJapaneseKana(char: Char): Boolean {
        return char in '\u3040'..'\u30FF' || char in '\uFF66'..'\uFF9D'
    }

    private fun sanitizeTranslation(sourceText: String, translatedText: String): String {
        val simplified = stripEdgeKanaLeak(
            cleanReturnedRubyMarkup(toSimplifiedChinese(translatedText))
        )
        val honorificAdjusted = applySanHonorificPolicy(sourceText, simplified)
        if (isAssistantMetaReply(honorificAdjusted)) {
            FgoLogger.warn(tag, "Dropping assistant meta reply instead of rendering translation")
            return ""
        }
        return preserveSourcePunctuation(sourceText, honorificAdjusted)
    }

    private fun isAssistantMetaReply(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() || it == '，' || it == ',' || it == '。' }
        if (compact.length < 12) return false
        return assistantMetaReplyPatterns.any { it.containsMatchIn(compact) }
    }

    private fun applySanHonorificPolicy(sourceText: String, translatedText: String): String {
        if (!sourceContainsNameSanHonorific(sourceText)) return translatedText
        return wrongSanHonorificPattern.replace(translatedText) { match ->
            "${match.groupValues[1]}桑"
        }
    }

    private fun sourceContainsNameSanHonorific(sourceText: String): Boolean {
        val normalized = Normalizer.normalize(sourceText, Normalizer.Form.NFKC)
        return nameSanHonorificPattern.findAll(normalized).any { match ->
            val contextStart = (match.range.first - 2).coerceAtLeast(0)
            val context = normalized.substring(contextStart, match.range.last + 1)
            sanHonorificExceptionPhrases.none { exception ->
                match.value == exception || context.endsWith(exception)
            }
        }
    }

    private fun stripEdgeKanaLeak(text: String): String {
        return text
            .replace(Regex("""(?m)^([\s　]*)[\u3040-\u30FF\uFF66-\uFF9D](?=[\u3400-\u9FFF])""")) {
                it.groupValues[1]
            }
            .replace(Regex("""(?m)(?<=[\u3400-\u9FFF])[\u3040-\u30FF\uFF66-\uFF9D]([\s　]*)$""")) {
                it.groupValues[1]
            }
    }

    private fun cleanReturnedRubyMarkup(text: String): String {
        return Regex("""([^《》\s]{1,24})《([^》]{1,32})》""").replace(text) { match ->
            val base = match.groupValues[1]
            val reading = match.groupValues[2].trim()
            when {
                reading.isBlank() -> base
                reading.any { it in '\u3040'..'\u30ff' } -> base
                else -> "$base（$reading）"
            }
        }
    }

    private data class ProtectedText(
        val text: String,
        val terms: List<TermProtection>
    )

    private data class TermProtection(
        val token: String,
        val officialText: String,
        val pluralToken: String? = null,
        val pluralOfficialText: String? = null
    )

    private fun protectText(
        sourceText: String,
        matchedTerms: List<TermEntity>,
        playerName: String
    ): ProtectedText {
        val playerProtected = protectPlayerName(sourceText, playerName)
        val termProtected = protectOfficialTerms(playerProtected.text, matchedTerms)
        return ProtectedText(
            text = termProtected.text,
            terms = playerProtected.terms + termProtected.terms
        )
    }

    private fun protectPlayerName(sourceText: String, playerName: String): ProtectedText {
        val normalizedPlayerName = TextNormalizer.normalizeForTranslation(playerName)
        if (sourceText.isBlank() || normalizedPlayerName.length < 2) {
            return ProtectedText(sourceText, emptyList())
        }

        val token = "__FGOPLAYER_1__"
        val pluralToken = "__FGOPLAYER_1_PLURAL__"
        val pluralProtectedText = replaceTermPluralCandidate(sourceText, normalizedPlayerName, pluralToken)
        val protectedText = replaceTermCandidate(pluralProtectedText, normalizedPlayerName, token)
        return if (protectedText != sourceText) {
            FgoLogger.debug(tag, "Protected player name as $token")
            ProtectedText(
                text = protectedText,
                terms = listOf(
                    TermProtection(
                        token = token,
                        officialText = normalizedPlayerName,
                        pluralToken = pluralToken.takeIf { pluralProtectedText != sourceText },
                        pluralOfficialText = pluralNameText(normalizedPlayerName)
                            .takeIf { pluralProtectedText != sourceText }
                    )
                )
            )
        } else {
            ProtectedText(sourceText, emptyList())
        }
    }

    private fun protectOfficialTerms(sourceText: String, matchedTerms: List<TermEntity>): ProtectedText {
        if (sourceText.isBlank() || matchedTerms.isEmpty()) {
            return ProtectedText(sourceText, emptyList())
        }

        var protectedText = sourceText
        val protections = mutableListOf<TermProtection>()
        var tokenIndex = 1

        for (term in matchedTerms.distinctBy { it.jpTerm }) {
            if (term.cnTerm.isBlank()) continue

            val termIndex = tokenIndex++
            val token = "__FGOTERM_${termIndex}__"
            val pluralToken = "__FGOTERM_${termIndex}_PLURAL__"
            var matched = false
            var pluralMatched = false
            for (candidate in termProtectionCandidates(term)) {
                val before = protectedText
                val pluralBefore = protectedText
                protectedText = replaceTermPluralCandidate(
                    protectedText,
                    candidate,
                    pluralToken
                )
                pluralMatched = pluralMatched || protectedText != pluralBefore
                protectedText = replaceTermCandidate(protectedText, candidate, token)
                matched = matched || protectedText != before
            }

            if (matched) {
                val officialText = toSimplifiedChinese(term.cnTerm)
                protections += TermProtection(
                    token = token,
                    officialText = officialText,
                    pluralToken = pluralToken.takeIf { pluralMatched },
                    pluralOfficialText = pluralNameText(officialText).takeIf { pluralMatched }
                )
                FgoLogger.debug(tag, "Protected DB term ${term.jpTerm} -> ${term.cnTerm} as $token")
            } else {
                tokenIndex--
            }
        }

        return ProtectedText(protectedText, protections)
    }

    private fun termProtectionCandidates(term: TermEntity): List<String> {
        return buildList {
            add(term.jpTerm)
            term.aliases.orEmpty()
                .split(',', '\n')
                .map { it.trim('"', '\'', '[', ']', ' ', '\t', '\r') }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }
            .map { TextNormalizer.normalizeForTranslation(it) }
            .filter { it.length >= 2 }
            .distinct()
            .sortedByDescending { it.length }
    }

    private fun replaceTermCandidate(text: String, candidate: String, token: String): String {
        val exact = if (candidate.any { it.isAsciiLetter() }) {
            text.replace(candidate, token, ignoreCase = true)
        } else {
            text.replace(candidate, token)
        }
        if (exact != text) return exact

        return replaceNormalizedTermCandidate(text, candidate, token)
    }

    private fun replaceTermPluralCandidate(text: String, candidate: String, token: String): String {
        var current = text
        for (suffix in NAME_PLURAL_ZU_SUFFIXES) {
            current = replaceTermCandidate(current, candidate + suffix, token)
        }
        return current
    }

    private fun replaceNormalizedTermCandidate(text: String, candidate: String, token: String): String {
        val normalizedCandidate = normalizeForTermProtection(candidate)
        if (normalizedCandidate.length < 2) return text

        var current = text
        while (true) {
            val replacement = replaceFirstNormalizedTermCandidate(current, normalizedCandidate, token)
            if (replacement == current) return current
            current = replacement
        }
    }

    private fun replaceFirstNormalizedTermCandidate(
        text: String,
        normalizedCandidate: String,
        token: String
    ): String {
        val normalizedText = StringBuilder()
        val sourceIndices = mutableListOf<Int>()
        for (index in text.indices) {
            val normalizedChar = normalizeForTermProtection(text[index].toString())
            for (char in normalizedChar) {
                normalizedText.append(char)
                sourceIndices += index
            }
        }

        val matchStart = normalizedText.toString().indexOf(
            normalizedCandidate,
            ignoreCase = normalizedCandidate.any { it.isAsciiLetter() }
        )
        if (matchStart < 0) return text

        val matchEnd = matchStart + normalizedCandidate.length - 1
        val sourceStart = sourceIndices[matchStart]
        val sourceEndExclusive = sourceIndices[matchEnd] + 1
        return text.substring(0, sourceStart) + token + text.substring(sourceEndExclusive)
    }

    private fun normalizeForTermProtection(text: String): String {
        return normalizeOcrTermGlyphs(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .filterNot { it.isTermProtectionSeparator() }
    }

    private fun normalizeOcrTermGlyphs(text: String): String {
        return text
            .replace('一', 'ー')
    }

    private fun Char.isTermProtectionSeparator(): Boolean {
        return isWhitespace() || this in setOf(
            '・', '･', '·', '•', '.', ',', '，', '、', '。', '!',
            '?', '！', '？', ':', '：', ';', '；', '"', '\'',
            '“', '”', '‘', '’', '「', '」', '『', '』', '(',
            ')', '（', '）', '[', ']', '【', '】', '{', '}',
            '〈', '〉', '《', '》', '<', '>', '/', '\\', '|'
        )
    }

    private fun restoreProtectedTerms(
        translatedText: String,
        protections: List<TermProtection>,
        playerName: String = ""
    ): String {
        var restored = translatedText
        for (protection in protections) {
            val pluralToken = protection.pluralToken ?: continue
            val pluralOfficialText = protection.pluralOfficialText ?: continue
            restored = restored.replace(pluralToken, pluralOfficialText)
        }
        for (protection in protections) {
            restored = restored.replace(protection.token, protection.officialText)
        }

        val normalizedPlayerName = TextNormalizer.normalizeForTranslation(playerName)
        if (normalizedPlayerName.isNotBlank()) {
            restored = unresolvedPlayerPluralPattern.replace(restored, pluralNameText(normalizedPlayerName))
            restored = unresolvedPlayerPattern.replace(restored, normalizedPlayerName)
        }

        for (protection in protections) {
            val unresolvedToken = listOfNotNull(protection.token, protection.pluralToken)
                .firstOrNull { restored.contains(it) }
            if (unresolvedToken != null) {
                FgoLogger.warn(tag, "LLM returned unresolved terminology token $unresolvedToken")
            }
        }
        return restored
    }

    private fun containsUnresolvedPlaceholder(text: String): Boolean {
        return unresolvedPlaceholderPattern.containsMatchIn(text)
    }

    private fun pluralNameText(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return trimmed
        return if (trimmed.endsWith("们") || trimmed.endsWith("組") || trimmed.endsWith("组") ||
            trimmed.endsWith("隊") || trimmed.endsWith("队")
        ) {
            trimmed
        } else {
            "${trimmed}们"
        }
    }

    private fun Char.isAsciiLetter(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z'
    }

    private fun preserveSourcePunctuation(sourceText: String, translatedText: String): String {
        var result = translatedText.trimEnd()
        val source = sourceText.trimEnd()

        val sourceEllipsis = trailingEllipsis(source)
        if (sourceEllipsis != null && trailingEllipsis(result) == null) {
            result += sourceEllipsis
        }

        val sourceTail = source.takeLastWhile { it.isPreservedTrailingSymbol() }
        if (sourceTail.isNotBlank()) {
            for (symbol in sourceTail) {
                if (symbol !in result.takeLast(sourceTail.length + 2)) {
                    result += symbol
                }
            }
        }
        return result
    }

    private fun trailingEllipsis(text: String): String? {
        val trimmed = text.trimEnd()
        return when {
            trimmed.endsWith("……") -> "……"
            trimmed.endsWith("...") -> "……"
            trimmed.endsWith("…") -> "……"
            trimmed.endsWith("・・・") -> "……"
            else -> null
        }
    }

    private fun Char.isPreservedTrailingSymbol(): Boolean {
        return this in setOf(
            '。', '、', '，', ',', '.', '！', '!', '？', '?', '…', '・',
            '—', '-', '－', '〜', '~', '」', '』', '）', ')', '】', ']'
        )
    }

    private fun toSimplifiedChinese(text: String): String {
        return buildString(text.length) {
            for (char in text) {
                append(traditionalToSimplified[char] ?: char)
            }
        }
    }

    private val traditionalToSimplified = mapOf(
        '萬' to '万', '與' to '与', '專' to '专', '業' to '业', '東' to '东',
        '絲' to '丝', '丟' to '丢', '兩' to '两', '嚴' to '严', '喪' to '丧',
        '個' to '个', '豐' to '丰', '臨' to '临', '為' to '为', '麗' to '丽',
        '舉' to '举', '麼' to '么', '義' to '义', '烏' to '乌', '樂' to '乐',
        '喬' to '乔', '習' to '习', '鄉' to '乡', '書' to '书', '買' to '买',
        '亂' to '乱', '爭' to '争', '於' to '于', '虧' to '亏', '雲' to '云',
        '亞' to '亚', '產' to '产', '畝' to '亩', '親' to '亲', '褻' to '亵',
        '嚲' to '亸', '億' to '亿', '僅' to '仅', '僕' to '仆', '從' to '从',
        '侖' to '仑', '倉' to '仓', '儀' to '仪', '們' to '们', '價' to '价',
        '眾' to '众', '優' to '优', '會' to '会', '傘' to '伞', '偉' to '伟',
        '傳' to '传', '傷' to '伤', '倫' to '伦', '偽' to '伪', '體' to '体',
        '餘' to '余', '傭' to '佣', '僉' to '佥', '俠' to '侠', '侶' to '侣',
        '僥' to '侥', '偵' to '侦', '側' to '侧', '僑' to '侨', '儈' to '侩',
        '儂' to '侬', '俁' to '俣', '倆' to '俩', '儔' to '俦', '儼' to '俨',
        '倀' to '伥', '倖' to '幸', '傾' to '倾', '偵' to '侦', '償' to '偿',
        '兒' to '儿', '兌' to '兑', '黨' to '党', '蘭' to '兰', '關' to '关',
        '興' to '兴', '養' to '养', '獸' to '兽', '內' to '内', '岡' to '冈',
        '冊' to '册', '寫' to '写', '軍' to '军', '農' to '农', '馮' to '冯',
        '衝' to '冲', '決' to '决', '況' to '况', '凍' to '冻', '淨' to '净',
        '淒' to '凄', '準' to '准', '涼' to '凉', '減' to '减', '湊' to '凑',
        '凜' to '凛', '幾' to '几', '鳳' to '凤', '憑' to '凭', '凱' to '凯',
        '擊' to '击', '鑿' to '凿', '劃' to '划', '劉' to '刘', '則' to '则',
        '剛' to '刚', '創' to '创', '刪' to '删', '別' to '别', '剎' to '刹',
        '劑' to '剂', '剮' to '剐', '劍' to '剑', '劇' to '剧', '勸' to '劝',
        '辦' to '办', '務' to '务', '動' to '动', '勵' to '励', '勁' to '劲',
        '勞' to '劳', '勢' to '势', '勳' to '勋', '勝' to '胜', '區' to '区',
        '醫' to '医', '華' to '华', '協' to '协', '單' to '单', '賣' to '卖',
        '盧' to '卢', '衛' to '卫', '卻' to '却', '廠' to '厂', '廳' to '厅',
        '歷' to '历', '厲' to '厉', '壓' to '压', '厭' to '厌', '廁' to '厕',
        '廚' to '厨', '廄' to '厩', '廈' to '厦', '縣' to '县', '參' to '参',
        '雙' to '双', '發' to '发', '變' to '变', '敘' to '叙', '葉' to '叶',
        '號' to '号', '嘆' to '叹', '嘰' to '叽', '嚇' to '吓', '嗎' to '吗',
        '啟' to '启', '吳' to '吴', '吶' to '呐', '員' to '员', '聽' to '听',
        '唄' to '呗', '問' to '问', '啞' to '哑', '喚' to '唤', '喪' to '丧',
        '喬' to '乔', '單' to '单', '喲' to '哟', '嘔' to '呕', '嘖' to '啧',
        '嘗' to '尝', '嘮' to '唠', '嘯' to '啸', '囂' to '嚣', '團' to '团',
        '園' to '园', '圓' to '圆', '圖' to '图', '國' to '国', '圍' to '围',
        '聖' to '圣', '場' to '场', '壞' to '坏', '塊' to '块', '堅' to '坚',
        '壇' to '坛', '壩' to '坝', '塢' to '坞', '墳' to '坟', '墜' to '坠',
        '墮' to '堕', '墻' to '墙', '壯' to '壮', '聲' to '声', '殼' to '壳',
        '壺' to '壶', '處' to '处', '備' to '备', '複' to '复', '夠' to '够',
        '頭' to '头', '誇' to '夸', '夾' to '夹', '奪' to '夺', '奮' to '奋',
        '奧' to '奥', '姦' to '奸', '婦' to '妇', '媽' to '妈', '嫵' to '妩',
        '妝' to '妆', '姍' to '姗', '薑' to '姜', '娛' to '娱', '婁' to '娄',
        '嬌' to '娇', '孫' to '孙', '學' to '学', '寧' to '宁', '寶' to '宝',
        '實' to '实', '寵' to '宠', '審' to '审', '憲' to '宪', '宮' to '宫',
        '寬' to '宽', '賓' to '宾', '寢' to '寝', '對' to '对', '尋' to '寻',
        '導' to '导', '將' to '将', '爾' to '尔', '塵' to '尘', '嘗' to '尝',
        '堯' to '尧', '屍' to '尸', '盡' to '尽', '層' to '层', '屆' to '届',
        '屬' to '属', '歲' to '岁', '島' to '岛', '峽' to '峡', '崗' to '岗',
        '嶺' to '岭', '嶽' to '岳', '巋' to '岿', '巔' to '巅', '幣' to '币',
        '帥' to '帅', '師' to '师', '帳' to '帐', '帶' to '带', '幀' to '帧',
        '幫' to '帮', '幹' to '干', '庫' to '库', '廟' to '庙', '廢' to '废',
        '廣' to '广', '慶' to '庆', '廬' to '庐', '應' to '应', '廟' to '庙',
        '開' to '开', '異' to '异', '棄' to '弃', '張' to '张', '彌' to '弥',
        '彎' to '弯', '彈' to '弹', '強' to '强', '歸' to '归', '當' to '当',
        '錄' to '录', '彥' to '彦', '徹' to '彻', '徑' to '径', '後' to '后',
        '從' to '从', '徠' to '徕', '復' to '复', '徵' to '征', '德' to '德',
        '憶' to '忆', '懺' to '忏', '憂' to '忧', '懷' to '怀', '態' to '态',
        '慫' to '怂', '憐' to '怜', '總' to '总', '戀' to '恋', '恆' to '恒',
        '懇' to '恳', '惡' to '恶', '惱' to '恼', '悅' to '悦', '懸' to '悬',
        '驚' to '惊', '懼' to '惧', '慘' to '惨', '懲' to '惩', '憊' to '惫',
        '慚' to '惭', '慣' to '惯', '慟' to '恸', '憤' to '愤', '願' to '愿',
        '戲' to '戏', '戰' to '战', '戶' to '户', '撲' to '扑', '執' to '执',
        '擴' to '扩', '掃' to '扫', '揚' to '扬', '擾' to '扰', '撫' to '抚',
        '拋' to '抛', '摶' to '抟', '搶' to '抢', '護' to '护', '報' to '报',
        '擔' to '担', '擬' to '拟', '攏' to '拢', '揀' to '拣', '擁' to '拥',
        '攔' to '拦', '擰' to '拧', '撥' to '拨', '擇' to '择', '掛' to '挂',
        '摯' to '挚', '摳' to '抠', '掄' to '抡', '損' to '损', '換' to '换',
        '據' to '据', '揮' to '挥', '撓' to '挠', '撿' to '捡', '捨' to '舍',
        '敗' to '败', '敘' to '叙', '敵' to '敌', '數' to '数', '齋' to '斋',
        '斬' to '斩', '斷' to '断', '於' to '于', '時' to '时', '曠' to '旷',
        '曇' to '昙', '晝' to '昼', '暈' to '晕', '暫' to '暂', '曉' to '晓',
        '曆' to '历', '會' to '会', '朧' to '胧', '術' to '术', '樸' to '朴',
        '機' to '机', '殺' to '杀', '雜' to '杂', '權' to '权', '條' to '条',
        '來' to '来', '楊' to '杨', '傑' to '杰', '極' to '极', '構' to '构',
        '樞' to '枢', '標' to '标', '棧' to '栈', '棟' to '栋', '欄' to '栏',
        '樹' to '树', '樣' to '样', '橋' to '桥', '檔' to '档', '檢' to '检',
        '櫻' to '樱', '歡' to '欢', '歐' to '欧', '歲' to '岁', '歷' to '历',
        '殘' to '残', '殞' to '殒', '毀' to '毁', '毆' to '殴', '氣' to '气',
        '漢' to '汉', '湯' to '汤', '溝' to '沟', '沒' to '没', '沖' to '冲',
        '況' to '况', '洶' to '汹', '決' to '决', '淚' to '泪', '潔' to '洁',
        '灑' to '洒', '濁' to '浊', '測' to '测', '濟' to '济', '瀏' to '浏',
        '渾' to '浑', '濃' to '浓', '澤' to '泽', '澆' to '浇', '塗' to '涂',
        '湧' to '涌', '濤' to '涛', '渦' to '涡', '滅' to '灭', '滯' to '滞',
        '滲' to '渗', '滾' to '滚', '滿' to '满', '濾' to '滤', '灘' to '滩',
        '災' to '灾', '為' to '为', '烏' to '乌', '無' to '无', '煉' to '炼',
        '煙' to '烟', '煩' to '烦', '燒' to '烧', '燭' to '烛', '熱' to '热',
        '愛' to '爱', '爺' to '爷', '牆' to '墙', '牽' to '牵', '犧' to '牺',
        '狀' to '状', '獨' to '独', '狹' to '狭', '獅' to '狮', '獎' to '奖',
        '瑪' to '玛', '環' to '环', '現' to '现', '琺' to '珐', '電' to '电',
        '畫' to '画', '暢' to '畅', '畢' to '毕', '異' to '异', '療' to '疗',
        '癥' to '症', '癡' to '痴', '癢' to '痒', '癮' to '瘾', '癬' to '癣',
        '發' to '发', '盜' to '盗', '盞' to '盏', '監' to '监', '盤' to '盘',
        '盧' to '卢', '眥' to '眦', '著' to '着', '睜' to '睁', '瞞' to '瞒',
        '矯' to '矫', '礦' to '矿', '碼' to '码', '磚' to '砖', '確' to '确',
        '禮' to '礼', '禍' to '祸', '禪' to '禅', '離' to '离', '種' to '种',
        '積' to '积', '稱' to '称', '穩' to '稳', '窮' to '穷', '竅' to '窍',
        '競' to '竞', '筆' to '笔', '築' to '筑', '簡' to '简', '簽' to '签',
        '籃' to '篮', '類' to '类', '糧' to '粮', '緊' to '紧', '糾' to '纠',
        '紀' to '纪', '紂' to '纣', '約' to '约', '紅' to '红', '紋' to '纹',
        '紡' to '纺', '紐' to '纽', '純' to '纯', '紗' to '纱', '紙' to '纸',
        '級' to '级', '紛' to '纷', '素' to '素', '紡' to '纺', '索' to '索',
        '練' to '练', '組' to '组', '細' to '细', '織' to '织', '終' to '终',
        '絆' to '绊', '紹' to '绍', '繹' to '绎', '經' to '经', '綁' to '绑',
        '綜' to '综', '綠' to '绿', '綴' to '缀', '網' to '网', '綱' to '纲',
        '綺' to '绮', '綻' to '绽', '綽' to '绰', '綾' to '绫', '綿' to '绵',
        '緊' to '紧', '緒' to '绪', '線' to '线', '締' to '缔', '編' to '编',
        '緩' to '缓', '緯' to '纬', '緻' to '致', '縱' to '纵', '縛' to '缚',
        '縣' to '县', '縫' to '缝', '縮' to '缩', '總' to '总', '績' to '绩',
        '繪' to '绘', '繩' to '绳', '繼' to '继', '續' to '续', '纏' to '缠',
        '罰' to '罚', '羅' to '罗', '羆' to '罴', '羈' to '羁', '聖' to '圣',
        '聞' to '闻', '聯' to '联', '聰' to '聪', '聲' to '声', '聳' to '耸',
        '職' to '职', '聶' to '聂', '腎' to '肾', '腫' to '肿', '脹' to '胀',
        '腦' to '脑', '腳' to '脚', '腸' to '肠', '臉' to '脸', '臟' to '脏',
        '臨' to '临', '臺' to '台', '與' to '与', '興' to '兴', '舊' to '旧',
        '艦' to '舰', '艙' to '舱', '藝' to '艺', '節' to '节', '範' to '范',
        '薦' to '荐', '藥' to '药', '萬' to '万', '蕭' to '萧', '薩' to '萨',
        '藍' to '蓝', '虛' to '虚', '蟲' to '虫', '蠟' to '蜡', '蠶' to '蚕',
        '蠻' to '蛮', '補' to '补', '裝' to '装', '裡' to '里', '製' to '制',
        '複' to '复', '見' to '见', '規' to '规', '覓' to '觅', '視' to '视',
        '覺' to '觉', '覽' to '览', '觀' to '观', '觸' to '触', '訂' to '订',
        '計' to '计', '訊' to '讯', '討' to '讨', '訓' to '训', '託' to '托',
        '記' to '记', '訟' to '讼', '訪' to '访', '設' to '设', '許' to '许',
        '訴' to '诉', '診' to '诊', '詆' to '诋', '詐' to '诈', '詔' to '诏',
        '評' to '评', '詞' to '词', '試' to '试', '詩' to '诗', '誠' to '诚',
        '話' to '话', '誕' to '诞', '該' to '该', '詳' to '详', '誇' to '夸',
        '譽' to '誉', '謄' to '誊', '誌' to '志', '認' to '认', '誡' to '诫',
        '誣' to '诬', '語' to '语', '誤' to '误', '說' to '说', '誰' to '谁',
        '課' to '课', '誼' to '谊', '調' to '调', '諒' to '谅', '談' to '谈',
        '請' to '请', '諸' to '诸', '諾' to '诺', '謀' to '谋', '謁' to '谒',
        '謂' to '谓', '謎' to '谜', '謠' to '谣', '謹' to '谨', '識' to '识',
        '譜' to '谱', '警' to '警', '譯' to '译', '議' to '议', '讓' to '让',
        '豔' to '艳', '貝' to '贝', '貞' to '贞', '負' to '负', '財' to '财',
        '貢' to '贡', '貧' to '贫', '貨' to '货', '貪' to '贪', '貫' to '贯',
        '責' to '责', '賢' to '贤', '敗' to '败', '賬' to '账', '質' to '质',
        '賭' to '赌', '購' to '购', '贈' to '赠', '贊' to '赞', '趕' to '赶',
        '趙' to '赵', '跡' to '迹', '踐' to '践', '躍' to '跃', '車' to '车',
        '軌' to '轨', '軍' to '军', '軟' to '软', '軸' to '轴', '較' to '较',
        '載' to '载', '輕' to '轻', '輝' to '辉', '輩' to '辈', '輪' to '轮',
        '輯' to '辑', '輸' to '输', '轄' to '辖', '辭' to '辞', '邊' to '边',
        '遼' to '辽', '達' to '达', '遷' to '迁', '過' to '过', '運' to '运',
        '還' to '还', '這' to '这', '進' to '进', '遠' to '远', '違' to '违',
        '連' to '连', '遲' to '迟', '適' to '适', '選' to '选', '遺' to '遗',
        '郵' to '邮', '鄧' to '邓', '鄭' to '郑', '鄰' to '邻', '鄲' to '郸',
        '醞' to '酝', '醫' to '医', '釀' to '酿', '釋' to '释', '裡' to '里',
        '鑒' to '鉴', '鑰' to '钥', '鈣' to '钙', '鈍' to '钝', '鈔' to '钞',
        '鐘' to '钟', '鋼' to '钢', '錢' to '钱', '鑽' to '钻', '鐵' to '铁',
        '長' to '长', '門' to '门', '閃' to '闪', '閉' to '闭', '問' to '问',
        '間' to '间', '閑' to '闲', '聞' to '闻', '閣' to '阁', '閱' to '阅',
        '隊' to '队', '陽' to '阳', '陰' to '阴', '陣' to '阵', '階' to '阶',
        '際' to '际', '隨' to '随', '隱' to '隐', '險' to '险', '難' to '难',
        '雖' to '虽', '雙' to '双', '雞' to '鸡', '離' to '离', '雜' to '杂',
        '電' to '电', '霧' to '雾', '靈' to '灵', '靜' to '静', '頂' to '顶',
        '頃' to '顷', '項' to '项', '順' to '顺', '須' to '须', '頑' to '顽',
        '顧' to '顾', '頓' to '顿', '頒' to '颁', '預' to '预', '領' to '领',
        '頰' to '颊', '頭' to '头', '頸' to '颈', '頻' to '频', '題' to '题',
        '額' to '额', '顏' to '颜', '風' to '风', '飛' to '飞', '飢' to '饥',
        '飯' to '饭', '飲' to '饮', '飼' to '饲', '飽' to '饱', '飾' to '饰',
        '餃' to '饺', '餅' to '饼', '餘' to '余', '館' to '馆', '馬' to '马',
        '馭' to '驭', '駁' to '驳', '駐' to '驻', '駕' to '驾', '駛' to '驶',
        '駝' to '驼', '駱' to '骆', '騎' to '骑', '騙' to '骗', '騰' to '腾',
        '驅' to '驱', '驚' to '惊', '驗' to '验', '髒' to '脏', '鬥' to '斗',
        '魚' to '鱼', '魯' to '鲁', '鮮' to '鲜', '鳥' to '鸟', '鳴' to '鸣',
        '鴻' to '鸿', '鵬' to '鹏', '鷹' to '鹰', '麥' to '麦', '黃' to '黄',
        '點' to '点', '齊' to '齐', '齒' to '齿', '龍' to '龙'
    )

    private suspend fun cacheTranslatedText(
        hash: String,
        originalText: String,
        normalizedText: String,
        translatedText: String,
        backend: String,
        playerName: String
    ) {
        val simplified = sanitizeTranslation(normalizedText, translatedText)
        if (simplified.isBlank()) {
            FgoLogger.warn(tag, "Not caching blank or non-translation result, hash=${hash.take(8)}...")
            return
        }
        if (containsUnresolvedPlaceholder(simplified)) {
            FgoLogger.warn(tag, "Not caching translation with unresolved placeholder, hash=${hash.take(8)}...")
            return
        }
        if (looksUntranslated(normalizedText, simplified, playerName)) {
            FgoLogger.warn(tag, "Not caching untranslated result, hash=${hash.take(8)}...")
            return
        }
        cacheDao.insert(
            CachedTranslation(
                jpTextHash = hash,
                jpText = originalText,
                normalizedJpText = normalizedText,
                cnText = simplified,
                backend = backend,
                promptVersion = PromptBuilder.PROMPT_VERSION
            )
        )
        putMemoryCachedTranslation(hash, simplified)
        FgoLogger.debug(tag, "Scene cached result, hash=${hash.take(8)}...")
    }

    private data class ParsedSceneResult(
        val name: String?,
        val dialogue: String?,
        val choices: List<String>
    )

    private fun buildSceneUserPrompt(
        name: String?,
        dialogue: String?,
        choices: List<String>
    ): String {
        return buildString {
            appendLine("Translate this Fate/Grand Order story scene to Simplified Chinese.")
            appendLine("Return ONLY a JSON object with exactly these keys:")
            appendLine("""{"name": string|null, "dialogue": string|null, "choices": string[]}""")
            appendLine("Rules:")
            appendLine("- Translate name only if name is not null; otherwise return null.")
            appendLine("- If a name is not in the glossary, transliterate it as a concise Simplified Chinese Fate/Grand Order character name. Never return the original Japanese name unchanged.")
            appendLine("- For short katakana names, transliterate the sound literally (example: レオン -> 莱昂). Do not replace it with another known FGO character.")
            appendLine("- Translate dialogue only if dialogue is not null; otherwise return null.")
            appendLine("- Translate choices as an array in the same order.")
            appendLine("- Keep __FGOTERM_n__ and __FGOPLAYER_n__ placeholders unchanged exactly; do not translate them.")
            appendLine("- If さん is a suffix after a character, Servant, NPC, or player name, translate that suffix as 桑; never use 先生, 小姐, or 女士. Do not apply to fixed common words like 皆さん, お父さん, お母さん, お兄さん, or お姉さん.")
            appendLine("- If ズ is a suffix after a character, Servant, NPC, or player name, treat it like English plural -s. Translate as X们 by default, or X组/X队 only when it clearly means a team.")
            appendLine("- Treat base《reading》 as ruby/furigana context. Translate the full base phrase first, then place any parenthetical after that full phrase. Never insert the parenthetical in the middle of the translated base phrase.")
            appendLine("- No markdown, no explanations, no extra keys.")
            appendLine()
            appendLine("Scene:")
            appendLine("name: ${name ?: "null"}")
            appendLine("dialogue: ${dialogue ?: "null"}")
            appendLine("choices:")
            if (choices.isEmpty()) {
                appendLine("[]")
            } else {
                choices.forEachIndexed { index, choice ->
                    appendLine("${index + 1}. $choice")
                }
            }
        }
    }

    private fun parseSceneResult(
        rawResult: String,
        expectName: Boolean,
        expectDialogue: Boolean,
        expectedChoiceCount: Int
    ): ParsedSceneResult {
        val trimmed = rawResult.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) {
            val plainText = cleanModelText(trimmed)
            if (expectedChoiceCount == 0 && plainText.isNotBlank()) {
                if (expectDialogue && !expectName) {
                    return ParsedSceneResult(
                        name = null,
                        dialogue = plainText,
                        choices = emptyList()
                    )
                }
                if (expectName && !expectDialogue) {
                    return ParsedSceneResult(
                        name = plainText,
                        dialogue = null,
                        choices = emptyList()
                    )
                }
            }
            throw IllegalArgumentException("Scene response did not contain a JSON object")
        }

        val jsonText = trimmed.substring(start, end + 1)
        val obj = responseJson.parseToJsonElement(jsonText).jsonObject
        val name = obj["name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.trim()
        val dialogue = obj["dialogue"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.trim()
        val choices = obj["choices"]
            ?.takeUnless { it is JsonNull }
            ?.jsonArray
            ?.map { it.jsonPrimitive.content.trim() }
            ?: emptyList()

        if (expectName && name.isNullOrBlank()) {
            throw IllegalArgumentException("Scene response missing name")
        }
        if (expectDialogue && dialogue.isNullOrBlank()) {
            throw IllegalArgumentException("Scene response missing dialogue")
        }
        if (choices.size != expectedChoiceCount) {
            throw IllegalArgumentException(
                "Scene response choice count ${choices.size} != expected $expectedChoiceCount"
            )
        }
        return ParsedSceneResult(
            name = name,
            dialogue = dialogue,
            choices = choices
        )
    }

    private fun cleanModelText(text: String): String {
        return text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun buildSingleUserPrompt(
        japaneseText: String,
        choiceTexts: List<String>,
        preserveRubyMeaning: Boolean
    ): String {
        val basePrompt = promptBuilder.buildUserPrompt(japaneseText, choiceTexts)
        if (!preserveRubyMeaning || !japaneseText.contains('《')) {
            return basePrompt
        }
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("Crop ruby rule:")
            appendLine("- The source includes visible small ruby/furigana in base《ruby》 form.")
            appendLine("- Translate BOTH the base text and the ruby text.")
            appendLine("- Put the translated ruby meaning in a Chinese parenthetical AFTER the full translated base phrase.")
            appendLine("- Do not omit the ruby meaning and do not place it in the middle of the base phrase.")
            appendLine("Example shape: Good point《nice job》 -> Good point（nice job）")
        }
    }

    private fun buildBatchUserPrompt(texts: List<String>): String {
        return buildString {
            appendLine("Translate each Japanese item to Simplified Chinese.")
            appendLine("Return ONLY a JSON array of strings.")
            appendLine("The JSON array must contain exactly ${texts.size} items in the same order.")
            appendLine("Keep __FGOTERM_n__ and __FGOPLAYER_n__ placeholders unchanged exactly; do not translate them.")
            appendLine("If さん is a suffix after a character, Servant, NPC, or player name, translate that suffix as 桑; never use 先生, 小姐, or 女士. Do not apply to fixed common words like 皆さん, お父さん, お母さん, お兄さん, or お姉さん.")
            appendLine("If ズ is a suffix after a character, Servant, NPC, or player name, treat it like English plural -s. Translate as X们 by default, or X组/X队 only when it clearly means a team.")
            appendLine("Treat base《reading》 as ruby/furigana context, not separate dialogue. If you include the reading as a parenthetical, place it after the full translated base phrase, never inside it.")
            appendLine("No markdown, no explanations, no extra keys.")
            appendLine()
            appendLine("Items:")
            texts.forEachIndexed { index, text ->
                appendLine("${index + 1}. $text")
            }
        }
    }

    private fun localFailureFallbackText(
        config: RuntimeConfig,
        allowLocalFailureFallback: Boolean
    ): String {
        return if (allowLocalFailureFallback && config.isLocalLlama) {
            LOCAL_LLAMA_RENDER_FALLBACK
        } else {
            UNTRANSLATED_FALLBACK
        }
    }

    private suspend fun retryUntranslatedSingle(
        config: RuntimeConfig,
        playerName: String,
        normalizedText: String,
        normalizedChoices: List<String>,
        matchedTerms: List<TermEntity>,
        protectedInput: ProtectedText
    ): String? {
        val retryMessages = listOf(
            ChatMessage(
                "system",
                promptBuilder.buildSystemPrompt(matchedTerms, playerName) + """

IMPORTANT RETRY:
The previous response copied Japanese instead of translating.
Return Simplified Chinese only.
The player name "$playerName" is fixed user text; keep it exactly if it appears.
Do not include Japanese kana except inside the player name or fixed official terminology supplied above.
Keep __FGOTERM_n__ and __FGOPLAYER_n__ placeholders unchanged exactly.
If さん is a suffix after a character, Servant, NPC, or player name, translate that suffix as 桑; never use 先生, 小姐, or 女士.
If ズ is a suffix after a character, Servant, NPC, or player name, translate it as a plural/group marker: X们 by default, or X组/X队 only when clearly a team.
""".trimIndent()
            ),
            ChatMessage("user", buildStrictRetryUserPrompt(protectedInput.text, normalizedChoices))
        )

        val retryRaw = try {
            callTranslationBackend(config, retryMessages)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Strict retry API call failed: ${e.message}", e)
            return null
        }

        val retrySimplified = sanitizeTranslation(
            normalizedText,
            restoreProtectedTerms(retryRaw, protectedInput.terms, playerName)
        )
        if (containsUnresolvedPlaceholder(retrySimplified) ||
            looksUntranslated(normalizedText, retrySimplified, playerName)
        ) {
            return null
        }
        FgoLogger.info(tag, "Strict retry produced translated result")
        return retrySimplified
    }

    private fun buildStrictRetryUserPrompt(
        japaneseText: String,
        choiceTexts: List<String>
    ): String {
        return buildString {
            appendLine("Translate this Japanese Fate/Grand Order text into Simplified Chinese.")
            appendLine("Return ONLY the Chinese translation. No Japanese source text, no explanation.")
            appendLine("If placeholders like __FGOTERM_1__ or __FGOPLAYER_1__ appear, copy them exactly.")
            appendLine("If さん is a suffix after a character, Servant, NPC, or player name, translate that suffix as 桑; never use 先生, 小姐, or 女士.")
            appendLine("If ズ is a suffix after a character, Servant, NPC, or player name, translate it as a plural/group marker: X们 by default.")
            if (choiceTexts.isNotEmpty()) {
                appendLine("Choice context:")
                choiceTexts.forEachIndexed { index, choice ->
                    appendLine("[Choice ${index + 1}] $choice")
                }
            }
            appendLine()
            appendLine("Japanese source:")
            append(japaneseText)
        }
    }

    private fun parseBatchResult(rawResult: String, expectedCount: Int): List<String> {
        val trimmed = rawResult.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException("Batch response did not contain a JSON array")
        }

        val jsonText = trimmed.substring(start, end + 1)
        val values = responseJson.parseToJsonElement(jsonText)
            .jsonArray
            .map { it.jsonPrimitive.content.trim() }
        if (values.size != expectedCount) {
            throw IllegalArgumentException(
                "Batch response item count ${values.size} != expected $expectedCount"
            )
        }
        return values
    }

    private fun chatMessagesJson(messages: List<ChatMessage>) = buildJsonArray {
        for (msg in messages) {
            add(buildJsonObject {
                put("role", JsonPrimitive(msg.role))
                put("content", JsonPrimitive(msg.content))
            })
        }
    }

    private suspend fun translateDeepSeek(
        apiKey: String,
        apiBaseUrl: String,
        apiModel: String,
        messages: List<ChatMessage>
    ): String {
        val response = httpClient.post(apiBaseUrl) {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", JsonPrimitive(apiModel))
                    put("max_tokens", JsonPrimitive(1024))
                    put("temperature", JsonPrimitive(0.3))
                    put("messages", chatMessagesJson(messages))
                    if (apiModel.startsWith("deepseek-v4")) {
                        put("thinking", buildJsonObject {
                            put("type", JsonPrimitive("disabled"))
                        })
                    }
                }
            )
        }
        val body = response.body<ChatResponse>()
        return body.choices.firstOrNull()?.message?.content
            ?: throw Exception("DeepSeek returned empty response")
    }

    private suspend fun translateClaude(
        apiKey: String,
        apiBaseUrl: String,
        apiModel: String,
        messages: List<ChatMessage>
    ): String {
        val response = httpClient.post(apiBaseUrl) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", JsonPrimitive(apiModel))
                    put("max_tokens", JsonPrimitive(1024))
                    put("temperature", JsonPrimitive(0.3))
                    put("messages", chatMessagesJson(messages))
                }
            )
        }

        val body = response.body<JsonObject>()
        val contentArray = body["content"] as? kotlinx.serialization.json.JsonArray
        val firstBlock = contentArray?.firstOrNull() as? JsonObject
        return (firstBlock?.get("text") as? JsonPrimitive)?.content
            ?: throw Exception("Claude returned empty response")
    }

    private suspend fun translateOpenAiCompatible(
        apiKey: String,
        apiBaseUrl: String,
        apiModel: String,
        messages: List<ChatMessage>
    ): String {
        val response = httpClient.post(apiBaseUrl) {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(model = apiModel, messages = messages))
        }
        val body = response.body<ChatResponse>()
        return body.choices.firstOrNull()?.message?.content
            ?: throw Exception("Chat completions API returned empty response")
    }

    private fun cacheKey(
        normalizedText: String,
        choiceTexts: List<String>,
        config: RuntimeConfig,
        rubyPolicyKey: String = ""
    ): String {
        return hashText(
            listOf(
                PromptBuilder.PROMPT_VERSION,
                config.backend,
                config.apiBaseUrl,
                config.apiModel,
                rubyPolicyKey,
                config.glossaryCacheKey,
                TextNormalizer.normalizeForTranslation(config.playerName),
                normalizedText,
                choiceTexts.joinToString("\n")
            ).joinToString("\u001F")
        )
    }

    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun callTranslationBackend(
        config: RuntimeConfig,
        messages: List<ChatMessage>
    ): String {
        return when (config.backend) {
            SettingsRepository.BACKEND_LOCAL_LLAMA -> if (LocalLlamaTranslator.RUNTIME_AVAILABLE) {
                localLlamaTranslator.translate(messages)
            } else {
                FgoLogger.warn(tag, "Local llama runtime unavailable; falling back to DeepSeek")
                translateDeepSeek(
                    apiKey = config.apiKey,
                    apiBaseUrl = SettingsRepository.DEFAULT_DEEPSEEK_BASE_URL,
                    apiModel = SettingsRepository.DEFAULT_DEEPSEEK_MODEL,
                    messages = messages
                )
            }

            SettingsRepository.BACKEND_CLAUDE -> translateClaude(
                apiKey = config.apiKey,
                apiBaseUrl = config.apiBaseUrl,
                apiModel = config.apiModel,
                messages = messages
            )

            SettingsRepository.BACKEND_GPT,
            SettingsRepository.BACKEND_FGOGOTRAN,
            SettingsRepository.BACKEND_CUSTOM_OPENAI -> translateOpenAiCompatible(
                apiKey = config.apiKey,
                apiBaseUrl = config.apiBaseUrl,
                apiModel = config.apiModel,
                messages = messages
            )

            else -> translateDeepSeek(
                apiKey = config.apiKey,
                apiBaseUrl = config.apiBaseUrl,
                apiModel = config.apiModel,
                messages = messages
            )
        }
    }
}
