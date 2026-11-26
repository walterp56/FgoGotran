package com.fgogotran.storylibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Contract test for the `fgogotran-ocr-v2` normalization shared with
 * `story_builder/build-story-db.mjs`. The fixtures below are copied verbatim from
 * the built `event-80639.db`, so a change on either side fails here.
 */
class StoryOcrNormalizerTest {

    @Test
    fun `plain dialogue matches the builder normalization`() {
        val jp = "は～い♪　ナウなヤングに大人気！"
        assertEquals("は~い♪ナウなヤングに大人気", StoryOcrNormalizer.normalize(jp))
        assertEquals("2a9057ce90da0b476f9cdd01ca784f05d617754c", StoryOcrNormalizer.hash(jp))
    }

    @Test
    fun `ruby reading is dropped`() {
        val jp = "『青城村〈あおぎむら〉』へ。"
        assertEquals("青城村へ", StoryOcrNormalizer.normalize(jp))
        assertEquals("e21a91dd38e618420e3cd93c0d65e3d387731830", StoryOcrNormalizer.hash(jp))
    }

    @Test
    fun `gender control keeps the masculine side`() {
        val jp = "手が空いたら、[&未来クン:未来チャン]の調査も手伝うから。"
        assertEquals("手が空いたら未来クンの調査も手伝うから", StoryOcrNormalizer.normalize(jp))
        assertEquals("029abc79c881d2dc130296c347882af39e28707c", StoryOcrNormalizer.hash(jp))
    }

    @Test
    fun `player name keeps a token`() {
        val jp = "特に[%1]、\n貴方は常にサーヴァントを連れること。"
        assertEquals("特に%1貴方は常にサーヴァントを連れること", StoryOcrNormalizer.normalize(jp))
        assertEquals("7365093f35b2a85b62be96e7560c21373f709736", StoryOcrNormalizer.hash(jp))
    }

    @Test
    fun `punctuation only line has an empty key`() {
        assertEquals("", StoryOcrNormalizer.normalize("…………。"))
        assertEquals("", StoryOcrNormalizer.hash("…………。"))
    }

    @Test
    fun `speaker normalization drops spaces`() {
        assertEquals("松永弾正", StoryOcrNormalizer.normalizeName(" 松永 弾正 "))
    }

    @Test
    fun `speaker normalization folds full width punctuation`() {
        assertEquals("新田義貞?", StoryOcrNormalizer.normalizeName("新田義貞？"))
    }

    /**
     * The name plate goes through the same OCR, so a three-character name such as
     * `ノッブ` is below the approximate minimum and is recovered only by the fold.
     */
    @Test
    fun `name key folds a promoted small katakana`() {
        assertEquals("ノッブ", StoryOcrNormalizer.normalizeName("ノッブ"))
        assertEquals("ノツブ", StoryOcrNormalizer.nameKey("ノッブ"))
        assertEquals(StoryOcrNormalizer.nameKey("ノッブ"), StoryOcrNormalizer.nameKey("ノツブ"))
    }

    /**
     * Real capture: the source line uses U+FF5E but OCR emitted U+301C. NFKC folds
     * the first to ASCII `~` and leaves the second alone, so the exact hash must stay
     * different while the approximate key lines them up.
     */
    @Test
    fun `fuzzy key folds wave dash width without changing the exact hash`() {
        val source = "は～い♪　ナウなヤングに大人気！"
        val ocr = "は〜い♪ナウなヤングに大人気！"
        assertEquals("は~い♪ナウなヤングに大人気", StoryOcrNormalizer.fuzzyKey(source))
        assertEquals(StoryOcrNormalizer.fuzzyKey(source), StoryOcrNormalizer.fuzzyKey(ocr))
        assertEquals("2a9057ce90da0b476f9cdd01ca784f05d617754c", StoryOcrNormalizer.hash(source))
        assertNotEquals(StoryOcrNormalizer.hash(source), StoryOcrNormalizer.hash(ocr))
    }

    @Test
    fun `fuzzy key folds long vowel bars`() {
        assertEquals("そうですね", StoryOcrNormalizer.foldKey("そうですね"))
        assertEquals("あーそう", StoryOcrNormalizer.foldKey("あ—そう"))
        assertEquals("あーそう", StoryOcrNormalizer.foldKey("あーそう"))
    }

    /** Real capture: the source `ほーん` was read as `ほ一ん` (long vowel as 一). */
    @Test
    fun `fuzzy key folds the long vowel mark read as kanji ichi`() {
        val source = "ほーん……。"
        val ocr = "ほ一ん……。"
        assertEquals("ほーん", StoryOcrNormalizer.fuzzyKey(source))
        assertEquals(StoryOcrNormalizer.fuzzyKey(source), StoryOcrNormalizer.fuzzyKey(ocr))
        assertNotEquals(StoryOcrNormalizer.hash(source), StoryOcrNormalizer.hash(ocr))
    }

    /**
     * Real capture: the source line is `スラニョっ！？` but the OCR returned the small
     * `ョ` as a full-size `ヨ`. The line is five characters, so the one-glyph miss
     * scores 0.5 — under the dialogue threshold — and the fold is what keeps it
     * matchable after the scene window has moved past it.
     */
    @Test
    fun `fuzzy key folds a katakana small kana read as full size`() {
        val source = "スラニョっ！？"
        val ocr = "スラニヨっ！？"
        assertEquals("スラニヨつ", StoryOcrNormalizer.fuzzyKey(source))
        assertEquals(StoryOcrNormalizer.fuzzyKey(source), StoryOcrNormalizer.fuzzyKey(ocr))
        assertNotEquals(StoryOcrNormalizer.hash(source), StoryOcrNormalizer.hash(ocr))
    }

