package com.fgogotran.translation

import androidx.room.*
import com.fgogotran.util.FgoLogger

/**
 * Room entity for caching JP→CN translation results.
 *
 * The [jpTextHash] (SHA-256 of the original JP text) serves as the dedup key.
 * Using a hash rather than the raw text as the primary lookup avoids storing
 * duplicate translations when the same dialogue line appears multiple times
 * (e.g., repeated story segments, grinding nodes).
 */
@Entity(
    tableName = "translation_cache",
    indices = [Index(value = ["jp_text_hash"], unique = true)]
)
data class CachedTranslation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "jp_text_hash")
    val jpTextHash: String,

    @ColumnInfo(name = "jp_text")
    val jpText: String,

    @ColumnInfo(name = "normalized_jp_text", defaultValue = "")
    val normalizedJpText: String = jpText,

    @ColumnInfo(name = "cn_text")
    val cnText: String,

    @ColumnInfo(name = "backend", defaultValue = "")
    val backend: String = "",

    @ColumnInfo(name = "prompt_version", defaultValue = "")
    val promptVersion: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Room DAO for the translation cache.
 */
@Dao
interface TranslationCacheDao {
    /** Looks up a cached translation by SHA-256 hash of the JP text. */
    @Query("SELECT cn_text FROM translation_cache WHERE jp_text_hash = :hash LIMIT 1")
    suspend fun getCached(hash: String): String?

    /** Returns newest cache entries for the history screen. */
    @Query("SELECT * FROM translation_cache ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<CachedTranslation>

    /** Inserts or replaces a cached translation (upsert by hash). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(translation: CachedTranslation)

    /** Removes cache entries older than [before] (epoch millis). */
    @Query("DELETE FROM translation_cache WHERE created_at < :before")
    suspend fun pruneOlderThan(before: Long)

    /** Returns the total number of cached entries. */
    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int
}

/**
 * Room database for persisting JP→CN translation results.
 * Separate from [com.fgogotran.terminology.TermDatabase] to keep
 * the pre-built term glossary and runtime-generated cache independent.
 */
@Database(
    entities = [CachedTranslation::class],
    version = 2,
    exportSchema = false
)
abstract class TranslationCacheDb : RoomDatabase() {
    abstract fun cacheDao(): TranslationCacheDao

    companion object {
        fun create(context: android.content.Context): TranslationCacheDb {
            FgoLogger.debug("Cache", "Creating translation cache DB")
            return Room.databaseBuilder(
                context,
                TranslationCacheDb::class.java,
                "translation_cache.db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
