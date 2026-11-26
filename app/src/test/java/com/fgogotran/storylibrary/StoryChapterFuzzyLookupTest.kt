package com.fgogotran.storylibrary

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * End-to-end lookup against a real SQLite file with the published schema, using
 * capture fixtures from an `event-80639` session.
 */
@RunWith(RobolectricTestRunner::class)
class StoryChapterFuzzyLookupTest {

    private lateinit var file: File
    private lateinit var database: StoryChapterDatabase

    @Before
    fun setUp() {
        file = File(RuntimeEnvironment.getApplication().cacheDir, "story-fuzzy-test.db")
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
            meta(raw, "content_version", "2026.11.24.1")
            meta(raw, "status", "finished")
            meta(raw, "entry_count", "14")
            insert(
                raw, seq = 1, type = "dialogue", name = "松永弾正", script = "s1",
                jp = "は～い♪　ナウなヤングに大人気！",
                cn = "嗨～♪　在当下年轻人中超有人气！"
            )
            insert(
                raw, seq = 2, type = "dialogue", name = "水着ノッブ", script = "s1",
                jp = "焼きまんじゅうとかいう食い物は気に入ったが、",
                cn = "那个叫烤馒头的吃食我倒是挺中意，",
                cnName = "泳装阿信"
            )
            insert(
                raw, seq = 3, type = "dialogue", name = "松永弾正", script = "s1",
                jp = "ってか、何やらせてくれてんだっつーの！",
                cn = "话说，到底让我干啥啊！"
            )
            insert(
                raw, seq = 4, type = "choice", name = "", script = "s1",
                jp = "水着を買う",
                cn = "买泳装"
            )
            insert(
                raw, seq = 5, type = "dialogue", name = "沖田オルタ", script = "s1",
                jp = "行くぞ煉獄！　焼きまんじゅうパワー全開！",
                cn = "上吧炼狱！烤馒头能量全开！"
            )
            insert(
                raw, seq = 6, type = "dialogue", name = "水着ノッブ", script = "s1",
                jp = "ほーん……。",
                cn = "哦～……。",
                cnName = "泳装阿信"
            )
            insert(
                raw, seq = 7, type = "choice", name = "", script = "s2",
                jp = "カルデア学院からきた[%1]でーす！",
                cn = "我是从迦勒底学院来的[%1]——！"
            )
            insert(
                raw, seq = 8, type = "dialogue", name = "", script = "s2",
                jp = "のう、[%1]。",
                cn = "吶，[%1]。"
            )
            insert(
                raw, seq = 9, type = "dialogue", name = "壱与", script = "s2",
                jp = "あ、それなら、卑弥呼さんに払ってもらいましたんで大丈夫です。",
                cn = "啊，那個的話，已經請卑彌呼桑幫我拍掉了，所以沒問題。",
                cnName = "壹與"
            )
            insert(
                raw, seq = 10, type = "dialogue", name = "スラノッブ", script = "s3",
                jp = "スラニョっ！？",
                cn = "史萊諾！？",
                cnName = "史萊姆阿信"
            )
            insert(
                raw, seq = 11, type = "dialogue", name = "ノッブ", script = "s3",
                jp = "ノッブじゃぞ。",
                cn = "是阿信喔。",
                cnName = "阿信"
            )
            insert(
                raw, seq = 12, type = "dialogue", name = "雑賀孫一", script = "s3",
                jp = "これーーー、たのーーー、しいーーー！！",
                cn = "這個——，好——開心——！！",
                cnName = "雜賀孫一"
            )
            insert(
                raw, seq = 13, type = "dialogue", name = "久坂玄瑞", script = "s4",
                jp = "ぐっ………、",
                cn = "唔………，",
                cnName = "久坂玄瑞"
            )
            insert(
                raw, seq = 14, type = "dialogue", name = "", script = "s5",
                jp = "はぁ、はぁ、はぁ……。",
                cn = "哈啊、哈啊、哈啊……。"
            )
        } finally {
            raw.close()
        }
        database = StoryChapterDatabase.open(file, CHAPTER)
    }

    @After
    fun tearDown() {
        database.close()
        file.delete()
    }

    @Test
    fun `exact hash lookup is unchanged`() {
        val rows = database.dialogueRowsForHash(StoryOcrNormalizer.hash("は～い♪　ナウなヤングに大人気！"))
        assertEquals(1, rows.size)
        assertEquals(1, rows.first().seq)
    }

    @Test
    fun `wave dash noise matches approximately`() {
        val key = StoryOcrNormalizer.fuzzyKey("は〜い♪ナウなヤングに大人気！")
        val row = database.fuzzyDialogue(key, StoryLocale.CN, null, -1)
        assertEquals(1, row?.seq)
    }

    @Test
    fun `one wrong glyph matches approximately`() {
        val key = StoryOcrNormalizer.fuzzyKey("ってか、何やらせてくれてんだっつ一の！")
        val row = database.fuzzyDialogue(key, StoryLocale.CN, "s1", 2)
        assertEquals(3, row?.seq)
    }

    /**
     * Real capture: the source draws `ぐっ………、` and the OCR returned `ぐつ……………`.
     * Two kana are below the approximate minimum, so the fold is the only rule that
     * can recover the line.
     */
    @Test
    fun `hiragana sokuon read as full size matches`() {
        val key = StoryOcrNormalizer.fuzzyKey("ぐつ……………")
        assertEquals(13, database.fuzzyDialogue(key, StoryLocale.CN, "s4", 12)?.seq)
    }

    /**
     * Real capture: the game draws `はぁ、はぁ、はぁ……。` and the OCR returned
     * `はあ、はあ、はあ……。`. Every bigram differs, so only the small-vowel fold can
     * recover the line.
     */
    @Test
    fun `hiragana small vowel read as full size matches`() {
        val key = StoryOcrNormalizer.fuzzyKey("はあ、はあ、はあ……。")
        assertEquals(14, database.fuzzyDialogue(key, StoryLocale.CN, null, -1)?.seq)
    }

    @Test
    fun `unrelated text is rejected`() {
        val key = StoryOcrNormalizer.fuzzyKey("まったく別の文章です")
        assertNull(database.fuzzyDialogue(key, StoryLocale.CN, "s1", 2))
    }

    @Test
    fun `choice matches approximately`() {
        val key = StoryOcrNormalizer.fuzzyKey("水着を買")
        val row = database.fuzzyChoice(key, StoryLocale.CN, "s1", 3)
        assertEquals(4, row?.seq)
    }

    @Test
    fun `speaker name matches approximately`() {
        val key = StoryOcrNormalizer.nameKey("水着ノック")
        val targets = database.fuzzyNameTargets(key, StoryLocale.CN, "s1", 2)
        assertEquals("泳装阿信", targets?.cn)
        assertTrue(database.nameTargets("水着ノッブ") != null)
    }

    @Test
    fun `scene window follows story order and skips choices`() {
        val window = database.windowEntries("s1", 1, 6)
        assertEquals(listOf(2, 3, 5, 6), window.map { it.seq })

        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("焼きまんじゅうとかいう食い物"))
        val match = StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)
        assertEquals(2, match?.entry?.id)
        assertEquals(2, database.rowBySeq(match!!.entry.id)?.seq)
    }

    @Test
    fun `scene window continues from the new position`() {
        val window = database.windowEntries("s1", 2, 6)
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("ってか、何やらせて"))
        assertEquals(
            3,
            StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)?.entry?.id
        )
    }

    @Test
    fun `very short OCR line resolves in the scene window`() {
        val window = database.windowEntries("s1", 5, 6)
        assertEquals(listOf(6), window.map { it.seq })

        val key = StoryOcrNormalizer.fuzzyKey("ほ一ん……。")
        assertEquals(6, StoryFuzzyMatcher.selectInWindow(
            StoryFuzzyMatcher.query(key), window, StoryLocale.CN
        )?.entry?.id)
        assertEquals(6, database.fuzzyDialogue(key, StoryLocale.CN, "s1", 5)?.seq)
    }

    /**
     * Real capture: the pack stores `これーーー、たのーーー、しいーーー！！` and the OCR
     * read every long vowel mark as an ASCII hyphen. Folded, the key is identical, so
     * the line resolves without any flow state.
     */
    @Test
    fun `hyphens read for long vowel marks match approximately`() {
        val ocr = "これ---、たの---、しい---！！"
        val key = StoryOcrNormalizer.fuzzyKey(ocr)
        assertEquals("これーーーたのーーーしいーーー", key)
        assertEquals(12, database.fuzzyDialogue(key, StoryLocale.CN, null, -1)?.seq)
    }

    /**
     * Real capture: the pack stores `スラニョっ！？` and the OCR read the small `ョ` as a
     * full-size `ヨ`. At five characters the one-glyph miss scores 0.5, so only the
     * scene window could absorb it — and once, because that hit advances the flow past
     * the line; every later capture of the same line then fell to the 0.72 dialogue
     * threshold and rendered the wrong-chapter warning. The folded key matches with no
     * flow state at all, which is what makes a repeat capture answer the same way.
     */
    @Test
    fun `small katakana read as full size matches without any flow state`() {
        val ocr = "スラニヨっ！？"
        assertTrue(database.dialogueRowsForHash(StoryOcrNormalizer.hash(ocr)).isEmpty())
        // Once the line is confirmed, the window opens after it and never answers for it again.
        val window = database.windowEntries("s3", 10, 6)
        assertEquals(listOf(11, 12), window.map { it.seq })
        assertNull(
            StoryFuzzyMatcher.selectInWindow(
                StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey(ocr)), window, StoryLocale.CN
            )
        )

        val key = StoryOcrNormalizer.fuzzyKey(ocr)
        assertEquals(10, database.fuzzyDialogue(key, StoryLocale.CN, null, -1)?.seq)
        assertEquals(10, database.fuzzyDialogue(key, StoryLocale.CN, null, -1)?.seq)
    }

    /**
     * Real capture: the choice is stored with a `[%1]` slot, but OCR reads the
     * rendered player name, so the exact and approximate pack lookups both miss.
     * The template lookup is what recovers the row.
     */
    @Test
    fun `choice with a rendered master name matches on the template`() {
        val ocr = "カルデア学院からきた九条京香でーす！"
        assertTrue(database.choiceRowsForHash(StoryOcrNormalizer.hash(ocr)).isEmpty())
        assertTrue(database.slotRows(StoryOcrNormalizer.fuzzyKey(ocr), choice = true).isEmpty())

        val key = StoryOcrNormalizer.slotKey(ocr, "九条京香")
        assertEquals(7, database.slotRows(key, choice = true).firstOrNull()?.seq)
        assertEquals(7, database.fuzzySlot(key, StoryLocale.CN, "s2", 6, "", choice = true)?.seq)
    }

    @Test
    fun `dialogue with a rendered master name matches on the template`() {
        val ocr = "のう、九条京香。"
        assertTrue(database.dialogueRowsForHash(StoryOcrNormalizer.hash(ocr)).isEmpty())

        val key = StoryOcrNormalizer.slotKey(ocr, "九条京香")
        assertEquals(listOf(8), database.slotRows(key, choice = false).map { it.seq })
        assertEquals(8, database.fuzzySlot(key, StoryLocale.CN, "s2", 7, "", choice = false)?.seq)
    }

    @Test
    fun `slot template lookup stays inside its line type`() {
        val key = StoryOcrNormalizer.slotKey("カルデア学院からきた九条京香でーす！", "九条京香")
        assertTrue(database.slotRows(key, choice = false).isEmpty())
        assertEquals(7, database.slotRows(key, choice = true).firstOrNull()?.seq)
    }

    /**
     * Real capture: a three-character plate is below the approximate minimum, so the
     * only rule that can recover a promoted small kana there is the name key fold.
     * `ノッブ` and `カッツ` are both three characters in this event.
     */
    @Test
    fun `short speaker name tolerates a promoted small kana`() {
        assertEquals("阿信", database.nameTargets(StoryOcrNormalizer.normalizeName("ノッブ"))?.cn)
        assertEquals("阿信", database.nameTargets(StoryOcrNormalizer.normalizeName("ノツブ"))?.cn)
        assertEquals("阿信", database.prefixNameTargets(StoryOcrNormalizer.nameKey("ノツブA"))?.cn)
        // The fold is not a free edit: a different glyph still misses.
        assertNull(database.nameTargets(StoryOcrNormalizer.normalizeName("ノック")))
    }

    /**
     * Real capture: the name plate read `壱与A` while the pack stores `壱与`.
     * A two-character name is below the approximate lookup's minimum, so the
     * plate suffix rule is what recovers it.
     */
    @Test
    fun `short speaker name tolerates a plate suffix glyph`() {
        val key = StoryOcrNormalizer.nameKey("壱与A")
        assertNull(database.nameTargets(key))
        assertNull(database.fuzzyNameTargets(key, StoryLocale.CN, "s2", 8))
        assertEquals("壹與", database.prefixNameTargets(key)?.cn)
    }

    @Test
    fun `plate suffix rule rejects a longer tail`() {
        assertNull(database.prefixNameTargets(StoryOcrNormalizer.nameKey("壱与ABC")))
        assertNull(database.prefixNameTargets(StoryOcrNormalizer.nameKey("水着")))
    }

    private fun meta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT INTO meta(key, value) VALUES(?, ?)", arrayOf(key, value))
    }

    private fun insert(
        db: SQLiteDatabase,
        seq: Int,
        type: String,
        name: String,
        script: String,
        jp: String,
        cn: String,
        cnName: String = ""
    ) {
        val norm = StoryOcrNormalizer.normalize(jp)
        val targetName = cnName.ifBlank { name }
        db.execSQL(
            "INSERT INTO entries(entry_id, seq, script_id, scene_id, position, type, branch_id, " +
                "choice_group, choice_slot, jp_name, jp_dialog, jp_norm, jp_hash, cn_name, cn_dialog, " +
                "tw_name, tw_dialog, en_name, en_dialog) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(
                "e$seq", seq, script, "scene-1", seq, type, null, null, null,
                name, jp, norm, StoryOcrNormalizer.sha1(norm),
                targetName, cn, targetName, cn, targetName, cn
            )
        )
    }

    private companion object {
        const val CHAPTER = "event-test"
    }
}
