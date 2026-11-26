package com.fgogotran.text

/**
 * Folds the small kana that OCR promotes to full size.
 *
 * The capture writes `スラニョっ` as `スラニヨっ`, `ノッブ` as `ノツブ`, `ジャンヌ` as
 * `ジヤンヌ`, the dialogue `ぐっ………、` as `ぐつ……………`, and the interjection
 * `はぁ、はぁ、はぁ……。` as `はあ、はあ、はあ……。`. One promoted glyph is a large
 * share of a short line: two kana sit under the approximate minimum, so the lookup
 * only accepts an identical folded key and the fold is the only rule that recovers
 * the line.
 *
 * Both sides of a comparison must go through it. The fold also merges real pairs
 * (`カッツ` and `カツツ`, `えぇ` and `ええ`), so a caller that can see several
 * candidates must refuse an ambiguous fold instead of taking the first one.
 */
internal object KanaFold {
    private val small = Regex("[ァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖ]")
    private val folds = mapOf(
        'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ',
        'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ',
        'ヮ' to 'ワ', 'ヵ' to 'カ', 'ヶ' to 'ケ',
        'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お',
        'っ' to 'つ', 'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ',
        'ゎ' to 'わ', 'ゕ' to 'か', 'ゖ' to 'け'
    )

    /** Full-size form of every small kana in [folds]. */
    fun smallKana(text: String): String =
        text.replace(small) { folds.getValue(it.value[0]).toString() }
}
