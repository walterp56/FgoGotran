package com.fgogotran.terminology

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
        private const val ONLINE_MARKER_NAME = "fgo_terms.db.online"
        private const val TAG = "TermDB"

        fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        fun markOnlineInstall(
            context: Context,
            contentVersion: String,
            sha256: String,
            generatedAt: String
        ) {
            val dbFile = databaseFile(context)
            dbFile.parentFile?.mkdirs()
            onlineMarkerFile(dbFile).writeText(
                listOf(
                    "contentVersion=$contentVersion",
                    "sha256=$sha256",
                    "generatedAt=$generatedAt"
                ).joinToString(separator = "\n", postfix = "\n"),
                Charsets.UTF_8
            )
        }

        /**
         * Creates or opens the term database.
         *
         * Copies `assets/db/fgo_terms.db` to the app's database directory when missing
         * or when the bundled asset has changed. This keeps installed apps from being
         * stuck on an older glossary after an APK update.
         */
        fun create(context: Context): TermDatabase {
            val dbFile = databaseFile(context)
            var assetAvailable = false
            try {
                refreshFromAssetsIfChanged(context, dbFile)
                assetAvailable = true
                ensureBundledDbHasRows(context, dbFile)
            } catch (e: Exception) {
                FgoLogger.warn(TAG,
                    "Term DB asset is missing or invalid, creating empty one if restore fails. " +
                    "Run term_builder scripts first for FGO terms.", e)
            }

            return try {
                Room.databaseBuilder(context, TermDatabase::class.java, DB_NAME)
                    .build()
                    .also { it.openHelper.readableDatabase }
            } catch (e: Exception) {
                FgoLogger.warn(TAG, "Term DB open failed, restoring bundled glossary.", e)
                deleteDatabaseFiles(dbFile)
                if (assetAvailable) {
                    copyFromAssets(context, dbFile)
                    ensureBundledDbHasRows(context, dbFile)
                }
                Room.databaseBuilder(context, TermDatabase::class.java, DB_NAME)
                    .build()
                    .also { it.openHelper.readableDatabase }
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
            if (shouldKeepOnlineDb(dbFile)) {
                FgoLogger.info(TAG, "Term DB is online-installed; skipping asset refresh")
                return
            }

            val tempFile = File(dbFile.parentFile, "$DB_NAME.asset")
            copyFromAssets(context, tempFile)

            val shouldReplace = !dbFile.exists() ||
                dbFile.length() <= 0L ||
                sha256(dbFile) != sha256(tempFile)

            if (shouldReplace) {
                dbFile.parentFile?.mkdirs()
                deleteDatabaseFiles(dbFile)
                if (!tempFile.renameTo(dbFile)) {
                    copyFromAssets(context, dbFile)
                    tempFile.delete()
                }
                FgoLogger.info(TAG, "Term DB refreshed from assets, size=${dbFile.length()} bytes")
            } else {
                tempFile.delete()
            }
        }

        private fun shouldKeepOnlineDb(dbFile: File): Boolean {
            return onlineMarkerFile(dbFile).exists() &&
                dbFile.exists() &&
                dbFile.length() > 0L
        }

        private fun ensureBundledDbHasRows(context: Context, dbFile: File) {
            val stats = readDbStats(dbFile)
            if (stats.total > 0) {
                FgoLogger.info(
                    TAG,
                    "Term DB ready: character_names=${stats.characterNames}, terms=${stats.terms}, size=${dbFile.length()} bytes"
                )
                return
            }

            FgoLogger.warn(TAG, "Term DB at ${dbFile.absolutePath} has no rows; refreshing from assets")
            deleteDatabaseFiles(dbFile)
            copyFromAssets(context, dbFile)
            val refreshedStats = readDbStats(dbFile)
            require(refreshedStats.total > 0) {
                "Bundled term DB is empty: ${dbFile.absolutePath}"
            }
            FgoLogger.info(
                TAG,
                "Term DB restored: character_names=${refreshedStats.characterNames}, terms=${refreshedStats.terms}, size=${dbFile.length()} bytes"
            )
        }

        private data class DbStats(
            val characterNames: Int,
            val terms: Int
        ) {
            val total: Int get() = characterNames + terms
        }

        private fun readDbStats(dbFile: File): DbStats {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                return DbStats(
                    characterNames = countRows(db, "character_names"),
                    terms = countRows(db, "terms")
                )
            }
        }

        private fun countRows(db: SQLiteDatabase, tableName: String): Int {
            db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

        private fun deleteDatabaseFiles(dbFile: File) {
            listOf(
                dbFile,
                File("${dbFile.absolutePath}-wal"),
                File("${dbFile.absolutePath}-shm"),
                onlineMarkerFile(dbFile)
            ).forEach { file ->
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("Unable to delete stale term DB file: ${file.absolutePath}")
                }
            }
        }

        private fun onlineMarkerFile(dbFile: File): File {
            return File(dbFile.parentFile, ONLINE_MARKER_NAME)
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
