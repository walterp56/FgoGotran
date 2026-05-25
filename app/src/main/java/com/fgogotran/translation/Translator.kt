package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.terminology.TermDao
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

        val backend = settingsRepository.translationBackend.first()
        val apiKey = settingsRepository.apiKey.first()
        val playerName = userProfile.getPlayerName()
        val cacheEnabled = settingsRepository.cacheEnabled.first()
        val hash = cacheKey(normalizedText, normalizedChoices, backend)

        FgoLogger.debug(tag, "translate: textLen=${normalizedText.length}, choices=${normalizedChoices.size}")

        if (cacheEnabled) {
            val cached = cacheDao.getCached(hash)
            if (cached != null) {
                FgoLogger.info(tag, "Cache HIT, hash=${hash.take(8)}...")
                SessionTranslationHistory.add(
                    SessionTranslationEntry(
                        jpText = japaneseText,
                        cnText = cached,
                        backend = "cache",
                        cached = true
                    )
                )
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
            val allTerms = termDao.getAllTerms()
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
        SessionTranslationHistory.add(
            SessionTranslationEntry(
                jpText = japaneseText,
                cnText = result,
                backend = backend,
                cached = false
            )
        )
        return TranslateResult(result, backend, false)
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
