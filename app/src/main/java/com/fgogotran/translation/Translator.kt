package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
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
    private val cacheDb: TranslationCacheDb
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
                return TranslateResult(cached, "cache", true)
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

        if (cacheEnabled) {
            cacheDao.insert(
                CachedTranslation(
                    jpTextHash = hash,
                    jpText = japaneseText,
                    normalizedJpText = normalizedText,
                    cnText = result,
                    backend = backend,
                    promptVersion = PromptBuilder.PROMPT_VERSION
                )
            )
            FgoLogger.debug(tag, "Cached result, hash=${hash.take(8)}...")
        }

        FgoLogger.info(tag, "Translation complete: backend=$backend, chars=${result.length}")
        return TranslateResult(result, backend, false)
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

            if (cacheEnabled) {
                val cached = cacheDao.getCached(hashes[index])
                if (cached != null) {
                    FgoLogger.info(tag, "Batch cache HIT, hash=${hashes[index].take(8)}...")
                    results[index] = TranslateResult(cached, "cache", true)
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
            val translated = translatedTexts[batchIndex]
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

        val nameHash = normalizedName?.let { cacheKey(it, emptyList(), backend) }
        val dialogueHash = normalizedDialogue?.let { cacheKey(it, emptyList(), backend) }
        val choiceHashes = normalizedChoices.map { it?.let { text -> cacheKey(text, emptyList(), backend) } }

        if (cacheEnabled) {
            if (normalizedName != null && nameHash != null) {
                cacheDao.getCached(nameHash)?.let {
                    FgoLogger.info(tag, "Scene cache HIT name, hash=${nameHash.take(8)}...")
                    nameResult = TranslateResult(it, "cache", true)
                }
            }
            if (normalizedDialogue != null && dialogueHash != null) {
                cacheDao.getCached(dialogueHash)?.let {
                    FgoLogger.info(tag, "Scene cache HIT dialogue, hash=${dialogueHash.take(8)}...")
                    dialogueResult = TranslateResult(it, "cache", true)
                }
            }
            for (index in normalizedChoices.indices) {
                val hash = choiceHashes[index] ?: continue
                cacheDao.getCached(hash)?.let {
                    FgoLogger.info(tag, "Scene cache HIT choice[$index], hash=${hash.take(8)}...")
                    choiceResults[index] = TranslateResult(it, "cache", true)
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
            nameResult = TranslateResult(translatedName, backend, false)
            if (cacheEnabled) {
                cacheTranslatedText(nameHash!!, input.name.orEmpty(), normalizedName!!, translatedName, backend)
            }
        }
        if (needsDialogue) {
            val translatedDialogue = translatedScene.dialogue
                ?: throw IllegalStateException("Structured scene response missing parsed dialogue")
            dialogueResult = TranslateResult(translatedDialogue, backend, false)
            if (cacheEnabled) {
                cacheTranslatedText(dialogueHash!!, input.dialogue.orEmpty(), normalizedDialogue!!, translatedDialogue, backend)
            }
        }
        for ((batchIndex, originalIndex) in neededChoiceIndices.withIndex()) {
            val translatedChoice = translatedScene.choices[batchIndex]
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
        val loaded = termDao.getAllTerms()
        cachedTerms = loaded
        FgoLogger.debug(tag, "Glossary terms cached: ${loaded.size}")
        return loaded
    }

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
                cnText = translatedText,
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
            appendLine("Translate this Fate/Grand Order story scene to Traditional Chinese.")
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
            appendLine("Translate each Japanese item to Traditional Chinese.")
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
