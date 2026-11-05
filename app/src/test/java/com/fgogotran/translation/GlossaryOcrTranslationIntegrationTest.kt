package com.fgogotran.translation

import androidx.room.Room
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.diagnostic.DiagnosticEventStore
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val nikitich = CharacterNameEntity(jpName = "ニキチッチ", cnName = "尼基季奇", enName = "Nikitich", gender = "女性")

    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val local = dao(LocalGlossaryDao::class.java, emptyList<Any>())
        val terms = dao(TermDao::class.java, listOf(nikitich))
        settings = SettingsRepository(app, local)
        settings.saveApiSettings("deepseek", "offline-fixture-only", "https://api.deepseek.com", "deepseek-chat")
        settings.setTargetLanguage("zh-Hans")
        db = Room.inMemoryDatabaseBuilder(app, TranslationCacheDb::class.java).build()
        translator = Translator(settings, UserProfile(settings), terms, local, PromptBuilder(),
            CharacterContextRepository(app), db, TranslationMemory(app), DiagnosticEventStore(app), app)
        val engine = object : HttpClientEngineBase("glossary-ocr-offline") {
            override val config = HttpClientEngineConfig()
            override val supportedCapabilities = setOf(HttpTimeout)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                requests += (data.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                check(answers.isNotEmpty()) { "Unexpected API request: ${requests.last()}" }
                val response = """{"choices":[{"message":{"content":${Json.encodeToString(answers.removeFirst())}},"finish_reason":"stop"}]}"""
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
        settings.saveApiSettings("deepseek", "offline-fixture-only", "https://api.deepseek.com", "sakura-14b-qwen2.5-v1.0")
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
