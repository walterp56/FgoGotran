package com.fgogotran.chapter.library

import com.fgogotran.chapter.packs.StoryLanguage
import com.fgogotran.chapter.packs.StoryPackPolicy
import java.util.Collections

class StoryPackSelection(val revision: Long = 0, val language: StoryLanguage = StoryLanguage.TRADITIONAL_CHINESE, archives: Collection<String> = emptyList()) {
    val archives: Set<String> = Collections.unmodifiableSet(LinkedHashSet(archives.sorted()))
    init {
        require(revision >= 0 && archives.size <= 32 && archives.distinct().size == archives.size)
        require(revision != 0L || archives.isEmpty())
        archives.forEach(StoryPackPolicy::digest)
    }
    override fun equals(other: Any?) = other is StoryPackSelection && revision == other.revision && language == other.language && archives == other.archives
    override fun hashCode() = listOf(revision, language, archives).hashCode()
}

/** Selection persistence has no installation port. Saving intent never writes FGO. */
interface StoryPackSelectionPersistence {
    fun loadSelection(): StoryPackSelection
    fun saveSelection(expected: StoryPackSelection, language: StoryLanguage, archives: Set<String>): StoryPackSelection
}
