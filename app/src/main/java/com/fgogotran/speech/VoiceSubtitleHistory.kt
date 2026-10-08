package com.fgogotran.speech

/** Main-thread-only display history; partial results replace the current segment. */
internal class VoiceSubtitleHistory {
    private val previousSegments = ArrayDeque<String>()
    private var currentText = ""
    private var currentIsFinal = false

    fun update(text: String, isFinal: Boolean) {
        val safeText = text.trim()
        if (safeText.isEmpty()) return
        if (currentIsFinal) {
            previousSegments.addLast(currentText)
            if (previousSegments.size > PREVIOUS_SEGMENT_LIMIT) previousSegments.removeFirst()
        }
        currentText = safeText
        currentIsFinal = isFinal
    }

    fun text(): String = buildString {
        for (segment in previousSegments) append(segment).append('\n')
        append(currentText)
    }

    fun clear() {
        previousSegments.clear()
        currentText = ""
        currentIsFinal = false
    }

    private companion object {
        const val PREVIOUS_SEGMENT_LIMIT = 2
    }
}
