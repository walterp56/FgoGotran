package com.fgogotran.chapter.storage

import com.fgogotran.chapter.codec.FgoJpFormatProfile
import java.io.File

/** The first device route is deliberately restricted to JP FGO in Android's owner profile. */
internal object ChapterAssetPolicy {
    const val ROOT = "/storage/emulated/0/Android/data/com.aniplex.fategrandorder/files/data/d713"
    const val MAX_BYTES = 64 * 1024 * 1024
    const val ROOT_ID = "jp-fgo:android-user-0:external-data-d713"
    private val bundleName = Regex("[0-9a-f]{40}\\.bin")

    fun requireFilename(value: String): String = value.also {
        require(it == FgoJpFormatProfile.MANIFEST_NAME || bundleName.matches(it)) { "Not an allowed FGO asset filename" }
    }

    fun resolve(root: File, filename: String): File {
        requireFilename(filename)
        val canonicalRoot = root.canonicalFile
        val target = File(root, filename)
        require(target.canonicalFile.parentFile == canonicalRoot && target.canonicalFile.name == filename) { "Asset path escapes the verified root" }
        return target
    }
}
