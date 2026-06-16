package com.fgogotran.terminology

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.fgogotran.util.FgoLogger
import java.io.File

/**
 * Room database for the FGO terminology glossary.
 *
 * The APK no longer ships a bundled terminology DB. Room creates an empty
 * schema when needed, and GlossaryUpdateManager installs the verified CDN DB.
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

        data class DbPackageMetadata(
            val contentVersion: String,
            val sha256: String,
            val generatedAt: String
        )

        fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        fun onlinePackageMetadata(context: Context): DbPackageMetadata? {
            return readOnlineMarker(databaseFile(context))
        }

        fun hasUsableDb(context: Context): Boolean {
            val dbFile = databaseFile(context)
            return dbFile.exists() &&
                dbFile.length() > 0L &&
                runCatching { readDbStats(dbFile).total > 0 }.getOrDefault(false)
        }

        fun markOnlineInstall(
            context: Context,
            contentVersion: String,
            sha256: String,
            generatedAt: String
        ) {
            writePackageMarker(
                dbFile = databaseFile(context),
                metadata = DbPackageMetadata(
                    contentVersion = contentVersion,
                    sha256 = sha256,
                    generatedAt = generatedAt
                )
            )
        }

        private fun writePackageMarker(dbFile: File, metadata: DbPackageMetadata) {
            dbFile.parentFile?.mkdirs()
            onlineMarkerFile(dbFile).writeText(
                listOf(
                    "contentVersion=${metadata.contentVersion}",
                    "sha256=${metadata.sha256}",
                    "generatedAt=${metadata.generatedAt}"
                ).joinToString(separator = "\n", postfix = "\n"),
                Charsets.UTF_8
            )
        }

        /** Creates or opens the term database. */
        fun create(context: Context): TermDatabase {
            val dbFile = databaseFile(context)
            removeUnmarkedLegacyDb(dbFile)
            return try {
                Room.databaseBuilder(context, TermDatabase::class.java, DB_NAME)
                    .build()
                    .also {
                        it.openHelper.readableDatabase
                        logDbState(dbFile)
                    }
            } catch (e: Exception) {
                FgoLogger.warn(TAG, "Term DB open failed; recreating empty online-only DB.", e)
                deleteDatabaseFiles(dbFile)
                Room.databaseBuilder(context, TermDatabase::class.java, DB_NAME)
                    .build()
                    .also {
                        it.openHelper.readableDatabase
                        logDbState(dbFile)
                    }
            }
        }

        private fun removeUnmarkedLegacyDb(dbFile: File) {
            if (!dbFile.exists() || readOnlineMarker(dbFile) != null) return
            FgoLogger.warn(TAG, "Removing unmarked legacy term DB; CDN updater will install the online DB")
            deleteDatabaseFiles(dbFile)
        }

        private fun logDbState(dbFile: File) {
            val stats = runCatching { readDbStats(dbFile) }.getOrNull()
            if (stats == null) {
                FgoLogger.warn(TAG, "Term DB stats unavailable; online updater will refresh it")
                return
            }
            if (stats.total == 0) {
                FgoLogger.info(
                    TAG,
                    "Term DB is empty; waiting for CDN install at ${dbFile.absolutePath}"
                )
            } else {
                FgoLogger.info(
                    TAG,
                    "Term DB ready: character_names=${stats.characterNames}, terms=${stats.terms}, size=${dbFile.length()} bytes"
                )
            }
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

        private fun readOnlineMarker(dbFile: File): DbPackageMetadata? {
            val marker = onlineMarkerFile(dbFile)
            if (!marker.exists()) return null

            val values = marker.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                    }
                }
                .toMap()

            val contentVersion = values["contentVersion"].orEmpty()
            val sha256 = values["sha256"].orEmpty()
            if (contentVersion.isBlank() || sha256.isBlank()) return null

            return DbPackageMetadata(
                contentVersion = contentVersion,
                sha256 = sha256,
                generatedAt = values["generatedAt"].orEmpty()
            )
        }
    }
}
