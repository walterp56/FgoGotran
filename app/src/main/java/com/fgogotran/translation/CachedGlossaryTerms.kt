package com.fgogotran.translation

import com.fgogotran.terminology.TermEntity

/** Stored targets and metadata take precedence over compatibility name-part fallbacks. */
internal fun mergeCachedGlossaryTerms(
    termRows: List<TermEntity>,
    characterEntries: List<TermEntity>
): List<TermEntity> {
    val (storedNames, fallbackParts) = characterEntries.partition { it.category == "character" }
    return (termRows + storedNames + fallbackParts)
        .distinctBy { TextNormalizer.normalizeForTranslation(it.jpTerm) }
}
