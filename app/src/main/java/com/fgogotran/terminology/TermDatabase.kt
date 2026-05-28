package com.fgogotran.terminology

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fgogotran.util.FgoLogger
import java.io.File
import java.security.MessageDigest

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
    entities = [TermEntity::class, CharacterNameEntity::class],
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
         * Copies `assets/db/fgo_terms.db` to the app's database directory when missing
         * or when the bundled asset has changed. This keeps installed apps from being
         * stuck on an older glossary after an APK update.
         */
        fun create(context: Context): TermDatabase {
            val dbFile = context.getDatabasePath(DB_NAME)
            try {
                refreshFromAssetsIfChanged(context, dbFile)
            } catch (e: Exception) {
                FgoLogger.warn(TAG,
                    "No pre-built term DB in assets, creating empty one. " +
                    "Run term_builder scripts first for FGO terms.", e)
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

        private fun refreshFromAssetsIfChanged(context: Context, dbFile: File) {
            val tempFile = File(dbFile.parentFile, "$DB_NAME.asset")
            copyFromAssets(context, tempFile)

            val shouldReplace = !dbFile.exists() ||
                dbFile.length() <= 0L ||
                sha256(dbFile) != sha256(tempFile)

            if (shouldReplace) {
                dbFile.parentFile?.mkdirs()
                if (dbFile.exists() && !dbFile.delete()) {
                    throw IllegalStateException("Unable to delete stale term DB: ${dbFile.absolutePath}")
                }
                if (!tempFile.renameTo(dbFile)) {
                    copyFromAssets(context, dbFile)
                    tempFile.delete()
                }
                FgoLogger.info(TAG, "Term DB refreshed from assets, size=${dbFile.length()} bytes")
            } else {
                tempFile.delete()
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
