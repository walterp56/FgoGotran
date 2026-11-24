package com.fgogotran.text

/**
 * Folds the small kana that OCR promotes to full size.
 *
 * The capture writes the source `スラニョっ` as `スラニヨっ`, `ノッブ` as `ノツブ`,
 * `ジャンヌ` as `ジヤンヌ`. One promoted glyph is a large share of a short line: it
 * drops a five-character dialogue to a 0.5 similarity and a three-character plate
 * below the approximate minimum, so the name, term, and story keys all compare on
 * this fold.
 *
 * Both sides of a comparison must go through it. Two genuinely different words can
 * fold together (`カッツ` and `カツツ`), so a caller that can see several candidates
 * must refuse an ambiguous fold instead of taking the first one.
 */
internal object KanaFold {
    private val small = Regex("[ァィゥェォッャュョヮヵヶ]")
    private val folds = mapOf(
        'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ',
        'ッ' to 'ツ', 'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ',
        'ヮ' to 'ワ', 'ヵ' to 'カ', 'ヶ' to 'ケ'
    )

    /** Full-size `ア イ ウ エ オ ツ ヤ ユ ヨ ワ カ ケ` for their small forms. */
    fun smallKatakana(text: String): String =
        text.replace(small) { folds.getValue(it.value[0]).toString() }
}
