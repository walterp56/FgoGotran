package com.fgogotran.text

/**
 * Folds the small kana that OCR promotes to full size.
 *
 * The capture writes `スラニョっ` as `スラニヨっ`, `ノッブ` as `ノツブ`, `ジャンヌ` as
 * `ジヤンヌ`, and the dialogue `ぐっ………、` as `ぐつ……………`. One promoted glyph is a
 * large share of a short line: two kana sit under the approximate minimum, so the
 * lookup only accepts an identical folded key and the fold is the only rule that
 * can recover the line.
 *
 * Both sides of a comparison must go through it. Two genuinely different words can
 * fold together (`カッツ` and `カツツ`), so a caller that can see several candidates
 * must refuse an ambiguous fold instead of taking the first one. The small vowels
 * (`ぁぃぅぇぉ`) stay unfolded because they separate real lines (`えぇ` and `ええ`).
 */
internal object KanaFold {
    private val small = Regex("[ァィゥェォッャュョヮヵヶっゃゅょ]")
    private val folds = mapOf(
        'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ',
        'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ',
        'ヮ' to 'ワ', 'ヵ' to 'カ', 'ヶ' to 'ケ',
        'っ' to 'つ', 'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ'
    )

    /** Full-size form of every small kana in [folds]. */
    fun smallKana(text: String): String =
        text.replace(small) { folds.getValue(it.value[0]).toString() }
}
