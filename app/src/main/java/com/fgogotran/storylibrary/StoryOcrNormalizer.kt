package com.fgogotran.storylibrary

import com.fgogotran.scriptmemory.ScriptMemory
import com.fgogotran.text.KanaFold
import java.security.MessageDigest
import java.text.Normalizer

/**
 * OCR normalization for the downloaded chapter database.
 *
 * This is the app-side half of the `fgogotran-ocr-v2` contract produced by
 * `story_builder/build-story-db.mjs`: the gender control keeps the masculine side,
 * the player-name slot keeps a token, ruby readings are dropped, then NFKC,
 * whitespace, the FGO punctuation set, and bar characters (`― — ─ ━`, the
 * `[line N]` rule the game paints) are removed. Both the builder and this object
 * must change together; a mismatch would silently break exact lookup.
 *
 * A player-name slot (`[%1]`) survives normalization as a `%1` token, which the
 * rendered screen never shows, so those lines are matched through [slotKey] and
 * [slotTemplateKey] instead of the exact hash.
 */
object StoryOcrNormalizer {
    private val genderControl = Regex("\\[&([^:\\]]+):([^\\]]+)\\]")
    private val masterName = Regex("\\[%(\\d+)\\]")
    private val masterNameToken = Regex("%\\d+")
    private val ruby = Regex("〈[^〉]*〉")
    private val spaces = Regex("[\\s\\u3000]+")
    private val punctuation = Regex("[。、，．,.！？!?…‥・：:；;「」『』（）()\\[\\]［］〈〉《》\\u2015\\u2014\\u2500\\u2501]")

    // OCR and the JP source disagree about dash width far more often than about any
    // other character class: NFKC folds `～` (U+FF5E) to ASCII `~` but leaves `〜`
    // (U+301C) untouched, so the exact hash silently misses otherwise identical
    // lines. The same pass folds the long-vowel mark onto the kanji OCR keeps
    // reading it as (`ー` seen as `一`, e.g. `ほーん` captured as `ほ一ん`).
    private val waveDashes = Regex("[\\u301C\\uFF5E\\u007E\\u223C]")
    private val horizontalBars = Regex("[\\u2015\\u2014\\u2500\\u2501\\u2212\\uFF0D]")

    fun normalize(text: String): String = text
        .replace(genderControl, "$1")
        .replace(masterName, "%$1")
        .replace(ruby, "")
        .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        .replace(spaces, "")
        .replace(punctuation, "")

    /** SHA-1 of [normalize], or an empty string when nothing visible remains. */
    fun hash(text: String): String {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return ""
        return sha1(normalized)
    }

    /**
     * Lookup key for approximate matching: [normalize] with dash width and small
     * katakana folded.
     *
     * The exact [hash] contract is unchanged so already-published databases keep
     * matching; this key is app-side only.
     */
    fun fuzzyKey(text: String): String = foldKey(normalize(text))

    /** Folds a [normalize]d string; exposed so the builder/test side can mirror it. */
    fun foldKey(normalized: String): String = KanaFold.smallKatakana(
        normalized
            .replace(waveDashes, "~")
            .replace(horizontalBars, "ー")
            .replace('一', 'ー')
    )

    /**
     * Key for a line whose `[%1]` slot the game filled with the player's name.
     *
     * The database stores the source template (`…きた%1でーす`), but OCR reads the
     * rendered line (`…きた九条京香でーす`), so neither [hash] nor [fuzzyKey] can
     * ever match it. Removing the rendered name from the capture puts the capture
     * on the same template as [slotTemplateKey] of the stored row.
     *
     * [playerName] falls back to the name the renderer uses for an unset name, so
     * a default-named player matches too.
     */
    fun slotKey(text: String, playerName: String): String {
        val key = normalize(text)
        val name = normalize(playerName.ifBlank { ScriptMemory.DEFAULT_MASTER_NAME })
        return foldKey(if (name.isEmpty()) key else key.replace(name, ""))
    }

    /** Template side of [slotKey]: the stored `jp_norm` with its `%N` slots removed. */
    fun slotTemplateKey(storedNorm: String): String = foldKey(storedNorm.replace(masterNameToken, ""))

    /**
     * Speaker key for the pack, folded the same way as [fuzzyKey].
     *
     * A name plate is short and read by the same OCR, so it suffers the same small
     * katakana promotion (`カッツ` captured as `カツツ`, `ノッブ` as `ノツブ`). Those
     * names are three characters, below [StoryFuzzyMatcher]'s approximate minimum,
     * so the fold is the only rule that can recover them; the name-box lookup in
     * `Translator.normalizeNameLookup` folds the yōon kana for the same reason.
     */
    fun nameKey(name: String): String = foldKey(normalizeName(name))

    /** Speaker key; NFKC folds full-width punctuation before the exact name lookup. */
    fun normalizeName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
        .replace(spaces, "")
        .trim()

    fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
