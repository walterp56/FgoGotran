package com.fgogotran.terminology

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TermDao {

    @Query("SELECT * FROM character_names")
    suspend fun getAllCharacterNames(): List<CharacterNameEntity>

    @Query("SELECT * FROM terms")
    suspend fun getAllTerms(): List<TermEntity>

    @Query("SELECT cn_name FROM character_names WHERE jp_name = :jpName LIMIT 1")
    suspend fun findExactCharacterName(jpName: String): String?

    @Query("SELECT cn_term FROM terms WHERE jp_term = :jpTerm LIMIT 1")
    suspend fun findExactTerm(jpTerm: String): String?

    @Query("SELECT COUNT(*) FROM character_names")
    suspend fun characterNameCount(): Int

    @Query("SELECT COUNT(*) FROM terms")
    suspend fun termCount(): Int

    @Query("SELECT (SELECT COUNT(*) FROM character_names) + (SELECT COUNT(*) FROM terms)")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTerms(terms: List<TermEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterNames(names: List<CharacterNameEntity>)
}
