package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.terminology.TermDao
import com.fgogotran.terminology.TermEntity
import com.fgogotran.util.FgoLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible chat message format used by DeepSeek and GPT backends.
 */
@Serializable
data class ChatMessage(
    val role: String,       // "system", "user", or "assistant"
    val content: String
)

/**
 * OpenAI-compatible chat completion request body.
 * @param temperature 0.3 — low temperature because translation is deterministic;
 *                    higher values produce creative but inconsistent terminology.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int = 1024,
    val temperature: Double = 0.3
)

/**
 * OpenAPI-compatible choice wrapper.
 */
@Serializable
data class ChatChoice(
    val message: ChatMessage
)

/**
 * OpenAI-compatible chat completion response.
 */
@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>
)

/**
 * Result of a translation request.
 * @param backend which API produced this (or "cache"/"none")
 * @param cached true if returned from local translation cache
 */
data class TranslateResult(
    val translatedText: String,
    val backend: String,
    val cached: Boolean
)

/**
 * Orchestrates JP→CN translation via LLM APIs with RAG terminology injection.
 *
 * ## Flow
 * 1. Compute SHA-256 hash of JP text → check local cache
 * 2. Load settings (backend, API key, player name)
 * 3. RAG: match JP text against the FGO terminology glossary
 * 4. Build system prompt (terminology table + 7 translation rules) and user prompt
 * 5. Dispatch to selected backend (DeepSeek / Claude / GPT)
 * 6. Cache the result for instant reuse on repeated dialogue
 *
 * ## Backend API differences
 * - DeepSeek & GPT: OpenAI-compatible `/v1/chat/completions` with Bearer auth
 * - Claude: Anthropic Messages API `/v1/messages` with `x-api-key` header and
 *   a different JSON shape (content is an array of content blocks, not a single string)
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
                isLenient = true  // Accept minor deviations from strict JSON schema
            })
        }
    }

    private val cacheDao = cacheDb.cacheDao()
    private val tag = "Translator"

    /**
     * Translates [japaneseText] from JP to CN.
     *
     * @param japaneseText The dialogue text extracted from the OCR result
     * @param choiceTexts Optional player choice texts shown on the same screen
     * @return [TranslateResult] with the translated text and metadata
     */
    suspend fun translate(
        japaneseText: String,
        choiceTexts: List<String> = emptyList()
    ): TranslateResult {
        if (japaneseText.isBlank()) {
            return TranslateResult("", "none", true)
        }

        FgoLogger.debug(tag, "translate: textLen=${japaneseText.length}, choices=${choiceTexts.size}")

        // ── 1. Check cache ──
        val hash = hashText(japaneseText)
        val cached = cacheDao.getCached(hash)
        if (cached != null) {
            FgoLogger.info(tag, "Cache HIT, hash=${hash.take(8)}...")
            return TranslateResult(cached, "cache", true)
        }
        FgoLogger.debug(tag, "Cache miss, hash=${hash.take(8)}...")

        // ── 2. Load settings ──
        val backend = settingsRepository.translationBackend.first()
        val apiKey = settingsRepository.apiKey.first()
        val playerName = userProfile.getPlayerName()

        if (apiKey.isBlank()) {
            FgoLogger.warn(tag, "No API key configured — returning placeholder")
            return TranslateResult(
                "[需要設定API Key]\n請在設定畫面輸入API Key",
                "none",
                false
            )
        }

        // ── 3. RAG term lookup ──
        // Matches JP dialogue text against the FGO terminology glossary
        // so the LLM uses official Chinese translations for proper nouns.
        // Schema mismatch in pre-built DB is non-fatal — skip RAG gracefully.
        val matchedTerms = try {
            val allTerms = termDao.getAllTerms()
            val matches = promptBuilder.extractTermMatches(japaneseText, allTerms)
            FgoLogger.debug(tag, "RAG: matched ${matches.size} of ${allTerms.size} terms")
            matches
        } catch (e: Exception) {
            FgoLogger.warn(tag, "RAG term lookup failed, continuing without glossary", e)
            emptyList()
        }

        // ── 4. Build prompts ──
        val systemPrompt = promptBuilder.buildSystemPrompt(matchedTerms, playerName)
        val userPrompt = promptBuilder.buildUserPrompt(japaneseText, choiceTexts)

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        )

        // ── 5. Call LLM API ──
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
                "[翻譯失敗: ${e.message}]\n請檢查API Key和網路連線",
                backend,
                false
            )
        }

        // ── 6. Cache result ──
        // Cache regardless of which backend produced it — avoids duplicate API calls
        // for the same dialogue text in future sessions.
        cacheDao.insert(
            CachedTranslation(
                jpTextHash = hash,
                jpText = japaneseText,
                cnText = result
            )
        )
        FgoLogger.debug(tag, "Cached result, hash=${hash.take(8)}...")

        FgoLogger.info(tag, "Translation complete: backend=$backend, chars=${result.length}")
        return TranslateResult(result, backend, false)
    }

    // ─── Backend-specific API calls ──────────────────────────────────

    /**
     * Calls the DeepSeek API — OpenAI-compatible `/v1/chat/completions` endpoint.
     * Uses Bearer token auth and the standard [ChatRequest]/[ChatResponse] serialization.
     */
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

    /**
     * Calls the Claude (Anthropic) Messages API.
     *
     * Uses a different JSON shape than OpenAI:
     * - Auth: `x-api-key` header instead of Bearer token
     * - Version: `anthropic-version: 2023-06-01` required
     * - Response: `content` is a JSON array of content blocks, each with a `type` and `text`
     */
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

        // Claude wraps the response text in content[0].text — not in choices[0].message.content
        val body = response.body<kotlinx.serialization.json.JsonObject>()
        val contentArray = body["content"] as? kotlinx.serialization.json.JsonArray
        val firstBlock = contentArray?.firstOrNull() as? kotlinx.serialization.json.JsonObject
        return (firstBlock?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw Exception("Claude returned empty response")
    }

    /**
     * Calls the OpenAI GPT API — uses the same OpenAI-compatible format as DeepSeek.
     */
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

    /**
     * Computes a SHA-256 hex digest of [text].
     * Same algorithm as [com.fgogotran.accessibility.FgoAccessibilityService.hashText]
     * — used here for cache lookup keys.
     */
    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
