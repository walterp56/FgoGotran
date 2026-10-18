package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32

internal fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
internal inline fun <T> nativeAttempt(block: () -> T): NativeCodecResult<T> = try {
    NativeCodecResult.Success(block())
} catch (failure: UnsupportedNative) {
    NativeCodecResult.Unsupported(failure.message ?: "Unsupported native format")
} catch (failure: Exception) {
    NativeCodecResult.Invalid(failure.message ?: failure.javaClass.simpleName)
}

internal fun <T> NativeCodecResult<T>.valueOrThrow(): T = when (this) {
    is NativeCodecResult.Success -> value
    is NativeCodecResult.Unsupported -> throw UnsupportedNative(reason)
    is NativeCodecResult.Invalid -> throw IllegalArgumentException(reason)
}

/** Observed installed-cache manifest, not the server AssetStorage format. */
class FgoInstalledManifestCodec(private val envelope: RijndaelEnvelope) : NativeManifestCodec {
    override val format = NativeFormat("fgo-installed-five-column-manifest", 1)

    data class Entry(val installed: Boolean, val logicalPath: String, val assetPath: GameAssetPath, val size: Long, val crc32: Long)
    private data class Contents(val packed: ByteArray, val text: String, val rows: List<String>, val entries: List<Entry>)

    fun entries(original: OriginalAsset): NativeCodecResult<List<Entry>> = nativeAttempt { contents(original).entries }

    override fun decode(original: OriginalAsset): NativeCodecResult<DecodedNativeManifest> = nativeAttempt {
        val content = contents(original)
        DecodedNativeManifest(original, format, content.entries.map { entry ->
            NativeManifestEntry(NativeElementId.parse(entry.logicalPath), entry.assetPath,
                NativeLocator(format, AssetBytes.copyOf(entry.logicalPath.toByteArray(Charsets.UTF_8))))
        })
    }

    override fun encodeUnchanged(document: DecodedNativeManifest): NativeCodecResult<AssetBytes> = nativeAttempt {
        require(document.format == format)
        val decoded = decode(document.original).valueOrThrow()
        require(decoded.entries == document.entries) { "Manifest document does not match original" }
        val packed = contents(document.original).packed
        val cipher = envelope.encrypt(packed)
        require(cipher.contentEquals(decodeBase64Text(document.original.bytes.toByteArray(), 4 * 1024 * 1024)))
        // Preserve the captured Base64/BOM/newline representation, not a re-compressed gzip timestamp.
        document.original.bytes
    }

    /** Only rewrites a verified installed Unity asset's size/CRC; all other rows are byte-preserved. */
    fun replaceAsset(original: OriginalAsset, originalAsset: OriginalAsset, replacement: AssetBytes): NativeCodecResult<AssetBytes> = nativeAttempt {
        val content = contents(original)
        val entry = verifyAsset(original, originalAsset).valueOrThrow()
        require(entry.logicalPath.startsWith("ScriptActionEncrypt/")) { "Proof patches only story bundles" }
        val rows = content.rows.toMutableList()
        val index = rows.indexOfFirst { it.split(',').lastOrNull() == entry.logicalPath }
        require(index >= 2)
        val columns = rows[index].split(',').toMutableList()
        columns[2] = replacement.size.toString()
        columns[3] = crc32(replacement.toByteArray()).toString()
        rows[index] = columns.joinToString(",")
        val body = rows.drop(1).joinToString("\n")
        val plain = ("~${crc32(body.toByteArray(Charsets.UTF_8))}\n" + body).toByteArray(Charsets.UTF_8)
        val cipher = envelope.encrypt(packText(plain, TextCompression.GZIP))
        val bom = if (content.text.startsWith("\uFEFF")) "\uFEFF" else ""
        AssetBytes.copyOf((bom + Base64.getEncoder().encodeToString(cipher)).toByteArray(Charsets.UTF_8))
    }

    /** Ensures the manifest's size/CRC actually describe the captured bytes before any rebuilding. */
    fun verifyAsset(originalManifest: OriginalAsset, originalAsset: OriginalAsset): NativeCodecResult<Entry> = nativeAttempt {
        val entry = contents(originalManifest).entries.single { it.assetPath == originalAsset.fingerprint.path }
        require(entry.installed && entry.size == originalAsset.fingerprint.sizeBytes && entry.crc32 == crc32(originalAsset.bytes.toByteArray())) {
            "Installed manifest and captured asset do not match"
        }
        entry
    }

    private fun contents(original: OriginalAsset): Contents {
        val bytes = original.bytes.toByteArray()
        val packed = envelope.decrypt(decodeBase64Text(bytes, 4 * 1024 * 1024), 4 * 1024 * 1024)
        val plain = invert(gunzipBounded(packed, 8 * 1024 * 1024))
        val text = strictUtf8(plain)
        require('\r' !in text && '\u0000' !in text) { "Unverified manifest line format" }
        val firstBreak = text.indexOf('\n')
        require(firstBreak > 1 && text.startsWith('~'))
        require(text.substring(1, firstBreak).toLong() == crc32(plain.copyOfRange(firstBreak + 1, plain.size))) { "Manifest body CRC mismatch" }
        val rows = text.split('\n')
        if (!rows[1].startsWith("@FgoDataVersion0066,")) throw UnsupportedNative("Unverified installed manifest version")
        val entries = rows.drop(2).filter { it.isNotEmpty() }.map { row ->
            val columns = row.split(',')
            if (columns.size != 5 || columns[0] !in listOf("0", "1")) throw UnsupportedNative("Not the installed five-column format")
            val logical = columns[4]
            GameAssetPath.parse(logical)
            val name = logical.replace('/', '@')
            val physical = if (logical.startsWith("Audio/") || logical.startsWith("Movie/")) {
                MessageDigest.getInstance("MD5").digest((name + "pN6ds2Bg").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            } else {
                MessageDigest.getInstance("SHA-1").digest((name + ".unity3d").toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format((it.toInt() and 255) xor 0xaa) } + ".bin"
            }
            Entry(columns[0] == "1", logical, GameAssetPath.parse(physical), columns[2].toLong().also { require(it >= 0) },
                columns[3].toLong().also { require(it in 0..0xffffffffL) })
        }
        require(entries.map { it.logicalPath }.distinct().size == entries.size)
        require(entries.map { it.assetPath }.distinct().size == entries.size)
        return Contents(packed, strictUtf8(bytes), rows, entries)
    }
}