    @Test
    fun `fuzzy key folds a katakana sokuon`() {
        assertEquals(
            StoryOcrNormalizer.fuzzyKey("スラノッブ"),
            StoryOcrNormalizer.fuzzyKey("スラノツブ")
        )
    }

    /**
     * Real capture: the source draws `これーーー、たのーーー、しいーーー！！` with long
     * vowel marks, and the OCR returned ASCII hyphens for every one of them. Three
     * glyphs per run put the line far under the dialogue threshold, so the hyphen is
     * folded onto the same mark as the bar family.
     */
    @Test
    fun `fuzzy key folds hyphens read for long vowel marks`() {
        val source = "これーーー、たのーーー、しいーーー！！"
        val ocr = "これ---、たの---、しい---！！"
        assertEquals("これーーーたのーーーしいーーー", StoryOcrNormalizer.fuzzyKey(source))
        assertEquals(StoryOcrNormalizer.fuzzyKey(source), StoryOcrNormalizer.fuzzyKey(ocr))
        assertNotEquals(StoryOcrNormalizer.hash(source), StoryOcrNormalizer.hash(ocr))
    }

    /** A real hyphen inside a word still folds, so both sides keep agreeing. */
    @Test
    fun `fuzzy key folds hyphens inside ordinary words`() {
        assertEquals(
            StoryOcrNormalizer.fuzzyKey("ヤタガラスMG-01"),
            StoryOcrNormalizer.fuzzyKey("ヤタガラスMGー01")
        )
    }

    /**
     * Real capture: the source draws `ぐっ………、` and the OCR returned `ぐつ……………`.
     * Two characters sit under the approximate minimum, so the lookup only accepts
     * an identical folded key; the fold is what recovers the line.
     */
    @Test
    fun `fuzzy key folds a hiragana sokuon`() {
        assertEquals("ぐつ", StoryOcrNormalizer.fuzzyKey("ぐっ………、"))
        assertEquals(
            StoryOcrNormalizer.fuzzyKey("ぐっ………、"),
            StoryOcrNormalizer.fuzzyKey("ぐつ……………")
        )
        assertNotEquals(
            StoryOcrNormalizer.hash("ぐっ………、"),
            StoryOcrNormalizer.hash("ぐつ……………")
        )
    }

    /**
     * Real capture: the game draws `はぁ、はぁ、はぁ……。` and the OCR returned
     * `はあ、はあ、はあ……。`. Every bigram differs, so the approximate score is 0 and
     * only the fold can recover the line.
     */
    @Test
    fun `fuzzy key folds a hiragana small vowel`() {
        assertEquals("はあはあはあ", StoryOcrNormalizer.fuzzyKey("はぁ、はぁ、はぁ……。"))
        assertEquals(
            StoryOcrNormalizer.fuzzyKey("はぁ、はぁ、はぁ……。"),
            StoryOcrNormalizer.fuzzyKey("はあ、はあ、はあ……。")
        )
        assertNotEquals(
            StoryOcrNormalizer.hash("はぁ、はぁ、はぁ……。"),
            StoryOcrNormalizer.hash("はあ、はあ、はあ……。")
        )
    }

    /** The fold merges real pairs; the matcher, not the key, has to refuse the guess. */
    @Test
    fun `fuzzy key merges a small vowel pair`() {
        assertEquals(
            StoryOcrNormalizer.fuzzyKey("えぇ……。"),
            StoryOcrNormalizer.fuzzyKey("ええ……、")
        )
    }

    /**
     * Real capture: the choice is stored as `カルデア学院からきた[%1]でーす！` but the
     * game renders the player's name into the slot, so the exact hash can never
     * match. Both sides reduce to the same template instead.
     */
    @Test
    fun `master name slot key removes the rendered player name`() {
        val stored = "カルデア学院からきた[%1]でーす！"
        val ocr = "カルデア学院からきた九条京香でーす！"
        val template = StoryOcrNormalizer.slotTemplateKey(StoryOcrNormalizer.normalize(stored))
        assertEquals("カルデア学院からきたでーす", template)
        assertEquals(template, StoryOcrNormalizer.slotKey(ocr, "九条京香"))
        assertNotEquals(StoryOcrNormalizer.hash(stored), StoryOcrNormalizer.hash(ocr))
    }

    @Test
    fun `master name slot key falls back to the default name`() {
        assertEquals("のう", StoryOcrNormalizer.slotKey("のう、藤丸立香。", ""))
        assertEquals("のう", StoryOcrNormalizer.slotKey("のう、藤丸立香。", "  "))
    }

    /** `[line N]` is stored as N bars in the table but is a draw command, not text. */
    @Test
    fun `line rule bars do not change the matching key`() {
        val plain = "『禁門』だ。"
        val withRule = "―――『禁門』だ。"
        assertEquals("禁門だ", StoryOcrNormalizer.normalize(withRule))
        assertEquals(StoryOcrNormalizer.hash(plain), StoryOcrNormalizer.hash(withRule))
        assertEquals(StoryOcrNormalizer.fuzzyKey(plain), StoryOcrNormalizer.fuzzyKey(withRule))
    }
}
