package com.fgogotran.terminology

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fgogotran.util.FgoLogger
import java.io.File

/**
 * Room database for the pre-built FGO terminology glossary.
 *
 * ## Database strategy
 * A pre-built SQLite database (`fgo_terms.db`) is shipped in `assets/db/`.
 * On first launch, Room copies it from assets into the app's database directory.
 * If no pre-built DB exists (e.g., developer forgot to run `term_builder` scripts),
 * an empty database is created and the app still works — just without RAG term matching.
 *
 * The term DB is read-only at runtime; all writes happen offline via the Python
 * `term_builder/` scripts that fetch data from Atlas Academy API.
 */
@Database(
    entities = [TermEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TermDatabase : RoomDatabase() {

    /** Provides access to FGO term queries (exact match, fuzzy search, all terms). */
    abstract fun termDao(): TermDao

    companion object {
        private const val DB_NAME = "fgo_terms.db"
        private const val TAG = "TermDB"

        /**
         * Creates or opens the term database.
         *
         * On first run: copies `assets/db/fgo_terms.db` to the app's database directory.
         * If the asset doesn't exist, Room creates an empty database (no crash).
         */
        fun create(context: Context): TermDatabase {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) {
                try {
                    copyFromAssets(context, dbFile)
                    FgoLogger.info(TAG, "Copied term DB from assets, size=${dbFile.length()} bytes")
                } catch (e: Exception) {
                    FgoLogger.warn(TAG,
                        "No pre-built term DB in assets, creating empty one. " +
                        "Run term_builder scripts first for FGO terms.", e)
                }
            }

            val builder = Room.databaseBuilder(context, TermDatabase::class.java, DB_NAME)
            if (dbFile.exists() && dbFile.length() > 0) {
                builder.createFromFile(dbFile)
            }
            return try {
                builder.build()
            } catch (e: Exception) {
                // Schema mismatch — delete old DB so Room creates a fresh one with correct schema
                FgoLogger.warn(TAG, "Term DB schema mismatch, recreating. Rebuild with term_builder for RAG.", e)
                dbFile.delete()
                val fallbackBuilder = Room.databaseBuilder(context, TermDatabase::class.java, DB_NAME)
                fallbackBuilder.build()
            }
        }

        /** Copies the pre-built database from APK assets to the app's database directory. */
        private fun copyFromAssets(context: Context, dbFile: File) {
            dbFile.parentFile?.mkdirs()
            context.assets.open("db/$DB_NAME").use { input ->
                dbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
