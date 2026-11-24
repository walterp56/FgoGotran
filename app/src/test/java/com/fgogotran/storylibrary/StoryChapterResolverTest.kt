package com.fgogotran.storylibrary

import android.database.sqlite.SQLiteDatabase
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.translation.SceneTranslateInput
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Scene-level contract: one frame carries a speaker plate and a dialogue line, and the
 * matched pack row carries both answers. The line decides the speaker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StoryChapterResolverTest {

    private lateinit var settings: SettingsRepository
    private lateinit var storage: StoryChapterStorage
    private lateinit var repository: StoryChapterRepository
    private lateinit var resolver: StoryChapterResolver
    private var previousLanguage = ""

    @Before
    fun setUp() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val local = Proxy.newProxyInstance(
            LocalGlossaryDao::class.java.classLoader,
            arrayOf(LocalGlossaryDao::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAllCharacterNames", "getAllTerms" -> emptyList<Any>()
                else -> error("Unexpected DAO call ${method.name}")
            }
        } as LocalGlossaryDao
        settings = SettingsRepository(app, local)
        previousLanguage = settings.targetLanguage.first()
        storage = StoryChapterStorage(app)
        val installed = storage.chapterFile(CHAPTER)
        buildDatabase(installed)
        settings.setStoryChapter(CHAPTER, "test", storage.sha256(installed))
        settings.setStoryChapterDbEnabled(true)
        settings.setTargetLanguage("zh-Hant")
        repository = StoryChapterRepository(settings, storage)
        resolver = StoryChapterResolver(app, settings, repository)
    }

    @After
    fun tearDown() = runBlocking {
        // Settings share one DataStore across this sandbox, so leaving the pack enabled
        // makes every later test believe a chapter is active but missing.
        if (!::settings.isInitialized) return@runBlocking
        settings.clearStoryChapter()
        settings.setStoryChapterDbEnabled(false)
        settings.setTargetLanguage(previousLanguage)
        if (!::storage.isInitialized) return@runBlocking
        // The repository keeps the SQLite handle; Windows refuses to delete an open file.
        if (::repository.isInitialized) repository.invalidateActiveDatabase()
        storage.deleteChapter(CHAPTER)
    }

    /** Real capture: the plate is unreadable but the line resolved from the pack. */
    @Test
    fun `a resolved line supplies its speaker when the plate does not resolve`() = runBlocking {
        val scene = resolver.translateScene(SceneTranslateInput("謎之声", "スラニョっ！？", emptyList()))!!
        assertEquals("史萊姆阿信", scene.name?.translatedText)
        assertEquals("史萊諾！？", scene.dialogue?.translatedText)
    }

    /**
     * Real capture: the OCR plate resolved to a different character than the row the line
     * came from. Rendering the plate's answer would contradict the dialogue, so the row
     * wins.
     */
    @Test
    fun `the matched line wins over a plate that resolved elsewhere`() = runBlocking {
        val scene = resolver.translateScene(SceneTranslateInput("オルガマリー", "スラニョっ！？", emptyList()))!!
        assertEquals("史萊姆阿信", scene.name?.translatedText)
        assertEquals("史萊諾！？", scene.dialogue?.translatedText)
    }

    @Test
    fun `a missed line never invents a speaker`() = runBlocking {
        val scene = resolver.translateScene(SceneTranslateInput("謎之声", "まったく別の文章です", emptyList()))!!
        assertNull(scene.name)
        assertNull(scene.dialogue)
    }

    @Test
    fun `a name-only request keeps the plate translation`() = runBlocking {
        val scene = resolver.translateScene(SceneTranslateInput("オルガマリー", null, emptyList()))!!
        assertEquals("奧爾嘉瑪麗", scene.name?.translatedText)
        assertNull(scene.dialogue)
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
            meta(raw, "chapter_id", CHAPTER)
            meta(raw, "content_version", "test")
            meta(raw, "status", "finished")
            meta(raw, "entry_count", "2")
            insert(raw, 1, "スラノッブ", "スラニョっ！？", "史萊姆阿信", "史萊諾！？")
            insert(raw, 2, "オルガマリー", "そうですね。", "奧爾嘉瑪麗", "說得也是。")
        } finally {
            raw.close()
        }
    }

    private fun meta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT INTO meta(key, value) VALUES(?, ?)", arrayOf(key, value))
    }

    private fun insert(
        db: SQLiteDatabase,
        seq: Int,
        name: String,
        jp: String,
        nameTarget: String,
        dialogTarget: String
    ) {
        val norm = StoryOcrNormalizer.normalize(jp)
        db.execSQL(
            "INSERT INTO entries(entry_id, seq, script_id, scene_id, position, type, branch_id, " +
                "choice_group, choice_slot, jp_name, jp_dialog, jp_norm, jp_hash, cn_name, cn_dialog, " +
                "tw_name, tw_dialog, en_name, en_dialog) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "e$seq", seq, "s1", "scene-1", seq, "dialogue", null, null, null,
                name, jp, norm, StoryOcrNormalizer.sha1(norm),
                nameTarget, dialogTarget, nameTarget, dialogTarget, nameTarget, dialogTarget
            )
        )
    }

    private companion object {
        const val CHAPTER = "event-test"
    }
}
