package com.fgogotran.chapter.remote

import android.content.Context
import java.io.File

/**
 * Disk cache for the hosted catalogue.
 *
 * The catalogue contains short-lived S3 URLs, so callers must not reuse a
 * cached copy for downloads after [MAX_DOWNLOAD_AGE_MS]. UI lookups may use it
 * for up to [MAX_UI_AGE_MS].
 */
class NativeCatalogCache(context: Context) {
    companion object {
        const val MAX_UI_AGE_MS = 15 * 60 * 1000L
        const val MAX_DOWNLOAD_AGE_MS = 3 * 60 * 1000L
    }

    private val root = File(context.cacheDir, "native-catalog").apply { mkdirs() }
    private val marker = File(root, "app-version.txt")

    init {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L).toString()
        val previous = runCatching { marker.readText().trim() }.getOrNull()
        if (previous != null && previous != version) {
            root.listFiles()?.forEach { it.delete() }
        }
        runCatching { marker.writeText(version) }
    }

    fun read(language: String, gameVersion: String): String? {
        val file = fileFor(language, gameVersion)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > MAX_UI_AGE_MS) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun ageMs(language: String, gameVersion: String): Long? {
        val file = fileFor(language, gameVersion)
        if (!file.isFile) return null
        return (System.currentTimeMillis() - file.lastModified()).coerceAtLeast(0L)
    }

    fun write(language: String, gameVersion: String, body: String) {
        runCatching {
            val target = fileFor(language, gameVersion)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(body)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
        runCatching { marker.writeText("0") }
    }

    private fun fileFor(language: String, gameVersion: String): File {
        val safe = "${language}_${gameVersion}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(root, "$safe.json")
    }
}
