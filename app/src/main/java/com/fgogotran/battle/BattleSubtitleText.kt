package com.fgogotran.battle

data class BattleTextLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float)

object BattleSubtitleText {
    private val japaneseText = Regex("[ぁ-ゖァ-ヺ㐀-䶿一-鿿]")
    private val level = Regex("(?i)(?:Lv[.．]?\\s*\\d|\\b(?:HP|NP|TOTAL|CRITICAL|Attack|Arts|Buster|Quick|Extra)\\b)")
    private val labels = setOf(
        "不屈の盾", "カウンター発動", "弱体無効", "宝具威力アップ",
        "化身増殖", "憎悪駆動", "霊基変速・闘争純化", "Sub Member"
    )
    private val punctuationCharacters = "「」『』“”\"'‘’（）()［］[]｛｝{}、。，．,.！？!?…‥・：:；;—―－-〜～♪"

    /**
     * Used only after extraction rejects a frame. Font-sized text at the subtitle
     * baseline can be an uncertain read, not proof of absence (notably low confidence).
     * Known HUD/status labels and small icons do not keep a pending caption alive.
     */
    fun hasUncertainSubtitle(lines: List<BattleTextLine>): Boolean = lines.any { line ->
        val text = line.text.trim()
        line.bottom - line.top in 24f..72f && line.top >= 0 && line.bottom in 115f..174f &&
            text.isNotEmpty() && !level.containsMatchIn(text) && text !in labels &&
            (text.any(Char::isLetter) || text.all { it in punctuationCharacters || it.isWhitespace() })
    }

    /** Coordinates are relative to the reference-space crop, not the physical device. */
    fun extract(lines: List<BattleTextLine>): String? {
        val plausible = lines.filter {
            it.confidence >= 0.45f && it.bottom - it.top in 24f..72f &&
                it.top >= 0 && it.bottom <= BattleLayout.subtitle.height + 4 && it.text.isNotBlank()
        }.sortedWith(compareBy<BattleTextLine> { it.top }.thenBy { it.left })
        if (plausible.isEmpty()) return null
        // OCR may split punctuation or phrases into separate boxes on the same baseline.
        // Group rows geometrically; only actual rows receive a newline.
        val rows = mutableListOf<MutableList<BattleTextLine>>()
        for (line in plausible) {
            val row = rows.firstOrNull { existing ->
                val anchor = existing.first()
                minOf(anchor.bottom, line.bottom) - maxOf(anchor.top, line.top) >=
                    minOf(anchor.bottom - anchor.top, line.bottom - line.top) * 0.5f
            }
            if (row == null) rows.add(mutableListOf(line)) else row.add(line)
        }
        if (rows.size > 2) return null
        // Detached quotes/dots can be shorter than the font. Retain them only when
        // spatially adjacent to an accepted row; never invent unobserved punctuation.
        for (fragment in lines.filter { it !in plausible }) {
            val content = fragment.text.trim()
            if (fragment.confidence < 0.45f || content.isEmpty() ||
                content.any { it !in punctuationCharacters } ||
                fragment.bottom - fragment.top !in 4f..72f) continue
            val centerY = (fragment.top + fragment.bottom) / 2f
            val row = rows.firstOrNull { row -> row.any { anchor ->
                val height = anchor.bottom - anchor.top
                centerY in anchor.top..anchor.bottom &&
                    maxOf(anchor.left - fragment.right, fragment.left - anchor.right) <= height
            } } ?: continue
            row.add(fragment)
        }
        val text = rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString("") { it.text.trim() } }
        if (text.length > 240) return null
        if (level.containsMatchIn(text) || labels.any { text.trim() == it }) return null
        // Quotes and terminal punctuation are optional OCR content, never evidence that
        // makes a candidate valid. Keep Japanese text and expressive punctuation-only
        // reactions from the fixed subtitle baseline; reject numeric/Latin HUD debris.
        val compact = text.filterNot(Char::isWhitespace)
        val expressivePunctuationOnly = compact.isNotEmpty() &&
            compact.all { it in punctuationCharacters } &&
            compact.any { it in "！？!?…‥—―〜～♪" }
        if (!japaneseText.containsMatchIn(text) && !expressivePunctuationOnly) return null
        // A font-sized line at the fixed baseline is required for all OCR candidates.
        if (plausible.none { it.bottom in 115f..174f }) return null
        return text
    }
}
