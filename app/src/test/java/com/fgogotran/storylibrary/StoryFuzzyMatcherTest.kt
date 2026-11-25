package com.fgogotran.storylibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behaviour of the approximate matcher on real capture noise: the fixtures come
 * from an `event-80639` session where OCR disagreed with the JP source.
 */
class StoryFuzzyMatcherTest {

    @Test
    fun `wave dash width noise is an exact approximate match`() {
        val query = StoryFuzzyMatcher.query(
            StoryOcrNormalizer.fuzzyKey("は〜い♪ナウなヤングに大人気！")
        )
        val candidate = entry(1, "は～い♪　ナウなヤングに大人気！")
        assertEquals(1.0, StoryFuzzyMatcher.score(query.bigrams, candidate.bigrams), 1e-9)

        val match = StoryFuzzyMatcher.select(
            query, listOf(candidate), StoryFuzzyMatcher.DIALOGUE_THRESHOLD,
            StoryLocale.CN, null, -1
        )
        assertEquals(1, match?.entry?.id)
    }

    @Test
    fun `one wrong glyph still clears the dialogue threshold`() {
        val query = StoryFuzzyMatcher.query(
            StoryOcrNormalizer.fuzzyKey("ってか、何やらせてくれてんだっつ一の！")
        )
        val candidate = entry(1, "ってか、何やらせてくれてんだっつーの！")
        val score = StoryFuzzyMatcher.score(query.bigrams, candidate.bigrams)
        assert(score >= 0.85) { "score was $score" }

        val match = StoryFuzzyMatcher.select(
            query, listOf(candidate), StoryFuzzyMatcher.DIALOGUE_THRESHOLD,
            StoryLocale.CN, null, -1
        )
        assertEquals(1, match?.entry?.id)
    }

