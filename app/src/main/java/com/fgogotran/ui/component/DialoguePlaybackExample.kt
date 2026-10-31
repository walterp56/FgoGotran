package com.fgogotran.ui.component

import com.fgogotran.overlay.RubyRenderText
import com.fgogotran.text.EmphasisRange

/** Original Mash-style settings dialogue, not a quotation from the game. */
internal data class DialoguePlaybackExample(val annotatedLines: List<String>, private val emphasizedWord: String) {
    val originalLines: List<String> = listOf(
        "先輩、今日の昼食〈ランチ〉はわたしがごちそうします。",
        "……食後のデザートも、昼食に含めていいですよね？")
    val originalText: String = originalLines.joinToString("\n")
    val annotatedText: String = annotatedLines.joinToString("\n")
    val lines: List<String> = annotatedLines.map { RubyRenderText.body(it) }
    // Timing counts only visible body characters, exactly as the game playback frame does.
    val text: String = lines.joinToString("\n")
    val starts: List<Int> = lines.indices.map { index -> lines.take(index).sumOf { it.length + 1 } }
    val emphasisRanges: List<EmphasisRange> = annotatedText.indexOf(emphasizedWord).let { start ->
        require(start >= 0)
        listOf(EmphasisRange(start, start + emphasizedWord.length))
    }

    fun renderText(includeRuby: Boolean): String = if (includeRuby) annotatedText else text

    companion object {
        fun forTarget(locale: String): DialoguePlaybackExample = when (locale) {
            "en" -> DialoguePlaybackExample(listOf("Senpai, today's meal〈Lunch〉 is on me.",
                "...Dessert counts as part of lunch too, right?"), "Dessert")
            "zh-Hant" -> DialoguePlaybackExample(listOf("前輩，今天的午餐〈Lunch〉就由我來請。",
                "……飯後的甜點，也算在午餐裡吧？"), "甜點")
            else -> DialoguePlaybackExample(listOf("前辈，今天的午餐〈Lunch〉就由我来请。",
                "……饭后的甜点，也算在午餐里吧？"), "甜点")
        }
    }
}
