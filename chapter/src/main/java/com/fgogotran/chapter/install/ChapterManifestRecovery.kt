package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.codec.FgoInstalledManifestCodec
import com.fgogotran.chapter.codec.valueOrThrow

/** Hash-bound private originals/prepared index, supplied read-only by the owning app. */
internal class ChapterManifestRecovery(record: ChapterInstallRecord, original: ByteArray, prepared: ByteArray,
    private val codec: FgoInstalledManifestCodec = FgoJpFormatProfile.manifestCodec()) {
    private fun asset(bytes: ByteArray) = OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), bytes)
    private val baseline = asset(original.also(record.originalManifest::verify))
    private val replacement = asset(prepared.also(requireNotNull(record.replacementManifest)::verify))
    init {
        require(record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1)
        codec.recoveryRow(baseline, baseline, replacement, ChapterInstallPolicy.GROUP).valueOrThrow()
    }
    /** null is unknown/corrupt/updated, not permission to repair it. */
    fun state(bytes: ByteArray): Int? = when (val result = codec.recoveryRow(asset(bytes), baseline, replacement, ChapterInstallPolicy.GROUP)) {
        is NativeCodecResult.Success -> result.value
        else -> null
    }
    fun restore(bytes: ByteArray): ByteArray = codec.restoreRecoveryRow(asset(bytes), baseline, replacement,
        ChapterInstallPolicy.GROUP).valueOrThrow().toByteArray()
    fun verifyRestore(before: ByteArray, after: ByteArray) {
        check(restore(before).contentEquals(after)) { "Recovery may change only the verified story index entry" }
    }
}
