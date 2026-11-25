package com.fgogotran.translation

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.scriptmemory.ScriptMemory
import com.fgogotran.storylibrary.StoryChapterRepository
import com.fgogotran.storylibrary.StoryChapterResolver
import com.fgogotran.storylibrary.StoryChapterStorage
import com.fgogotran.storylibrary.StoryOcrNormalizer
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The installed chapter pack answers story frames only.
 *
 * Crop, battle subtitles and voice subtitles share [Translator.translate], and the story
 * pipeline shares [Translator.translateScene]. While a chapter is installed, both entry
 * points must keep the API for every surface that is not a story frame: otherwise their
 * text either picks up a chapter line, or a miss silently swallows the API request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(io.ktor.util.InternalAPI::class)
class StoryChapterSurfaceTest {

    private lateinit var settings: SettingsRepository
    private lateinit var storage: StoryChapterStorage
    private lateinit var repository: StoryChapterRepository
    private lateinit var db: TranslationCacheDb
    private lateinit var translator: Translator
    private val requests = mutableListOf<String>()

    /** The API answer; crop mode asks for a JSON array, every other surface for plain text. */
    private var answer = "這是測試譯文。"

    @Before
    fun setUp() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val local = dao(LocalGlossaryDao::class.java, emptyList())
        val terms = dao(TermDao::class.java, emptyList())
        settings = SettingsRepository(app, local)
        settings.saveApiSettings(
            settings.recommendedApiProfile("deepseek").copy(
                apiKey = "offline-fixture-only", model = "deepseek-chat"
            )
        )
        settings.setTargetLanguage("zh-Hant")
        storage = StoryChapterStorage(app)
        val installed = storage.chapterFile(CHAPTER)
        buildDatabase(installed)
        settings.setStoryChapter(CHAPTER, "test", storage.sha256(installed))
        settings.setStoryChapterDbEnabled(true)
        repository = StoryChapterRepository(settings, storage)
        db = Room.inMemoryDatabaseBuilder(app, TranslationCacheDb::class.java).build()
        translator = Translator(
            settings, UserProfile(settings), terms, local, PromptBuilder(),
            CharacterContextRepository(app), db, TranslationMemory(app),
            DiagnosticEventStore(app), ScriptMemory(app),
            StoryChapterResolver(app, settings, repository), app
        )
        val engine = object : HttpClientEngineBase("story-chapter-surface-offline") {
            override val config = HttpClientEngineConfig()
            override val supportedCapabilities = setOf(HttpTimeout)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                requests += (data.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val body = """{"choices":[{"message":{"content":${Json.encodeToString(answer)}},"finish_reason":"stop"}]}"""
                return HttpResponseData(
                    HttpStatusCode.OK, GMTDate(),
                    headersOf(HttpHeaders.ContentType, "application/json"),
                    HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body), callContext()
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val field = Translator::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        (field.get(translator) as HttpClient).close()
        field.set(translator, client)
    }

    @After
    fun tearDown() {
        if (::translator.isInitialized) {
            runCatching {
                (Translator::class.java.getDeclaredField("httpClient")
                    .apply { isAccessible = true }.get(translator) as HttpClient).close()
            }
        }
        if (::db.isInitialized) db.close()
        if (!::settings.isInitialized) return
        runBlocking {
            settings.clearStoryChapter()
            settings.setStoryChapterDbEnabled(false)
            if (::repository.isInitialized) repository.invalidateActiveDatabase()
            if (::storage.isInitialized) storage.deleteChapter(CHAPTER)
        }
    }

    /** The crop button must ask the API even with a chapter installed. */
    @Test
    fun `crop keeps the API while a chapter is installed`() = runBlocking {
        answer = """["這是測試譯文。"]"""
        val result = translator.translate(
            PACK_LINE, cropMode = true, maxTokens = 512, useTranslationCache = false
        )
        assertNotEquals("chapter-db", result.backend)
        assertTrue(result.translatedText.contains("測試"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `battle subtitles keep the API while a chapter is installed`() = runBlocking {
        val result = translator.translate(
            PACK_LINE, promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE,
            maxApiAttempts = 1, maxTokens = 512
        )
        assertNotEquals("chapter-db", result.backend)
        assertEquals(1, requests.size)
    }

    @Test
    fun `voice subtitles keep the API while a chapter is installed`() = runBlocking {
        val result = translator.translate(
            PACK_LINE, promptProfile = TranslationPromptProfile.DIALOGUE_SUBTITLE, maxTokens = 512
        )
        assertNotEquals("chapter-db", result.backend)
        assertEquals(1, requests.size)
    }

    /** A story frame still answers from the pack, without touching the API. */
    @Test
    fun `a story line answers from the pack`() = runBlocking {
        val result = translator.translate(PACK_LINE, currentSpeaker = SPEAKER, storyChapter = true)
        assertEquals("chapter-db", result.backend)
        assertEquals(PACK_LINE_TARGET, result.translatedText)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a story scene answers from the pack`() = runBlocking {
        val scene = translator.translateScene(
            SceneTranslateInput(SPEAKER, PACK_LINE, emptyList()), storyChapter = true
        )
        assertEquals(PACK_LINE_TARGET, scene.dialogue?.translatedText)
        assertTrue(requests.isEmpty())
    }

    /** No story flag: the same scene reaches the API instead of the pack. */
    @Test
    fun `a scene without the story flag keeps the API`() = runBlocking {
        val scene = translator.translateScene(SceneTranslateInput(null, PACK_LINE, emptyList()))
        assertTrue(scene.dialogue?.translatedText.orEmpty().contains("測試"))
        assertEquals(1, requests.size)
    }

    private fun buildDatabase(file: File) {
        file.parentFile?.mkdirs()
        file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            raw.execSQL("PRAGMA user_version = 1")
            raw.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            raw.execSQL(
                """
                CREATE TABLE entries(
                  entry_id TEXT PRIMARY KEY,
                  seq INTEGER NOT NULL UNIQUE,
                  script_id TEXT NOT NULL,
                  scene_id TEXT NOT NULL,
                  position INTEGER NOT NULL,
                  type TEXT NOT NULL,
                  branch_id TEXT,
                  choice_group TEXT,
                  choice_slot TEXT,
                  jp_name TEXT NOT NULL,
                  jp_dialog TEXT NOT NULL,
                  jp_norm TEXT NOT NULL,
                  jp_hash TEXT NOT NULL,
                  cn_name TEXT NOT NULL,
                  cn_dialog TEXT NOT NULL,
                  tw_name TEXT NOT NULL,
                  tw_dialog TEXT NOT NULL,
                  en_name TEXT NOT NULL,
                  en_dialog TEXT NOT NULL
                )
                """.trimIndent()
            )
            raw.execSQL(
                "CREATE TABLE script_openings(" +
                    "script_id TEXT NOT NULL, seq INTEGER NOT NULL, jp_norm TEXT NOT NULL)"
            )
            for ((key, value) in listOf(
                "chapter_id" to CHAPTER, "content_version" to "test",
                "status" to "finished", "entry_count" to "2"
            )) {
                raw.execSQL("INSERT INTO meta(key, value) VALUES(?, ?)", arrayOf(key, value))
            }
            insert(raw, 1, SPEAKER, PACK_LINE, PACK_LINE_TARGET)
            insert(raw, 2, "", "そうか。", "這樣啊。")
        } finally {
            raw.close()
        }
    }

    private fun insert(
        db: SQLiteDatabase,
        seq: Int,
        name: String,
        jp: String,
        target: String
    ) {
        val norm = StoryOcrNormalizer.normalize(jp)
        val hash = if (norm.isEmpty()) "" else StoryOcrNormalizer.sha1(norm)
        db.execSQL(
            "INSERT INTO entries(entry_id, seq, script_id, scene_id, position, type, branch_id, " +
                "choice_group, choice_slot, jp_name, jp_dialog, jp_norm, jp_hash, cn_name, cn_dialog, " +
                "tw_name, tw_dialog, en_name, en_dialog) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "e$seq", seq, "s1", "scene-1", seq, "dialogue", null, null, null,
                name, jp, norm, hash,
                target, target, target, target, target, target
            )
        )
    }

    private fun <T : Any> dao(type: Class<T>, rows: List<Any>): T = requireNotNull(
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.name) {
                    "getAllCharacterNames" -> rows
                    "getAllTerms" -> emptyList<TermEntity>()
                    else -> error("Unexpected DAO call ${method.name}")
                }
            }
        )
    )

    private companion object {
        const val CHAPTER = "event-surface-test"
        const val SPEAKER = "才蔵"
        const val PACK_LINE = "アズサの見立てでは、この谷は二重底だ。"
        const val PACK_LINE_TARGET = "阿祖紗的看法是，這條溪谷有雙層底。"
        const val API_ANSWER = "這是測試譯文。"
    }
}
