package com.fgogotran.storylibrary

import android.database.sqlite.SQLiteDatabase
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.text.EmphasisRange
import com.fgogotran.translation.SceneTranslateInput
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * `だって……、` and `だって……！` both normalize to `だって`, so they share a hash.
     * A dialogue frame may only take the dialogue row; otherwise a button's wording
     * could appear in the dialogue box.
     */
    @Test
    fun `a dialogue frame only matches dialogue rows`() = runBlocking {
        val hit = resolver.translateLine(
            "だって……、", "村人", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("因為……、", hit.text)
    }

    /** The same collision from the other side, with no confirmed flow to lean on. */
    @Test
    fun `a choice frame without flow state matches its own row`() = runBlocking {
        val hit = resolver.translateLine(
            "だって……！", "", StoryChapterResolver.LineKind.CHOICE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("可是……！", hit.text)
    }

    @Test
    fun `a scene keeps dialogue and choice rows apart`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput(null, "だって……、", listOf("だって……！"))
        )!!
        assertEquals("因為……、", scene.dialogue?.translatedText)
        assertEquals("可是……！", scene.choices.single().translatedText)
    }

    /**
     * A choice frame is painted all-or-nothing: one plate the pack cannot answer leaves
     * the whole frame untouched, so the game's own buttons stay readable instead of a
     * mixed frame or a copy of the Japanese source.
     */
    @Test
    fun `one missed choice leaves the whole frame unpainted`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput(null, null, listOf("だって……！", "まったく別の文章です"))
        )!!
        assertTrue(scene.chapterPack)
        assertEquals(listOf("", ""), scene.choices.map { it.translatedText })
    }

    /** Every plate resolves: the frame paints all of them, source punctuation included. */
    @Test
    fun `a fully recognised choice frame paints every plate`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput(null, null, listOf("だって……！", "……"))
        )!!
        assertEquals(listOf("可是……！", "……"), scene.choices.map { it.translatedText })
    }

    /**
     * A button is short and has no speaker, so without a confirmed scene an approximate
     * hit is another scene's wording far more often than an OCR slip. Only exact rows
     * may answer there.
     */
    @Test
    fun `a choice without a confirmed scene only matches exactly`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput(null, null, listOf("うむむむむむ。"))
        )!!
        assertEquals(listOf(""), scene.choices.map { it.translatedText })
    }

    /** Once the scene is confirmed, one damaged glyph may resolve inside that scene. */
    @Test
    fun `a confirmed scene absorbs one damaged choice glyph`() = runBlocking {
        resolver.translateLine("うむむむむ。", "", StoryChapterResolver.LineKind.CHOICE)
        val scene = resolver.translateScene(
            SceneTranslateInput(null, null, listOf("うむむむむむ。"))
        )!!
        assertEquals(listOf("嗯嗯嗯嗯嗯。"), scene.choices.map { it.translatedText })
    }

    /** A miss never paints the "check the selected chapter" text, however long it repeats. */
    @Test
    fun `repeated dialogue misses stay silent`() = runBlocking {
        val first = resolver.translateScene(
            SceneTranslateInput(null, "まったく別の文章です", emptyList())
        )!!
        val second = resolver.translateScene(
            SceneTranslateInput(null, "まったく別の文章です", emptyList())
        )!!
        assertNull(first.dialogue)
        assertNull(second.dialogue)
    }

    /**
     * `……` keeps no characters after normalization, so it has no hash even though the
     * pack stores it. The stored dialog is used instead, and the frame kind still
     * separates the dialogue row from the button.
     */
    @Test
    fun `a punctuation-only line resolves from the pack`() = runBlocking {
        val hit = resolver.translateLine(
            "……", "村人", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("……", hit.text)
    }

    @Test
    fun `a punctuation-only button resolves as a button`() = runBlocking {
        val hit = resolver.translateLine(
            "……", "", StoryChapterResolver.LineKind.CHOICE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("……", hit.text)
    }

    /**
     * Two scripts contain a line that normalizes to `はい` with different renderings.
     * Without a speaker or a confirmed flow nothing separates them, and picking the
     * first would put another scene's wording next to this frame.
     */
    @Test
    fun `an ambiguous exact match stays a miss`() = runBlocking {
        val miss = resolver.translateLine(
            "はい。", "", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /**
     * The approximate lookup searches the whole pack, so a damaged line can score best
     * on another script. Taking it would move the flow there; a new scene already
     * produces one miss, which is what unlocks the switch.
     */
    @Test
    fun `the approximate lookup does not leave a confirmed script`() = runBlocking {
        val first = resolver.translateLine(
            "そうですね。", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("說得也是。", first.text)
        val miss = resolver.translateLine(
            "おはようございます、マスタ。", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /**
     * The Story Library OCR accepts a fast read only when the pack agrees with it, and the
     * probe must not move the flow: the real lookup runs a moment later.
     */
    @Test
    fun `the fast-path probe answers without changing the flow`() = runBlocking {
        assertTrue(resolver.probe("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE))
        assertFalse(resolver.probe("まったく別の文章です", "", StoryChapterResolver.LineKind.DIALOGUE))
        // No flow was established, so the ambiguous line is still a miss, not a guess.
        val miss = resolver.translateLine(
            "はい。", "", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
        // Punctuation-only reads resolve through the stored dialog as well.
        assertTrue(resolver.probe("……", "村人", StoryChapterResolver.LineKind.DIALOGUE))
        assertTrue(resolver.probe("……", "", StoryChapterResolver.LineKind.CHOICE))
    }

    /**
     * Real capture: the stylized `オオオォ……。` came back as `才才才才……。` from both
     * crops, with high confidence, and the two readings share no bigram — no score
     * threshold reaches them. The flow still identifies the frame, so the expected line
     * is taken, and the fast-path probe agrees on the first attempt.
     */
    @Test
    fun `a read the pack cannot explain resolves from the confirmed flow`() = runBlocking {
        val first = resolver.translateScene(SceneTranslateInput("", "スラニョっ！？", emptyList()))!!
        assertEquals("史萊諾！？", first.dialogue?.translatedText)
        assertTrue(resolver.probe("才才才才才", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE))
        val scene = resolver.translateScene(
            SceneTranslateInput("オルガマリー", "才才才才才", emptyList())
        )!!
        assertEquals("說得也是。", scene.dialogue?.translatedText)
        assertEquals("奧爾嘉瑪麗", scene.name?.translatedText)
    }

    /** The plate is the second axis: a different speaker means the frame is not that line. */
    @Test
    fun `the flow inference still needs the plate to agree`() = runBlocking {
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        val miss = resolver.translateLine(
            "才才才才才", "村人", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /** The length is the third axis: a shorter read is a different line, not a substitute. */
    @Test
    fun `the flow inference still needs the length to agree`() = runBlocking {
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        val miss = resolver.translateLine(
            "才才才才", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /**
     * A read the pack does store is never replaced by the expected line, even when the
     * frame kind kept it from matching: the pack explaining the text outranks the flow.
     */
    @Test
    fun `the flow inference yields to a line the pack stores`() = runBlocking {
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        val miss = resolver.translateLine(
            "うむむむむ", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /** A button never borrows the dialogue flow: it answers with a button, or with nothing. */
    @Test
    fun `a button does not take the dialogue inference`() = runBlocking {
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        val miss = resolver.translateLine(
            "才才才才才", "オルガマリー", StoryChapterResolver.LineKind.CHOICE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /** Without a freshly confirmed line the flow says nothing about the frame on screen. */
    @Test
    fun `the flow inference needs a freshly confirmed line`() = runBlocking {
        // No line has resolved yet.
        assertFalse(resolver.probe("才才才才才", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE))
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        assertTrue(resolver.probe("才才才才才", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE))
        // One miss means the reader may have skipped, so the flow no longer speaks for
        // the frame. The probe answers the same lookup without registering a miss.
        assertTrue(
            resolver.translateLine(
                "まったく別の文章です", "", StoryChapterResolver.LineKind.DIALOGUE
            ) is StoryChapterResolver.LineResult.Miss
        )
        assertFalse(resolver.probe("才才才才才", "オルガマリー", StoryChapterResolver.LineKind.DIALOGUE))
    }

    /**
     * Real capture from the event: the fast read lost the bar and read the variant kanji
     * (`一「絶劔…`), so it must fall back; the pipeline read keeps the ruby and drops the
     * kanji, and the scene opening still identifies the script.
     */
    @Test
    fun `a damaged scene opening identifies the new scene`() = runBlocking {
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        assertFalse(resolver.probe("一「絶劔！！無穹一閃』！", "沖田オルタ", StoryChapterResolver.LineKind.DIALOGUE))
        val hit = resolver.translateLine(
            "───『絶〈ぜっけん〉！！無穹一〈むきゅういっせん〉閃』！",
            "沖田オルタ",
            StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("絕劍·無穹一閃", hit.text)
    }

    /**
     * One line can normalize to a hash shared by two rows with different renderings. The
     * scene opening settles it: only one of those scripts starts with the line.
     */
    @Test
    fun `an exact line resolves through the scene it opens`() = runBlocking {
        val hit = resolver.translateLine(
            "絶剱無穹一閃", "沖田オルタ", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Hit
        assertEquals("絕劍·無穹一閃", hit.text)
    }

    /** The plate is checked before a scene is adopted; another speaker keeps the line. */
    @Test
    fun `a scene opening with another speaker stays a miss`() = runBlocking {
        val miss = resolver.translateLine(
            "───『絶〈ぜっけん〉！！無穹一〈むきゅういっせん〉閃』！",
            "村人",
            StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /** A damaged read that no opening explains still keeps the original line. */
    @Test
    fun `a read no opening explains stays a miss`() = runBlocking {
        val miss = resolver.translateLine(
            "まったく別の文章です", "", StoryChapterResolver.LineKind.DIALOGUE
        ) as StoryChapterResolver.LineResult.Miss
        assertEquals(false, miss.chapterMismatch)
    }

    /**
     * A scene change is a stored fact, not a distance: every script carries its first line
     * in `script_openings`, so a damaged read of that line enters the new scene even when
     * the flow still belongs to the previous one. A jump into a later section reads
     * exactly like the next section starting.
     */
    @Test
    fun `a new scene is entered by its stored opening line`() = runBlocking {
        resolver.translateLine("だって……、", "村人", StoryChapterResolver.LineKind.DIALOGUE)
        val scene = resolver.translateScene(
            SceneTranslateInput(null, "おはようございます、マスタ一。", emptyList())
        )!!
        assertEquals("早安，御主。", scene.dialogue?.translatedText)
    }

    /**
     * Only the scene's first line opens it. A damaged line further inside another scene is
     * still a miss, so the opening is a fact about the pack and not a general permission to
     * change scenes on a similar read.
     */
    @Test
    fun `a damaged line inside another scene stays a miss`() = runBlocking {
        resolver.translateLine("だって……、", "村人", StoryChapterResolver.LineKind.DIALOGUE)
        val scene = resolver.translateScene(
            SceneTranslateInput(null, "わたくしは、サ一ヴァントです。", emptyList())
        )!!
        assertNull(scene.dialogue)
    }

    /** A readable plate that names another character keeps the scene change unproven. */
    @Test
    fun `a plate naming another character does not open the scene`() = runBlocking {
        resolver.translateLine("だって……、", "村人", StoryChapterResolver.LineKind.DIALOGUE)
        val scene = resolver.translateScene(
            SceneTranslateInput("謎之声", "おはようございます、マスタ一。", emptyList())
        )!!
        assertNull(scene.dialogue)
    }

    /**
     * The pack marks the words the game draws emphasis dots above with `⟦…⟧`; the marks are
     * metadata, so the rendered text is plain and the spans reach the overlay that paints them.
     */
    @Test
    fun `a pack line keeps the emphasis dots the game shows`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput(null, "これは大切なことだ。", emptyList())
        )!!
        assertEquals("這是很重要的事。", scene.dialogue?.translatedText)
        assertEquals(listOf(EmphasisRange(2, 3)), scene.dialogue?.emphasisRanges)
    }

    /** A matched row carries the stored line, so bilingual display never shows OCR damage. */
    @Test
    fun `a matched line carries the stored japanese`() = runBlocking {
        val scene = resolver.translateScene(SceneTranslateInput("謎之声", "スラニョっ！？", emptyList()))!!
        assertEquals("スラニョっ！？", scene.dialogue?.sourceText)
    }

    /** Real capture: the reading arrived with broken ruby, the stored line is intact. */
    @Test
    fun `the stored japanese replaces the damaged reading`() = runBlocking {
        resolver.translateLine("スラニョっ！？", "", StoryChapterResolver.LineKind.DIALOGUE)
        val scene = resolver.translateScene(
            SceneTranslateInput(
                "沖田オルタ", "───『絶〈ぜっけん〉！！無穹一〈むきゅういっせん〉閃』！", emptyList()
            )
        )!!
        assertEquals("絕劍·無穹一閃", scene.dialogue?.translatedText)
        assertEquals("絶剱無穹一閃", scene.dialogue?.sourceText)
    }

    /** The stored line keeps `%1`; the displayed original shows the rendered player name. */
    @Test
    fun `a player-name slot renders the name in the stored japanese`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput(null, "おかえりなさい、藤丸立香。", emptyList())
        )!!
        assertEquals("おかえりなさい、藤丸立香。", scene.dialogue?.sourceText)
    }

    /** A pack row can carry the game's `[f large]`: only the render scale follows it. */
    @Test
    fun `a large line reports its render size and hides the marker`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput("沖田オルタ", "『ヤタガラス・クラーッシュ』！！！", emptyList())
        )!!
        assertEquals(DialogueSize.LARGE, scene.dialogueSize)
        assertEquals("『ヤタガラス・クラーッシュ』！！！", scene.dialogue?.sourceText)
        assertFalse(scene.dialogue?.sourceText.orEmpty().contains("⟬"))
        assertFalse(scene.dialogue?.translatedText.orEmpty().contains("⟬"))
    }

    @Test
    fun `a small line reports its render size`() = runBlocking {
        val scene = resolver.translateScene(SceneTranslateInput(null, "こそこそ話", emptyList()))!!
        assertEquals(DialogueSize.SMALL, scene.dialogueSize)
        assertEquals("こそこそ話", scene.dialogue?.sourceText)
    }

    @Test
    fun `a line without a marker keeps the default size`() = runBlocking {
        val scene = resolver.translateScene(
            SceneTranslateInput("謎之声", "スラニョっ！？", emptyList())
        )!!
        assertEquals(DialogueSize.NORMAL, scene.dialogueSize)
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
            meta(raw, "entry_count", "17")
            insert(raw, 1, "スラノッブ", "スラニョっ！？", "史萊姆阿信", "史萊諾！？")
            insert(raw, 2, "オルガマリー", "そうですね。", "奧爾嘉瑪麗", "說得也是。")
            // Both normalize to `だって`, so only the frame kind can separate them.
            insert(raw, 3, "村人", "だって……、", "村民", "因為……、")
            insert(raw, 4, "", "だって……！", "", "可是……！", type = "choice")
            // Punctuation only: normalization removes every character, so these rows
            // have no hash and are matched on the stored dialog instead.
            insert(raw, 5, "村人", "……", "村民", "……")
            insert(raw, 6, "", "……", "", "……", type = "choice")
            insert(raw, 7, "", "おはようございます、マスター。", "", "早安，御主。", script = "s2")
            // Same normalized key in two scripts with different renderings.
            insert(raw, 8, "", "はい。", "", "是。", script = "s2")
            insert(raw, 9, "", "はい！", "", "好的！", script = "s3")
            // A button that no dialogue frame may take, even when the flow infers a line
            // of the same length: the pack explaining the text outranks the inference.
            insert(raw, 10, "", "うむむむむ。", "", "嗯嗯嗯嗯嗯。", type = "choice", script = "s3")
            // The same line opens one scene and sits mid-scene in another, with different
            // renderings: only the scene opening can separate them.
            insert(raw, 11, "沖田オルタ", "絶剱無穹一閃", "沖田Alter", "絕劍·無穹一閃", script = "s4")
            insert(raw, 12, "沖田オルタ", "絶剱無穹一閃", "沖田Alter", "絕劍！！無穹一閃", script = "s5")
            // The stored line keeps the player-name slot; the answer renders the real name.
            insert(raw, 13, "", "おかえりなさい、[%1]。", "", "歡迎回來，[%1]。", script = "s6")
            // A line that is not its scene's opening: a scene change is never taken on it,
            // however closely the read matches.
            insert(raw, 14, "", "わたくしは、サーヴァントです。", "", "我是從者。", script = "s2")
            // The translation marks an emphasized word with the pack's ruby-style brackets.
            insert(raw, 15, "", "これは大切なことだ。", "", "這是⟦很⟧重要的事。", script = "s2")
            // The pack carries the game's font size as a marker. Only the dialogue render
            // scale follows it, and the marker never reaches a displayed string.
            insert(
                raw, 16, "沖田オルタ", "⟬L⟭『ヤタガラス・クラーッシュ』！！！",
                "沖田Alter", "『黑鴉・粉碎』！！！", script = "s7"
            )
            insert(raw, 17, "", "⟬S⟭こそこそ話", "", "竊竊私語", script = "s7")
            for ((script, jp, seq) in listOf(
                Triple("s1", "スラニョっ！？", 1),
                Triple("s2", "おはようございます、マスター。", 7),
                Triple("s4", "絶剱無穹一閃", 11)
            )) {
                raw.execSQL(
                    "INSERT INTO script_openings(script_id, seq, jp_norm) VALUES(?, ?, ?)",
                    arrayOf(script, seq, StoryOcrNormalizer.normalize(jp))
                )
            }
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
        dialogTarget: String,
        type: String = "dialogue",
        script: String = "s1"
    ) {
        val norm = StoryOcrNormalizer.normalize(jp)
        // The builder stores an empty hash when normalization leaves nothing; sha1("")
        // would be a real digest and would hide the row from the punctuation lookup.
        val hash = if (norm.isEmpty()) "" else StoryOcrNormalizer.sha1(norm)
        db.execSQL(
            "INSERT INTO entries(entry_id, seq, script_id, scene_id, position, type, branch_id, " +
                "choice_group, choice_slot, jp_name, jp_dialog, jp_norm, jp_hash, cn_name, cn_dialog, " +
                "tw_name, tw_dialog, en_name, en_dialog) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "e$seq", seq, script, "scene-1", seq, type, null, null, null,
                name, jp, norm, hash,
                nameTarget, dialogTarget, nameTarget, dialogTarget, nameTarget, dialogTarget
            )
        )
    }

    private companion object {
        const val CHAPTER = "event-test"
    }
}
