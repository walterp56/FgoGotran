package com.fgogotran.battle

data class BattleTextLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float)

/** One observed rendering of an FGO battle subtitle. Text is always kept verbatim. */
data class BattleSubtitleCandidate(
    val text: String,
    val confidence: Float,
    val rowCount: Int,
    val horizontalCoverage: Float
)

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
        line.bottom - line.top in FONT_HEIGHT_RANGE && line.top >= 0 && line.bottom in BASELINE_RANGE &&
            text.isNotEmpty() && !level.containsMatchIn(text) && text !in labels &&
            (text.any(Char::isLetter) || text.all { it in punctuationCharacters || it.isWhitespace() })
    }

    /** Coordinates are relative to the reference-space crop, not the physical device. */
    fun extract(lines: List<BattleTextLine>): String? = extractCandidate(lines)?.text

    /**
     * Selects the bottom subtitle row and, when present, its adjacent row above.
     * Unrelated OCR rows do not invalidate an otherwise coherent subtitle.
     */
    fun extractCandidate(lines: List<BattleTextLine>): BattleSubtitleCandidate? {
        val plausible = lines.filter {
            it.confidence >= MIN_CONFIDENCE && it.bottom - it.top in FONT_HEIGHT_RANGE &&
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
        val usableRows = rows.filter(::isDialogueRow)
        val bottomRow = usableRows
            .filter { rowBottom(it) in BASELINE_RANGE }
            .maxWithOrNull(compareBy<MutableList<BattleTextLine>> { rowQuality(it) }
                .thenBy { rowBottom(it) }) ?: return null
        val selectedRows = mutableListOf(bottomRow)
        usableRows.asSequence()
            .filter { it !== bottomRow && rowTop(it) < rowTop(bottomRow) }
            .filter { upper ->
                val gap = rowTop(bottomRow) - rowBottom(upper)
                val heightRatio = rowHeight(upper) / rowHeight(bottomRow).coerceAtLeast(1f)
                gap in UPPER_ROW_GAP_RANGE && heightRatio in 0.55f..1.8f
            }
            .maxWithOrNull(compareBy<MutableList<BattleTextLine>> { rowBottom(it) }
                .thenBy { rowQuality(it) })
            ?.let { selectedRows.add(0, it) }
        // Detached quotes/dots can be shorter than the font. Retain them only when
        // spatially adjacent to an accepted row; never invent unobserved punctuation.
        for (fragment in lines.filter { it !in plausible }) {
            val content = fragment.text.trim()
            if (fragment.confidence < MIN_CONFIDENCE || content.isEmpty() ||
                content.any { it !in punctuationCharacters } ||
                fragment.bottom - fragment.top !in 4f..72f) continue
            val centerY = (fragment.top + fragment.bottom) / 2f
            val row = selectedRows.firstOrNull { row -> row.any { anchor ->
                val height = anchor.bottom - anchor.top
                centerY in anchor.top..anchor.bottom &&
                    maxOf(anchor.left - fragment.right, fragment.left - anchor.right) <= height
            } } ?: continue
            row.add(fragment)
        }
        val text = selectedRows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString("") { it.text.trim() }
        }
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
        if (bottomRow.none { it.bottom in BASELINE_RANGE }) return null

        val evidence = selectedRows.flatten()
        val weights = evidence.map { it.text.count { char -> !char.isWhitespace() }.coerceAtLeast(1) }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val confidence = evidence.zip(weights).sumOf { (line, weight) ->
            line.confidence.toDouble() * weight
        }.div(totalWeight).toFloat().coerceIn(0f, 1f)
        val left = evidence.minOf(BattleTextLine::left)
        val right = evidence.maxOf(BattleTextLine::right)
        val coverage = ((right - left) / BattleLayout.subtitle.width.toFloat()).coerceIn(0f, 1f)
        return BattleSubtitleCandidate(text, confidence, selectedRows.size, coverage)
    }

    private fun isDialogueRow(row: MutableList<BattleTextLine>): Boolean {
        val text = row.sortedBy(BattleTextLine::left).joinToString("") { it.text.trim() }
        if (text.isEmpty() || level.containsMatchIn(text) || text in labels) return false
        val compact = text.filterNot(Char::isWhitespace)
        return japaneseText.containsMatchIn(text) || compact.isNotEmpty() &&
            compact.all { it in punctuationCharacters } && compact.any { it in "！？!?…‥—―〜～♪" }
    }

    private fun rowTop(row: List<BattleTextLine>) = row.minOf(BattleTextLine::top)
    private fun rowBottom(row: List<BattleTextLine>) = row.maxOf(BattleTextLine::bottom)
    private fun rowHeight(row: List<BattleTextLine>) = rowBottom(row) - rowTop(row)

    private fun rowQuality(row: List<BattleTextLine>): Float {
        val compactLength = row.sumOf { it.text.count { char -> !char.isWhitespace() } }.coerceAtLeast(1)
        val confidence = row.sumOf { line ->
            line.confidence.toDouble() * line.text.count { char -> !char.isWhitespace() }.coerceAtLeast(1)
        }.div(compactLength).toFloat()
        val coverage = (row.maxOf(BattleTextLine::right) - row.minOf(BattleTextLine::left)) /
            BattleLayout.subtitle.width.toFloat()
        return confidence * 10f + coverage.coerceIn(0f, 1f) + compactLength.coerceAtMost(80) / 80f
    }

    private val FONT_HEIGHT_RANGE = 24f..72f
    private val BASELINE_RANGE = 115f..174f
    private val UPPER_ROW_GAP_RANGE = -8f..65f
    private const val MIN_CONFIDENCE = 0.45f
}
