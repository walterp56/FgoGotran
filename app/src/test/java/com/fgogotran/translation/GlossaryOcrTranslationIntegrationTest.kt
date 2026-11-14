package com.fgogotran.translation

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.TranslationApiFormat
import com.fgogotran.data.TranslationReasoning
import com.fgogotran.data.TranslationRequestMode
import com.fgogotran.data.TranslationOutputLimit
import com.fgogotran.data.UserProfile
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.scriptmemory.ScriptMemory
import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.terminology.TermDao
import com.fgogotran.terminology.TermEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(io.ktor.util.InternalAPI::class)
class GlossaryOcrTranslationIntegrationTest {
    private lateinit var translator: Translator
    private lateinit var db: TranslationCacheDb
    private lateinit var settings: SettingsRepository
    private val requests = mutableListOf<String>()
    private val answers = ArrayDeque<String>()
    private val rawAnswers = ArrayDeque<String>()
    private val sentRequests = mutableListOf<HttpRequestData>()
    private val nikitich = CharacterNameEntity(jpName = "ニキチッチ", cnName = "尼基季奇", enName = "Nikitich", gender = "女性")

    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val local = dao(LocalGlossaryDao::class.java, emptyList<Any>())
        val terms = dao(TermDao::class.java, listOf(nikitich))
        settings = SettingsRepository(app, local)
        settings.saveApiSettings(settings.recommendedApiProfile("deepseek").copy(
            apiKey = "offline-fixture-only", model = "deepseek-chat"
        ))
        settings.setTargetLanguage("zh-Hans")
        db = Room.inMemoryDatabaseBuilder(app, TranslationCacheDb::class.java).build()
        translator = Translator(settings, UserProfile(settings), terms, local, PromptBuilder(),
            CharacterContextRepository(app), db, TranslationMemory(app), DiagnosticEventStore(app), ScriptMemory(app), app)
        val engine = object : HttpClientEngineBase("glossary-ocr-offline") {
            override val config = HttpClientEngineConfig()
            override val supportedCapabilities = setOf(HttpTimeout)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                requests += (data.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                sentRequests += data
                check(answers.isNotEmpty() || rawAnswers.isNotEmpty()) { "Unexpected API request: ${requests.last()}" }
                val response = rawAnswers.removeFirstOrNull()
                    ?: """{"choices":[{"message":{"content":${Json.encodeToString(answers.removeFirst())}},"finish_reason":"stop"}]}"""
                return HttpResponseData(HttpStatusCode.OK, GMTDate(), headersOf(HttpHeaders.ContentType, "application/json"),
                    HttpProtocolVersion.HTTP_1_1, ByteReadChannel(response), callContext())
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val field = Translator::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        (field.get(translator) as HttpClient).close()
        field.set(translator, client)
    }

    @After fun close() {
        if (::translator.isInitialized) (Translator::class.java.getDeclaredField("httpClient")
            .apply { isAccessible = true }.get(translator) as HttpClient).close()
        if (::db.isInitialized) db.close()
    }

    @Test fun legacyProfilesKeepTheEndpointAndModelThatWereActuallyUsed() {
        val legacy = preferencesOf(
            stringPreferencesKey("translation_backend") to "gpt",
            stringPreferencesKey("api_base_url_gpt") to "https://unused.invalid/chat",
            stringPreferencesKey("api_model_gpt") to "gpt-4.1-mini",
            stringPreferencesKey("api_key_gpt") to "old-gpt-key",
            stringPreferencesKey("qwen_site") to "international",
            stringPreferencesKey("api_key_qwen") to "old-qwen-key",
            stringPreferencesKey("api_base_url_custom_openai") to "http://192.168.1.20:8080/v1/chat/completions",
            stringPreferencesKey("api_model_custom_openai") to "local-model",
            stringPreferencesKey("api_key_custom_openai") to "old-local-key"
        )
        val gpt = settings.readApiProfile(legacy).validated()
        assertEquals("https://api.openai.com/v1/chat/completions", gpt.endpoint)
        assertEquals("gpt-4.1-mini", gpt.model)
        assertEquals("old-gpt-key", gpt.apiKey)
        assertEquals(TranslationOutputLimit.MAX_COMPLETION_TOKENS, gpt.requestOptions.outputLimit)

        val qwen = settings.readApiProfile(legacy, "qwen").validated()
        assertEquals(SettingsRepository.DEFAULT_QWEN_INTERNATIONAL_BASE_URL, qwen.endpoint)
        assertEquals("old-qwen-key", qwen.apiKey)
        val local = settings.readApiProfile(legacy, "custom_openai").validated()
        assertEquals("http://192.168.1.20:8080/v1/chat/completions", local.endpoint)
        assertEquals("local-model", local.model)
        assertEquals("old-local-key", local.apiKey)
        assertFailsWith<IllegalArgumentException> { local.copy(endpoint = "http://public.invalid/chat").validated() }
    }

    @Test(timeout = 30_000) fun apiTestAndGameplayUseTheSameSavedEndpointAndRequestOptions() = runBlocking {
        val official = settings.recommendedApiProfile(SettingsRepository.BACKEND_GPT)
        val draft = official.copy(
            endpoint = "https://offline.invalid/chat",
            apiKey = "offline-fixture-only",
            model = "gpt-6-luna",
            requestMode = TranslationRequestMode.MANUAL,
            requestOptions = official.requestOptions.copy(outputBudget = 1024, reasoning = TranslationReasoning.LOW)
        )
        answers += "御主，我们回迦勒底吧。"
        translator.testApiSettings(draft)
        assertEquals("deepseek", settings.apiProfile.first().backend)
        settings.saveApiSettings(draft)
        assertEquals(draft, settings.apiProfile.first())

        answers += """{"dialogue":"我们回去吧。"}"""
        assertFalse(translator.translateScene(SceneTranslateInput(null, "戻りましょう。", emptyList()))
            .dialogue?.isFailure ?: true)
        assertEquals(2, requests.size)
        for (request in sentRequests) {
            assertEquals(draft.endpoint, request.url.toString())
            assertEquals("Bearer offline-fixture-only", request.headers[HttpHeaders.Authorization])
        }
        for (request in requests) {
            val body = Json.parseToJsonElement(request).jsonObject
            assertEquals("gpt-6-luna", body["model"]?.jsonPrimitive?.content)
            assertEquals("1024", body["max_completion_tokens"]?.jsonPrimitive?.content)
            assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
            assertFalse("max_tokens" in body || "temperature" in body || "top_p" in body)
        }

        val moved = draft.copy(endpoint = "https://another.invalid/chat")
        assertFailsWith<IllegalArgumentException> { translator.testApiSettings(moved) }
        assertEquals(2, requests.size)
        assertFailsWith<IllegalArgumentException> { settings.saveApiSettings(moved) }
        assertEquals(draft, settings.apiProfile.first())
        settings.saveApiSettings(moved, "https://another.invalid:443")
        assertEquals(moved, settings.apiProfile.first())
    }

    @Test(timeout = 30_000) fun bundledPresetsSendTheirExpectedFormatAndRejectTruncatedOutput() = runBlocking {
        val backends = listOf("deepseek", "zhipu", "qwen", "gpt", "gemini", "claude", "custom_openai")
        for (backend in backends) {
            val preset = settings.recommendedApiProfile(backend)
            val profile = preset.copy(
                endpoint = preset.endpoint.ifBlank { "https://offline.invalid/custom" },
                model = preset.model.ifBlank { "fixture" },
                apiKey = "offline-fixture-only"
            )
            val isClaude = profile.requestOptions.format == TranslationApiFormat.ANTHROPIC_MESSAGES
            for (content in listOf("null", Json.encodeToString("御主，我们回"))) {
                rawAnswers += if (isClaude) {
                    """{"stop_reason":"max_tokens","content":[{"type":"text","text":$content}]}"""
                } else {
                    """{"choices":[{"message":{"content":$content},"finish_reason":"length"}]}"""
                }
                assertFailsWith<ApiOutputLimitException> { translator.testApiSettings(profile) }
                val request = sentRequests.last()
                val body = Json.parseToJsonElement(requests.last()).jsonObject
                val field = if (backend == "gpt") "max_completion_tokens" else "max_tokens"
                assertEquals(setOf(field), body.keys.intersect(setOf("max_tokens", "max_completion_tokens")))
                assertEquals(if (backend in setOf("gpt", "gemini")) "512" else "96",
                    body[field]?.jsonPrimitive?.content)
                assertEquals(if (backend in setOf("gpt", "gemini")) "low" else null,
                    body["reasoning_effort"]?.jsonPrimitive?.content)
                assertEquals(backend == "zhipu" || backend == "deepseek", "thinking" in body)
                if (isClaude) {
                    assertEquals(profile.apiKey, request.headers["x-api-key"])
                    assertEquals("2023-06-01", request.headers["anthropic-version"])
                    assertTrue("system" in body)
                } else {
                    assertEquals("Bearer ${profile.apiKey}", request.headers[HttpHeaders.Authorization])
                }
            }
        }
        assertEquals(backends.size * 2, requests.size)
    }

    @Test(timeout = 30_000) fun automaticOpenAiBudgetChangesWithTheTask() = runBlocking {
        val profile = settings.recommendedApiProfile(SettingsRepository.BACKEND_GPT).copy(
            model = "gpt-6-luna",
            apiKey = "offline-budget-fixture"
        )
        answers += "御主，我们回迦勒底吧。"
        translator.testApiSettings(profile)
        settings.saveApiSettings(profile)

        answers += "我们回去吧。"
        val scene = translator.translateScene(SceneTranslateInput(null, "戻りましょう。", emptyList()))
        assertFalse(scene.dialogue?.isFailure ?: true)

        answers += """{"dialogue":"去哪里？","choices":["回迦勒底","留在这里"]}"""
        val sceneWithChoices = translator.translateScene(
            SceneTranslateInput(null, "どこへ行こうか？", listOf("カルデアへ戻る", "ここに残る"))
        )
        assertFalse(sceneWithChoices.dialogue?.isFailure ?: true)
        assertEquals(listOf("512", "1024", "1536"), requests.map {
            Json.parseToJsonElement(it).jsonObject["max_completion_tokens"]?.jsonPrimitive?.content
        })
    }

    @Test fun migrationKeepsExplicitOverridesAndAlternateEndpointsInManual() {
        val gpt = settings.recommendedApiProfile(SettingsRepository.BACKEND_GPT)
        val legacyOptions = gpt.requestOptions.copy(outputBudget = 850)
        val prefs = preferencesOf(
            stringPreferencesKey("api_model_gpt") to "gpt-5.4-mini",
            stringPreferencesKey("api_base_url_gpt") to "https://gateway.invalid/v1/chat/completions",
            stringPreferencesKey("api_key_gpt") to "offline-migration-fixture",
            stringPreferencesKey("api_request_options_gpt") to Json.encodeToString(legacyOptions)
        )
        val saved = settings.readApiProfile(prefs, gpt.backend)
        assertEquals(TranslationRequestMode.MANUAL, saved.requestMode)
        assertEquals("gpt-5.4-mini", saved.model)
        assertEquals(850, saved.resolved().requestOptions.outputBudget)
        assertFailsWith<IllegalArgumentException> {
            saved.copy(requestMode = TranslationRequestMode.AUTO).resolved()
        }
    }

    @Test(timeout = 30_000) fun autoStoresTheManualOverrideForSwitchingBack() = runBlocking {
        val gpt = settings.recommendedApiProfile(SettingsRepository.BACKEND_GPT)
        val profile = gpt.copy(
            model = "gpt-5.4-mini",
            apiKey = "offline-switch-fixture",
            requestOptions = gpt.requestOptions.copy(
                outputBudget = 850,
                outputLimit = TranslationOutputLimit.MAX_TOKENS
            )
        )
        settings.saveApiSettings(profile)
        val saved = settings.getApiProfile(profile.backend)
        assertEquals(TranslationRequestMode.AUTO, saved.requestMode)
        assertEquals(850, saved.requestOptions.outputBudget)
        assertNull(saved.resolved().requestOptions.outputBudget)
        assertEquals(TranslationOutputLimit.MAX_COMPLETION_TOKENS, saved.resolved().requestOptions.outputLimit)
        val manual = saved.copy(requestMode = TranslationRequestMode.MANUAL)
        settings.saveApiSettings(manual)
        assertEquals(manual, settings.getApiProfile(manual.backend))
    }

    @Test(timeout = 30_000) fun denseCropAndSubtitlesKeepTheirOwnAutomaticLimits() = runBlocking {
        val profile = settings.recommendedApiProfile(SettingsRepository.BACKEND_GPT).copy(
            apiKey = "offline-mode-budget-fixture"
        )
        settings.saveApiSettings(profile)
        settings.setTargetLanguage("en")
        val sourceRow = "カルデアの記録を確認しましょう。"
        val source = List(20) { sourceRow }.joinToString("\n")
        answers += Json.encodeToString(List(20) { "Let's check Chaldea's records." })
        assertFalse(translator.translate(source, cropMode = true, maxTokens = 512,
            useTranslationCache = false).isFailure)

        answers += "Let's go back."
        assertFalse(translator.translate(
            "戻りましょう。",
            promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE,
            maxTokens = 512,
            useTranslationCache = false
        ).isFailure)
        assertEquals(listOf("1024", "512"), requests.map {
            Json.parseToJsonElement(it).jsonObject["max_completion_tokens"]?.jsonPrimitive?.content
        })
    }

    @Test(timeout = 30_000) fun correctedSceneAndStandaloneNamesUseTheStoredShortTargetBeforeAnyApi() = runBlocking {
        for ((target, expected) in listOf("zh-Hans" to "尼基季奇", "zh-Hant" to "尼基季奇", "en" to "Nikitich")) {
            settings.setTargetLanguage(target)
            val scene = translator.translateScene(SceneTranslateInput("二キチッチ", null, emptyList()))
            assertEquals(expected, scene.name?.translatedText)
            assertEquals("character-db", scene.name?.backend)
            assertEquals(expected, translator.translate("二キチッチ", translateAsName = true).translatedText)
        }
        assertTrue(requests.isEmpty(), "known short names must not need API fallback")
    }

    @Test(timeout = 30_000) fun scenePromptGetsCorrectedRagSpeakerGenderAndRawInputStaysIntact() = runBlocking {
        val input = SceneTranslateInput("二キチッチ", "二キチッチはお茶を飲む。", emptyList())
        for ((target, name) in listOf("zh-Hans" to "尼基季奇", "zh-Hant" to "尼基季奇", "en" to "Nikitich")) {
            settings.setTargetLanguage(target)
            answers += if (target == "en") """{"dialogue":"Nikitich drinks tea."}""" else """{"dialogue":"尼基季奇喝茶。"}"""
            val result = translator.translateScene(input)
            assertEquals(name, result.name?.translatedText)
            assertFalse(result.dialogue?.isFailure ?: true)
            val request = requests.last()
            assertTrue(request.contains("ニキチッチ"))
            assertTrue(request.contains(name))
            assertTrue(request.contains("女性") || request.contains("female"), request)
            assertFalse(request.contains("二キチッチ"))
        }
        assertEquals(3, requests.size)
        assertEquals("二キチッチ", input.name)
        assertEquals("二キチッチはお茶を飲む。", input.dialogue)
    }

    @Test(timeout = 30_000) fun singleAndBatchRequestsUseCorrectedSourceAndCorrectedCacheOnRepeat() = runBlocking {
        answers += "尼基季奇喝茶。"
        val first = translator.translate("二キチッチはお茶を飲む。")
        assertFalse(first.isFailure)
        val second = translator.translate("二キチッチはお茶を飲む。")
        assertTrue(second.cached)
        assertEquals(first.translatedText, second.translatedText)
        assertEquals(1, requests.size)
        assertTrue(requests.first().contains("ニキチッチ"))
        assertFalse(requests.first().contains("二キチッチ"))
        answers += "尼基季奇喝茶。"
        assertFalse(translator.translate("ニキチッチはお茶を飲む。").cached)
        assertEquals(2, requests.size, "changed requests have their own correction-policy namespace")
        assertTrue(translator.translate("ニキチッチはお茶を飲む。").cached)
        answers += """["尼基季奇，再喝一杯。"]"""
        val batch = translator.translateBatch(listOf("二キチッチ、もう一杯。"))
        assertFalse(batch.single().isFailure)
        assertTrue(requests.last().contains("ニキチッチ"))
        assertFalse(requests.last().contains("二キチッチ"))
    }

    @Test(timeout = 30_000) fun sakuraReceivesTheSameCorrectedSourceWithoutAnExtraRepairInstruction() = runBlocking {
        settings.saveApiSettings(settings.recommendedApiProfile("deepseek").copy(
            apiKey = "offline-fixture-only", model = "sakura-14b-qwen2.5-v1.0"
        ))
        answers += "尼基季奇喝茶。"
        assertFalse(translator.translate("二キチッチはお茶を飲む。").isFailure)
        assertTrue(requests.single().contains("ニキチッチ"))
        assertFalse(requests.single().contains("二キチッチ"))
        assertFalse(requests.single().contains("ocr-glossary"), "correction policy belongs in keys, not prompts")
    }

    @Test(timeout = 30_000) fun oldWrongNameCacheCannotOverrideCorrectedDatabaseHit() = runBlocking {
        // Reproduce the old path with repair disabled, using real cache keys and writes.
        val cache = Translator::class.java.getDeclaredField("glossaryOcrCorrector")
            .apply { isAccessible = true }.get(translator) as GlossaryOcrCorrectorCache
        GlossaryOcrCorrectorCache::class.java.getDeclaredField("cached").apply { isAccessible = true }
            .set(cache, GlossaryOcrCorrector(emptyList(), emptyList()))
        answers += """{"name":"二基奇奇"}"""
        val input = SceneTranslateInput("二キチッチ", null, emptyList())
        assertEquals("二基奇奇", translator.translateScene(input).name?.translatedText)
        assertTrue(translator.translateScene(input).name?.cached == true)
        assertEquals(1, requests.size)
        cache.clear()
        val fixed = translator.translateScene(input).name
        assertEquals("尼基季奇", fixed?.translatedText)
        assertEquals("character-db", fixed?.backend)
        assertEquals(1, requests.size, "repair must bypass the real poisoned cache without another API call")
    }

    private fun <T : Any> dao(type: Class<T>, names: List<Any>): T = requireNotNull(type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)
    ) { _, method, _ -> when (method.name) {
        "getAllCharacterNames" -> names
        "getAllTerms" -> emptyList<TermEntity>()
        else -> error("Unexpected DAO call ${method.name}")
    } }))
}
