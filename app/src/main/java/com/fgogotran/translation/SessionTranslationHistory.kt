package com.fgogotran.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionTranslationEntry(
    val jpText: String,
    val cnText: String,
    val backend: String,
    val cached: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * In-memory history for the current service run only.
 */
object SessionTranslationHistory {
    private val _entries = MutableStateFlow<List<SessionTranslationEntry>>(emptyList())
    val entries: StateFlow<List<SessionTranslationEntry>> = _entries.asStateFlow()

    fun add(entry: SessionTranslationEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(100)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
