package com.fgogotran.terminology

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for FGO terminology lookups.
 *
 * The terms table is populated from a pre-built SQLite database
 * shipped in assets (see [TermDatabase]).
 */
@Dao
interface TermDao {

    /**
     * Returns the official Chinese name for an exact JP term match.
     * Used as the primary (fastest) lookup path.
     */
    @Query("SELECT cn_name FROM terms WHERE jp_name = :jpText LIMIT 1")
    suspend fun findExactCn(jpText: String): String?

    /**
     * Fuzzy search: finds terms whose JP name contains [partial] as a substring.
     * Limited to 10 results to avoid overwhelming the LLM prompt.
     */
    @Query("SELECT * FROM terms WHERE jp_name LIKE '%' || :partial || '%' LIMIT 10")
    suspend fun findFuzzy(partial: String): List<TermEntity>

    /** Returns all terms in the glossary (for full-text RAG matching in [com.fgogotran.translation.PromptBuilder]). */
    @Query("SELECT * FROM terms")
    suspend fun getAllTerms(): List<TermEntity>

    /** Returns the total number of terms in the glossary. */
    @Query("SELECT COUNT(*) FROM terms")
    suspend fun count(): Int

    /** Inserts downloaded terms, replacing older rows with the same JP name. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(terms: List<TermEntity>)
}
