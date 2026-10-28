package com.fgogotran.ui.component

import com.fgogotran.overlay.RubyRenderText

/** Mash's two-row activation line (Atlas script 0300053020); English retains ruby for the demo. */
internal data class DialoguePlaybackExample(val annotatedLines: List<String>) {
    val originalLines: List<String> = listOf("霊基外骨骼〈オルテナウス〉起動。", "行きます！")
    val originalText: String = originalLines.joinToString("\n")
    val annotatedText: String = annotatedLines.joinToString("\n")
    val lines: List<String> = annotatedLines.map { RubyRenderText.body(it) }
    // Timing counts only visible body characters, exactly as the game playback frame does.
    val text: String = lines.joinToString("\n")
    val starts: List<Int> = lines.indices.map { index -> lines.take(index).sumOf { it.length + 1 } }

    fun renderText(includeRuby: Boolean): String = if (includeRuby) annotatedText else text

    companion object {
        fun forTarget(locale: String): DialoguePlaybackExample = DialoguePlaybackExample(when (locale) {
            "en" -> listOf("Exoskeleton〈Ortinax〉, activate.", "Here goes!")
            "zh-Hant" -> listOf("靈基外骨骼〈奧特瑙斯〉啟動。", "出擊！")
            else -> listOf("灵基外骨骼〈奥特瑙斯〉启动。", "出击！")
        })
    }
}
