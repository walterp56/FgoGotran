package com.fgogotran.ui.component

/** Authored, spoiler-free sample copy, selected by translation target rather than app UI language. */
internal data class DialoguePlaybackExample(val lines: List<String>) {
    val originalLines: List<String> = listOf("先輩、準備はいいですか？", "カルデアへ帰りましょう。")
    val text: String = lines.joinToString("\n")
    val starts: List<Int> = lines.indices.map { index -> lines.take(index).sumOf { it.length + 1 } }

    companion object {
        fun forTarget(locale: String): DialoguePlaybackExample = DialoguePlaybackExample(when (locale) {
            "en" -> listOf("Senpai, are you ready?", "Let’s head back to Chaldea.")
            "zh-Hant" -> listOf("前輩，準備好了嗎？", "我們一起回迦勒底吧。")
            else -> listOf("前辈，准备好了吗？", "我们一起回迦勒底吧。")
        })
    }
}
