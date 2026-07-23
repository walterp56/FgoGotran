package com.fgogotran.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceAudioCache @Inject constructor(
    @ApplicationContext context: Context
) {
    private val cacheDir = File(context.cacheDir, "voice_audio").apply { mkdirs() }

    fun cachedFile(cacheMaterial: String): File? {
        val file = cacheFile(cacheMaterial)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun write(cacheMaterial: String, audio: ByteArray): File {
        val file = cacheFile(cacheMaterial)
        file.writeBytes(audio)
        pruneOldFiles()
        return file
    }

    private fun cacheFile(cacheMaterial: String): File {
        return File(cacheDir, "${sha256(cacheMaterial)}.mp3")
    }

    private fun pruneOldFiles() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_CACHED_AUDIO_FILES)
            .forEach { it.delete() }
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_CACHED_AUDIO_FILES = 80
    }
}
