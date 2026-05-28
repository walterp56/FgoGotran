package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
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
    private val translationMemory: TranslationMemory
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
    private var cachedTerms: List<TermEntity>? = null
    private var cachedCharacterNames: List<CharacterNameEntity>? = null

    private data class RuntimeConfig(
        val backend: String,
        val apiKey: String,
        val playerName: String,
        val cacheEnabled: Boolean
    )

    companion object {
        private const val RUNTIME_CONFIG_CACHE_TTL_MS = 5_000L
    }

    suspend fun translate(
        japaneseText: String,
        choiceTexts: List<String> = emptyList()
    ): TranslateResult {
        val normalizedText = TextNormalizer.normalizeForTranslation(japaneseText)
        val normalizedChoices = choiceTexts.map(TextNormalizer::normalizeForTranslation)
            .filter { it.isNotBlank() }

        if (normalizedText.isBlank()) {
            return TranslateResult("", "none", true)
        }

        translationMemory.lookupNormalized(normalizedText)?.let {
            FgoLogger.info(tag, "Official CN memory HIT")
            return TranslateResult(toSimplifiedChinese(it), "official-cn", true)
        }

        findCharacterNameTranslation(normalizedText)?.let {
            FgoLogger.info(tag, "Character exact HIT")
            return TranslateResult(toSimplifiedChinese(it), "character-db", true)
        }

        findTermTranslation(normalizedText)?.let {
            FgoLogger.info(tag, "Glossary exact HIT")
            return TranslateResult(toSimplifiedChinese(it), "glossary", true)
        }

        val config = getRuntimeConfig()
        val backend = config.backend
        val apiKey = config.apiKey
        val playerName = config.playerName
        val cacheEnabled = config.cacheEnabled
        val hash = cacheKey(normalizedText, normalizedChoices, backend)

        FgoLogger.debug(tag, "translate: textLen=${normalizedText.length}, choices=${normalizedChoices.size}")

        if (cacheEnabled) {
            val cached = cacheDao.getCached(hash)
            if (cached != null) {
                FgoLogger.info(tag, "Cache HIT, hash=${hash.take(8)}...")
                return TranslateResult(toSimplifiedChinese(cached), "cache", true)
            }
        }
        FgoLogger.debug(tag, "Cache miss, hash=${hash.take(8)}...")

        if (apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured; returning placeholder")
            return TranslateResult(
                "[API key not configured]\nOpen Settings and enter an API key.",
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

        val messages = listOf(
            ChatMessage("system", promptBuilder.buildSystemPrompt(matchedTerms, playerName)),
            ChatMessage("user", promptBuilder.buildUserPrompt(normalizedText, normalizedChoices))
        )

        FgoLogger.info(tag, "Calling $backend API")
        val result = try {
            when (backend) {
                SettingsRepository.BACKEND_DEEPSEEK -> translateDeepSeek(apiKey, messages)
                SettingsRepository.BACKEND_CLAUDE -> translateClaude(apiKey, messages)
                SettingsRepository.BACKEND_GPT -> translateGpt(apiKey, messages)
                else -> translateDeepSeek(apiKey, messages)
            }
        } catch (e: Exception) {
            FgoLogger.error(tag, "$backend API call failed: ${e.message}", e)
            return TranslateResult(
                "[Translation failed: ${e.message}]\nCheck your API key and network connection.",
                backend,
                false
            )
        }

        val simplifiedResult = toSimplifiedChinese(result)

        if (cacheEnabled) {
            cacheDao.insert(
                CachedTranslation(
                    jpTextHash = hash,
                    jpText = japaneseText,
                    normalizedJpText = normalizedText,
                    cnText = simplifiedResult,
                    backend = backend,
                    promptVersion = PromptBuilder.PROMPT_VERSION
                )
            )
            FgoLogger.debug(tag, "Cached result, hash=${hash.take(8)}...")
        }

        FgoLogger.info(tag, "Translation complete: backend=$backend, chars=${simplifiedResult.length}")
        return TranslateResult(simplifiedResult, backend, false)
    }

    suspend fun translateBatch(japaneseTexts: List<String>): List<TranslateResult> {
        if (japaneseTexts.isEmpty()) return emptyList()

        val normalizedTexts = japaneseTexts.map(TextNormalizer::normalizeForTranslation)
        val config = getRuntimeConfig()
        val backend = config.backend
        val apiKey = config.apiKey
        val playerName = config.playerName
        val cacheEnabled = config.cacheEnabled
        val hashes = normalizedTexts.map { cacheKey(it, emptyList(), backend) }
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
                results[index] = TranslateResult(toSimplifiedChinese(officialMemory), "official-cn", true)
                continue
            }

            val characterTranslation = findCharacterNameTranslation(normalizedText)
            if (characterTranslation != null) {
                FgoLogger.info(tag, "Batch character exact HIT[$index]")
                results[index] = TranslateResult(toSimplifiedChinese(characterTranslation), "character-db", true)
                continue
            }

            val termTranslation = findTermTranslation(normalizedText)
            if (termTranslation != null) {
                FgoLogger.info(tag, "Batch term exact HIT[$index]")
                results[index] = TranslateResult(toSimplifiedChinese(termTranslation), "glossary", true)
                continue
            }

            if (cacheEnabled) {
                val cached = cacheDao.getCached(hashes[index])
                if (cached != null) {
                    FgoLogger.info(tag, "Batch cache HIT, hash=${hashes[index].take(8)}...")
                    results[index] = TranslateResult(toSimplifiedChinese(cached), "cache", true)
                    continue
                }
            }
            uncachedIndices.add(index)
        }

        if (uncachedIndices.isEmpty()) {
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        if (apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured; returning placeholders for batch")
            val placeholder = "[API key not configured]\nOpen Settings and enter an API key."
            uncachedIndices.forEach { index ->
                results[index] = TranslateResult(placeholder, "none", false)
            }
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        val uncachedTexts = uncachedIndices.map { normalizedTexts[it] }
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

        val messages = listOf(
            ChatMessage("system", promptBuilder.buildSystemPrompt(matchedTerms, playerName)),
            ChatMessage("user", buildBatchUserPrompt(uncachedTexts))
        )

        FgoLogger.info(tag, "Calling $backend API for batch (${uncachedTexts.size} items)")
        val translatedTexts = try {
            val rawResult = when (backend) {
                SettingsRepository.BACKEND_DEEPSEEK -> translateDeepSeek(apiKey, messages)
                SettingsRepository.BACKEND_CLAUDE -> translateClaude(apiKey, messages)
                SettingsRepository.BACKEND_GPT -> translateGpt(apiKey, messages)
                else -> translateDeepSeek(apiKey, messages)
            }
            parseBatchResult(rawResult, uncachedTexts.size)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Batch translation failed, falling back to single calls", e)
            for (index in uncachedIndices) {
                results[index] = translate(japaneseTexts[index])
            }
            return results.map { it ?: TranslateResult("", "none", true) }
        }

        for ((batchIndex, originalIndex) in uncachedIndices.withIndex()) {
            val translated = toSimplifiedChinese(translatedTexts[batchIndex])
            results[originalIndex] = TranslateResult(translated, backend, false)
            if (cacheEnabled) {
                cacheDao.insert(
                    CachedTranslation(
                        jpTextHash = hashes[originalIndex],
                        jpText = japaneseTexts[originalIndex],
                        normalizedJpText = normalizedTexts[originalIndex],
                        cnText = translated,
                        backend = backend,
                        promptVersion = PromptBuilder.PROMPT_VERSION
                    )
                )
                FgoLogger.debug(tag, "Batch cached result, hash=${hashes[originalIndex].take(8)}...")
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

        var nameResult: TranslateResult? = null
        var dialogueResult: TranslateResult? = null
        val choiceResults = MutableList<TranslateResult?>(input.choices.size) { null }

        normalizedName?.let { normalized ->
            translationMemory.lookupNormalized(normalized)?.let {
                FgoLogger.info(tag, "Official CN memory HIT name")
                nameResult = TranslateResult(toSimplifiedChinese(it), "official-cn", true)
            }
            if (nameResult == null) {
                findCharacterNameTranslation(normalized)?.let {
                    FgoLogger.info(tag, "Character exact HIT name")
                    nameResult = TranslateResult(toSimplifiedChinese(it), "character-db", true)
                }
            }
        }
        normalizedDialogue?.let { normalized ->
            translationMemory.lookupNormalized(normalized)?.let {
                FgoLogger.info(tag, "Official CN memory HIT dialogue")
                dialogueResult = TranslateResult(toSimplifiedChinese(it), "official-cn", true)
            }
            if (dialogueResult == null) {
                findTermTranslation(normalized)?.let {
                    FgoLogger.info(tag, "Term exact HIT dialogue")
                    dialogueResult = TranslateResult(toSimplifiedChinese(it), "glossary", true)
                }
            }
        }
        normalizedChoices.forEachIndexed { index, normalized ->
            if (normalized == null) return@forEachIndexed
            translationMemory.lookupNormalized(normalized)?.let {
                FgoLogger.info(tag, "Official CN memory HIT choice[$index]")
                choiceResults[index] = TranslateResult(toSimplifiedChinese(it), "official-cn", true)
            }
            if (choiceResults[index] == null) {
                findTermTranslation(normalized)?.let {
                    FgoLogger.info(tag, "Term exact HIT choice[$index]")
                    choiceResults[index] = TranslateResult(toSimplifiedChinese(it), "glossary", true)
                }
            }
        }

        val nameHash = normalizedName?.let { cacheKey(it, emptyList(), backend) }
        val dialogueHash = normalizedDialogue?.let { cacheKey(it, emptyList(), backend) }
        val choiceHashes = normalizedChoices.map { it?.let { text -> cacheKey(text, emptyList(), backend) } }

        if (cacheEnabled) {
            if (normalizedName != null && nameHash != null && nameResult == null) {
                cacheDao.getCached(nameHash)?.let {
                    FgoLogger.info(tag, "Scene cache HIT name, hash=${nameHash.take(8)}...")
                    nameResult = TranslateResult(toSimplifiedChinese(it), "cache", true)
                }
            }
            if (normalizedDialogue != null && dialogueHash != null && dialogueResult == null) {
                cacheDao.getCached(dialogueHash)?.let {
                    FgoLogger.info(tag, "Scene cache HIT dialogue, hash=${dialogueHash.take(8)}...")
                    dialogueResult = TranslateResult(toSimplifiedChinese(it), "cache", true)
                }
            }
            for (index in normalizedChoices.indices) {
                if (choiceResults[index] != null) continue
                val hash = choiceHashes[index] ?: continue
                cacheDao.getCached(hash)?.let {
                    FgoLogger.info(tag, "Scene cache HIT choice[$index], hash=${hash.take(8)}...")
                    choiceResults[index] = TranslateResult(toSimplifiedChinese(it), "cache", true)
                }
            }
        }

        val needsName = normalizedName != null && nameResult == null
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

        if (apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured; returning placeholders for scene")
            val placeholder = "[API key not configured]\nOpen Settings and enter an API key."
            if (needsName) nameResult = TranslateResult(placeholder, "none", false)
            if (needsDialogue) dialogueResult = TranslateResult(placeholder, "none", false)
            neededChoiceIndices.forEach { choiceResults[it] = TranslateResult(placeholder, "none", false) }
            return SceneTranslateResult(
                name = nameResult,
                dialogue = dialogueResult,
                choices = choiceResults.map { it ?: TranslateResult("", "none", true) }
            )
        }

        val uncachedName = normalizedName.takeIf { needsName }
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

        val messages = listOf(
            ChatMessage("system", promptBuilder.buildSystemPrompt(matchedTerms, config.playerName)),
            ChatMessage("user", buildSceneUserPrompt(uncachedName, uncachedDialogue, uncachedChoices))
        )

        FgoLogger.info(
            tag,
            "Calling $backend API for structured scene (name=$needsName, dialogue=$needsDialogue, choices=${uncachedChoices.size})"
        )
        val translatedScene = try {
            val rawResult = when (backend) {
                SettingsRepository.BACKEND_DEEPSEEK -> translateDeepSeek(apiKey, messages)
                SettingsRepository.BACKEND_CLAUDE -> translateClaude(apiKey, messages)
                SettingsRepository.BACKEND_GPT -> translateGpt(apiKey, messages)
                else -> translateDeepSeek(apiKey, messages)
            }
            parseSceneResult(rawResult, needsName, needsDialogue, uncachedChoices.size)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Structured scene translation failed, falling back to single calls", e)
            if (needsName) nameResult = translate(input.name.orEmpty())
            if (needsDialogue) dialogueResult = translate(input.dialogue.orEmpty())
            for (index in neededChoiceIndices) {
                choiceResults[index] = translate(input.choices[index])
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
            val simplifiedName = toSimplifiedChinese(translatedName)
            nameResult = TranslateResult(simplifiedName, backend, false)
            if (cacheEnabled) {
                cacheTranslatedText(nameHash!!, input.name.orEmpty(), normalizedName!!, simplifiedName, backend)
            }
        }
        if (needsDialogue) {
            val translatedDialogue = translatedScene.dialogue
                ?: throw IllegalStateException("Structured scene response missing parsed dialogue")
            val simplifiedDialogue = toSimplifiedChinese(translatedDialogue)
            dialogueResult = TranslateResult(simplifiedDialogue, backend, false)
            if (cacheEnabled) {
                cacheTranslatedText(dialogueHash!!, input.dialogue.orEmpty(), normalizedDialogue!!, simplifiedDialogue, backend)
            }
        }
        for ((batchIndex, originalIndex) in neededChoiceIndices.withIndex()) {
            val translatedChoice = toSimplifiedChinese(translatedScene.choices[batchIndex])
            val normalizedChoice = normalizedChoices[originalIndex] ?: continue
            val hash = choiceHashes[originalIndex] ?: continue
            choiceResults[originalIndex] = TranslateResult(translatedChoice, backend, false)
            if (cacheEnabled) {
                cacheTranslatedText(hash, input.choices[originalIndex], normalizedChoice, translatedChoice, backend)
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

        val loaded = RuntimeConfig(
            backend = settingsRepository.translationBackend.first(),
            apiKey = settingsRepository.apiKey.first(),
            playerName = userProfile.getPlayerName(),
            cacheEnabled = settingsRepository.cacheEnabled.first()
        )
        cachedRuntimeConfig = loaded
        cachedRuntimeConfigAt = now
        FgoLogger.debug(tag, "Runtime config refreshed")
        return loaded
    }

    private suspend fun getCachedTerms(): List<TermEntity> {
        cachedTerms?.let { return it }
        val loaded = termDao.getAllTerms() + getCachedCharacterNames().map {
            TermEntity(
                jpTerm = it.jpName,
                cnTerm = it.cnName,
                category = "character",
                aliases = it.aliases
            )
        }
        cachedTerms = loaded
        FgoLogger.debug(tag, "Glossary terms cached: ${loaded.size}")
        return loaded
    }

    private suspend fun getCachedCharacterNames(): List<CharacterNameEntity> {
        cachedCharacterNames?.let { return it }
        val loaded = termDao.getAllCharacterNames()
        cachedCharacterNames = loaded
        FgoLogger.debug(tag, "Character names cached: ${loaded.size}")
        return loaded
    }

    private suspend fun findCharacterNameTranslation(normalizedText: String): String? {
        termDao.findExactCharacterName(normalizedText)?.let { return it }
        val lookupText = normalizeNameLookup(normalizedText)
        return getCachedCharacterNames().firstOrNull { character ->
            lookupText == normalizeNameLookup(character.jpName) ||
                aliases(character.aliases).any { alias ->
                    lookupText == normalizeNameLookup(alias)
                }
        }?.cnName
    }

    private suspend fun findTermTranslation(normalizedText: String): String? {
        termDao.findExactTerm(normalizedText)?.let { return it }
        return termDao.getAllTerms().firstOrNull { term ->
            normalizedText == TextNormalizer.normalizeForTranslation(term.jpTerm) ||
                aliases(term.aliases).any { alias ->
                    normalizedText == TextNormalizer.normalizeForTranslation(alias)
                }
        }?.cnTerm
    }

    private fun aliases(rawAliases: String?): List<String> {
        return rawAliases.orEmpty()
            .trim('[', ']')
            .split(',')
            .map { it.trim('"', ' ', '\t', '\r', '\n') }
            .filter { it.isNotBlank() }
    }

    private fun normalizeNameLookup(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("""[\s　]+"""), "")
            .replace('一', 'ー')
            .replace('二', 'ニ')
            .replace('工', 'エ')
            .replace('－', 'ー')
            .replace('-', 'ー')
    }

    private fun toSimplifiedChinese(text: String): String {
        val phraseFixed = text
            .replace("庫爾霍斯", "福尔摩斯")
            .replace("庫霍姆斯", "福尔摩斯")
            .replace("福爾摩斯", "福尔摩斯")
            .replace("克尼莫", "尼莫")
            .replace("風暴之邊界", "Storm Border")
            .replace("风暴之边界", "Storm Border")
            .replace("風暴邊界", "Storm Border")
            .replace("风暴边界", "Storm Border")

        return buildString(phraseFixed.length) {
            for (char in phraseFixed) {
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
        backend: String
    ) {
        cacheDao.insert(
            CachedTranslation(
                jpTextHash = hash,
                jpText = originalText,
                normalizedJpText = normalizedText,
                cnText = toSimplifiedChinese(translatedText),
                backend = backend,
                promptVersion = PromptBuilder.PROMPT_VERSION
            )
        )
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
            appendLine("- Translate dialogue only if dialogue is not null; otherwise return null.")
            appendLine("- Translate choices as an array in the same order.")
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

    private fun buildBatchUserPrompt(texts: List<String>): String {
        return buildString {
            appendLine("Translate each Japanese item to Simplified Chinese.")
            appendLine("Return ONLY a JSON array of strings.")
            appendLine("The JSON array must contain exactly ${texts.size} items in the same order.")
            appendLine("No markdown, no explanations, no extra keys.")
            appendLine()
            appendLine("Items:")
            texts.forEachIndexed { index, text ->
                appendLine("${index + 1}. $text")
            }
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

    private suspend fun translateDeepSeek(
        apiKey: String,
        messages: List<ChatMessage>
    ): String {
        val response = httpClient.post("https://api.deepseek.com/v1/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(model = "deepseek-chat", messages = messages))
        }
        val body = response.body<ChatResponse>()
        return body.choices.firstOrNull()?.message?.content
            ?: throw Exception("DeepSeek returned empty response")
    }

    private suspend fun translateClaude(
        apiKey: String,
        messages: List<ChatMessage>
    ): String {
        val response = httpClient.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", JsonPrimitive("claude-sonnet-4-20250514"))
                    put("max_tokens", JsonPrimitive(1024))
                    put("temperature", JsonPrimitive(0.3))
                    put("messages", buildJsonArray {
                        for (msg in messages) {
                            add(buildJsonObject {
                                put("role", JsonPrimitive(msg.role))
                                put("content", JsonPrimitive(msg.content))
                            })
                        }
                    })
                }
            )
        }

        val body = response.body<JsonObject>()
        val contentArray = body["content"] as? kotlinx.serialization.json.JsonArray
        val firstBlock = contentArray?.firstOrNull() as? JsonObject
        return (firstBlock?.get("text") as? JsonPrimitive)?.content
            ?: throw Exception("Claude returned empty response")
    }

    private suspend fun translateGpt(
        apiKey: String,
        messages: List<ChatMessage>
    ): String {
        val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(model = "gpt-4o", messages = messages))
        }
        val body = response.body<ChatResponse>()
        return body.choices.firstOrNull()?.message?.content
            ?: throw Exception("GPT returned empty response")
    }

    private fun cacheKey(
        normalizedText: String,
        choiceTexts: List<String>,
        backend: String
    ): String {
        return hashText(
            listOf(
                PromptBuilder.PROMPT_VERSION,
                backend,
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
}
