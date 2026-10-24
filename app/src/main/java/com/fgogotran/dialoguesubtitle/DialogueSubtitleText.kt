package com.fgogotran.dialoguesubtitle

data class DialogueSubtitleTextLine(
    val text: String, val left: Float, val top: Float,
    val right: Float, val bottom: Float, val confidence: Float
)

data class DialogueSubtitleRow(val text: String, val top: Float, val bottom: Float, val confidence: Float)

/** Reference-crop coordinates. No battle HUD blacklist or fixed story ruby bands. */
object DialogueSubtitleText {
    fun isExpressivePunctuationOnly(text: String): Boolean = text.isNotBlank() &&
        text.all { it.isWhitespace() || it in PUNCTUATION }

    fun extract(lines: List<DialogueSubtitleTextLine>): List<DialogueSubtitleRow> {
        val groups = mutableListOf<MutableList<DialogueSubtitleTextLine>>()
        val main = lines.filter {
            it.text.isNotBlank() && it.confidence >= 0.55f &&
                it.bottom - it.top in 22f..72f && it.top >= 0 &&
                it.bottom <= DialogueSubtitleLayout.text.height + 4
        }.sortedWith(compareBy<DialogueSubtitleTextLine> { it.top }.thenBy { it.left })
        for (line in main) {
            val row = groups.firstOrNull { group ->
                val anchor = group.first()
                minOf(anchor.bottom, line.bottom) - maxOf(anchor.top, line.top) >=
                    minOf(anchor.bottom - anchor.top, line.bottom - line.top) * 0.5f
            }
            if (row == null) groups += mutableListOf(line) else row += line
        }
        // More than two main rows is not this panel. In particular, don't translate voice-list items.
        if (groups.size !in 1..2) return emptyList()
        for (fragment in lines.filter { it !in main }) {
            val text = fragment.text.trim()
            if (fragment.confidence < 0.55f || text.isEmpty() ||
                text.any { it !in PUNCTUATION } || fragment.bottom - fragment.top !in 3f..72f) continue
            groups.firstOrNull { group -> group.any { anchor ->
                val center = (fragment.top + fragment.bottom) / 2
                center in anchor.top..anchor.bottom &&
                    maxOf(anchor.left - fragment.right, fragment.left - anchor.right) <= anchor.bottom - anchor.top
            } }?.add(fragment)
        }
        return groups.map { group ->
            val ordered = group.sortedBy { it.left }
            DialogueSubtitleRow(
                ordered.joinToString("") { it.text.trim() }, group.minOf { it.top },
                group.maxOf { it.bottom }, group.map { it.confidence }.average().toFloat()
            )
        }.sortedBy { it.top }
    }

    private const val PUNCTUATION = "「」『』“”\"'‘’（）()、。，．,.！？!?…‥・：:；;—―－-〜～♪♡♥"
}
