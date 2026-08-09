package com.fgogotran.voice

import android.content.Context
import java.io.File

internal object VoiceDataFiles {
    const val PROFILE_FILE = "character_voice_profiles_cn.tsv"
    const val NAME_MAP_FILE = "jp_cn_name_map.tsv"
    const val PROFILE_ASSET = "voice/$PROFILE_FILE"
    const val NAME_MAP_ASSET = "voice/$NAME_MAP_FILE"

    val PROFILE_HEADER = listOf(
        "speaker_id",
        "aliases",
        "voice_type",
        "cn_voice_name",
        "cn_style",
        "cn_pitch",
        "cn_rate",
        "cn_volume"
    )
    val NAME_MAP_HEADER = listOf("jp_name", "cn_name_simp", "cn_name_trad", "count")

    fun rootDir(context: Context): File = File(context.filesDir, "voice_data")

    fun installedDir(context: Context): File = File(rootDir(context), "installed")

    fun installedProfileFile(context: Context): File = File(installedDir(context), PROFILE_FILE)

    fun installedNameMapFile(context: Context): File = File(installedDir(context), NAME_MAP_FILE)

    fun installedPackageExists(context: Context): Boolean {
        return installedProfileFile(context).isFile && installedNameMapFile(context).isFile
    }
}