    @Test
    fun `unrelated text never matches`() {
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("まったく別の文章です"))
        val candidate = entry(1, "焼きまんじゅうとかいう食い物は気に入ったが、")
        assertNull(
            StoryFuzzyMatcher.select(
                query, listOf(candidate), StoryFuzzyMatcher.DIALOGUE_THRESHOLD,
                StoryLocale.CN, null, -1
            )
        )
    }

    @Test
    fun `two rival lines with different translations are rejected`() {
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("今日はいい天気ですね"))
        val pool = listOf(
            entry(1, "今日はいい天気ですわ", cn = "今天天气真好", tw = "今天天氣真好"),
            entry(2, "今日はいい天気ですの", cn = "今天天气真好呀", tw = "今天天氣真好呀")
        )
        assertNull(
            StoryFuzzyMatcher.select(
                query, pool, StoryFuzzyMatcher.DIALOGUE_THRESHOLD, StoryLocale.CN, null, -1
            )
        )
    }

    @Test
    fun `rival lines that render the same text are accepted`() {
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("今日はいい天気ですね"))
        val pool = listOf(
            entry(1, "今日はいい天気ですわ", cn = "今天天气真好", tw = "今天天氣真好"),
            entry(2, "今日はいい天気ですの", cn = "今天天气真好", tw = "今天天氣真好")
        )
        val match = StoryFuzzyMatcher.select(
            query, pool, StoryFuzzyMatcher.DIALOGUE_THRESHOLD, StoryLocale.CN, null, -1
        )
        assertEquals(1, match?.entry?.id)
    }

    @Test
    fun `flow script breaks a genuine tie`() {
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("今日はいい天気ですね"))
        val pool = listOf(
            entry(1, "今日はいい天気ですわ", script = "script-b", cn = "今天天气真好"),
            entry(2, "今日はいい天気ですの", script = "script-a", cn = "今天天气真好呀")
        )
        val match = StoryFuzzyMatcher.select(
            query, pool, StoryFuzzyMatcher.DIALOGUE_THRESHOLD, StoryLocale.CN, "script-a", 2
        )
        assertEquals(2, match?.entry?.id)
    }

    @Test
    fun `short lines only match the same folded key`() {
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("はい"))
        val pool = listOf(entry(1, "はい", cn = "是"))
        assertEquals(
            1,
            StoryFuzzyMatcher.select(
                query, pool, StoryFuzzyMatcher.DIALOGUE_THRESHOLD, StoryLocale.CN, null, -1
            )?.entry?.id
        )
        assertNull(
            StoryFuzzyMatcher.select(
                StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("はいい")),
                pool,
                StoryFuzzyMatcher.DIALOGUE_THRESHOLD,
                StoryLocale.CN,
                null,
                -1
            )
        )
    }

    @Test
    fun `speaker name tolerates one wrong glyph`() {
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.nameKey("水着ノック"))
        val pool = listOf(
            entry(
                1, "水着ノッブ", cn = "泳装阿信", tw = "泳裝阿信",
                speaker = "水着ノッブ", fold = false
            ),
            entry(
                2, "松永弾正", cn = "松永弹正", tw = "松永彈正",
                speaker = "松永弾正", fold = false
            )
        )
        val match = StoryFuzzyMatcher.select(
            query, pool, StoryFuzzyMatcher.NAME_THRESHOLD, StoryLocale.CN, null, -1
        )
        assertEquals(1, match?.entry?.id)
    }

    @Test
    fun `a mid-line fragment cannot skip ahead in the window`() {
        val window = listOf(
            entry(1, "全然関係ない文章です", cn = "完全无关的句子"),
            entry(2, "今日はとてもいい天気ですね、お嬢様", cn = "今天天气非常好呢，大小姐")
        )
        // The capture sits inside a later line rather than at its head, which is not
        // what a truncated OCR read looks like.
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("いい天気"))
        assertNull(StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN))
    }

    @Test
    fun `a half read next line resolves inside the scene window`() {
        val window = listOf(
            entry(2, "あの弾正ちゃんの素敵なお店、ボンバーショップ『ヒラグモちゃん』にようこそ～☆"),
            entry(3, "ってか、何やらせてくれてんだっつーの！")
        )
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("あの弾正ちゃんの素敵"))
        val match = StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)
        assertEquals(2, match?.entry?.id)
    }

    @Test
    fun `skipping the next line needs a clearly stronger score`() {
        val next = entry(2, "全然関係ない文章です", cn = "完全无关的句子")
        val later = entry(3, "かきくけこさしすせそ", cn = "后面的句子")
        val weak = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("かきくけこあいう"))
        assertNull(StoryFuzzyMatcher.selectInWindow(weak, listOf(next, later), StoryLocale.CN))

        val strong = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("かきくけこさしすせそ"))
        assertEquals(
            3,
            StoryFuzzyMatcher.selectInWindow(strong, listOf(next, later), StoryLocale.CN)?.entry?.id
        )
    }

    @Test
    fun `rival window lines with the same rendering are accepted`() {
        val window = listOf(
            entry(1, "今日はいい天気ですわ", cn = "今天天气真好"),
            entry(2, "今日はいい天気ですの", cn = "今天天气真好")
        )
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("今日はいい天気ですね"))
        assertEquals(
            1,
            StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)?.entry?.id
        )
    }

    @Test
    fun `short next line absorbs one glyph error in the scene window`() {
        val window = listOf(entry(1, "ほーん"), entry(2, "どうしました姉上？"))
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("ほ一ん……。"))
        assertEquals(
            1,
            StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)?.entry?.id
        )
    }

    @Test
    fun `short next line absorbs one dropped character`() {
        val window = listOf(entry(1, "そうだね"))
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("そうだ"))
        assertEquals(
            1,
            StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)?.entry?.id
        )
    }

    @Test
    fun `two character lines still need an exact window match`() {
        val window = listOf(entry(1, "はい"))
        assertNull(
            StoryFuzzyMatcher.selectInWindow(
                StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("はし")),
                window,
                StoryLocale.CN
            )
        )
    }

    @Test
    fun `a short exact line further ahead still wins in the window`() {
        val window = listOf(entry(1, "はい"), entry(2, "そうだ"))
        val query = StoryFuzzyMatcher.query(StoryOcrNormalizer.fuzzyKey("そうだ"))
        assertEquals(
            2,
            StoryFuzzyMatcher.selectInWindow(query, window, StoryLocale.CN)?.entry?.id
        )
    }

    private fun entry(
        id: Int,
        jp: String,
        cn: String = "译文$id",
        tw: String = "譯文$id",
        en: String = "line $id",
        speaker: String = "",
        script: String = "script",
        fold: Boolean = true
    ): StoryFuzzyMatcher.Entry = StoryFuzzyMatcher.Entry(
        id = id,
        // Dialogue and choices use the folded lookup key; speaker names use the name
        // key, which folds the same kana after `normalizeName`.
        key = if (fold) StoryOcrNormalizer.fuzzyKey(jp) else StoryOcrNormalizer.nameKey(jp),
        speakerKey = speaker,
        scriptId = script,
        seq = id,
        cn = cn,
        tw = tw,
        en = en
    )
}
