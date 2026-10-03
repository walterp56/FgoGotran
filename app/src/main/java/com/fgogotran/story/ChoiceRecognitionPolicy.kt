package com.fgogotran.story

/** Choice-request eligibility and completeness, including punctuation-only rows. */
internal object ChoiceRecognitionPolicy {
    /** A story advance tap is not user initiation; only the floating button requests OCR. */
    fun canRecognize(userInitiated: Boolean, fullAuto: Boolean): Boolean = userInitiated || fullAuto

    fun <T> isComplete(expectedButtons: List<T>, rows: List<Pair<T, String>>): Boolean {
        if (expectedButtons.isEmpty() || rows.size != expectedButtons.size) return false
        val expected = expectedButtons.toSet()
        if (expected.size != expectedButtons.size || rows.any { it.second.isBlank() }) return false
        val recognized = rows.map { it.first }.toSet()
        return recognized.size == rows.size && recognized == expected
    }
}
