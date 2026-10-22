package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.concurrent.CancellationException

internal fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
internal inline fun <T> nativeAttempt(block: () -> T): NativeCodecResult<T> = try {
    NativeCodecResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
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

    /** Recovery compares decoded rows, never gzip timestamps or a previous filesystem inode. */
    internal fun recoveryRow(current: OriginalAsset, baseline: OriginalAsset, prepared: OriginalAsset,
        logicalPath: String): NativeCodecResult<Int> = nativeAttempt {
        val a = contents(baseline); val b = contents(prepared); val live = contents(current)
        require(logicalPath.startsWith("ScriptActionEncrypt/"))
        require(a.rows[1] == b.rows[1] && live.rows[1] == a.rows[1]) { "Resource index version changed" }
        fun row(value: Contents) = value.rows.single { it.substringAfterLast(',') == logicalPath }
        val before = row(a); val after = row(b); val now = row(live)
        // References are hash-bound by the caller. Only this one bundle's size/CRC
        // may differ between them; include opaque columns, headers and row order.
        require(a.rows.size == b.rows.size)
        require(a.rows.drop(1).map { if (it == before) after else it } == b.rows.drop(1))
        val aa = before.split(','); val bb = after.split(',')
        require(aa.size == 5 && bb.size == 5 && listOf(0, 1, 4).all { aa[it] == bb[it] })
        when (now) { before -> 0; after -> 1; else -> error("Story index entry changed unexpectedly") }
    }

    /** Restore one verified row in today's index; keep unrelated installed flags/rows. */
    internal fun restoreRecoveryRow(current: OriginalAsset, baseline: OriginalAsset, prepared: OriginalAsset,
        logicalPath: String): NativeCodecResult<AssetBytes> = nativeAttempt {
        val state = recoveryRow(current, baseline, prepared, logicalPath).valueOrThrow()
        val live = contents(current); val original = contents(baseline)
        if (state == 0) return@nativeAttempt if (live.rows == original.rows) baseline.bytes else current.bytes
        val before = original.rows.single { it.substringAfterLast(',') == logicalPath }
        val rows = live.rows.map { if (it.substringAfterLast(',') == logicalPath) before else it }
        val body = rows.drop(1).joinToString("\n")
        val decoded = "~${crc32(body.toByteArray(Charsets.UTF_8))}\n$body"
        // Reuse exact original bytes when no unrelated entry changed.
        if (decoded == original.rows.joinToString("\n")) return@nativeAttempt baseline.bytes
        val cipher = envelope.encrypt(packText(decoded.toByteArray(Charsets.UTF_8), TextCompression.GZIP))
        val bom = if (live.text.startsWith("\uFEFF")) "\uFEFF" else ""
        AssetBytes.copyOf((bom + Base64.getEncoder().encodeToString(cipher)).toByteArray(Charsets.UTF_8)).also {
            require(contents(OriginalAsset.capture(current.fingerprint.path, it.toByteArray())).rows.joinToString("\n") == decoded)
        }
    }

    internal data class DiagnosticLayers(val gzip: AssetBytes, val decodedText: String)

    /** Read-only, validated layers for desktop diagnostics; never changes encoding or acceptance. */
    internal fun diagnosticLayers(original: OriginalAsset): NativeCodecResult<DiagnosticLayers> = nativeAttempt {
        val content = contents(original)
        DiagnosticLayers(AssetBytes.copyOf(content.packed), content.rows.joinToString("\n"))
    }

    /** Explicit R1 index-only control: same decoded bytes, fresh gzip/envelope. Not a bundle proof. */
    internal fun reencodeEquivalentForIndexControl(original: OriginalAsset): NativeCodecResult<AssetBytes> = nativeAttempt {
        val content = contents(original)
        val plain = content.rows.joinToString("\n").toByteArray(Charsets.UTF_8)
        val cipher = envelope.encrypt(packText(plain, TextCompression.GZIP))
        val bom = if (content.text.startsWith("\uFEFF")) "\uFEFF" else ""
        AssetBytes.copyOf((bom + Base64.getEncoder().encodeToString(cipher)).toByteArray(Charsets.UTF_8)).also {
            require(contents(OriginalAsset.capture(original.fingerprint.path, it.toByteArray())).rows == content.rows)
        }
    }

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
    fun replaceAsset(original: OriginalAsset, originalAsset: OriginalAsset, replacement: AssetBytes): NativeCodecResult<AssetBytes> =
        replaceAssets(original, listOf(originalAsset to replacement))

    /** Batch metadata update from this user's captured index; unrelated rows/flags stay unchanged. */
    fun replaceAssets(original: OriginalAsset, replacements: Collection<Pair<OriginalAsset, AssetBytes>>, checkCancelled: () -> Unit = {}): NativeCodecResult<AssetBytes> = nativeAttempt {
        val selected = replacements.toList()
        require(selected.isNotEmpty() && selected.map { it.first.fingerprint.path }.distinct().size == selected.size)
        val content = contents(original)
        val rows = content.rows.toMutableList()
        selected.forEach { (originalAsset, replacement) ->
            checkCancelled()
            val entry = content.entries.single { it.assetPath == originalAsset.fingerprint.path }
            verifyEntry(entry, originalAsset)
            require(entry.logicalPath.startsWith("ScriptActionEncrypt/")) { "Only verified story bundles may change" }
            val index = rows.indexOfFirst { it.split(',').lastOrNull() == entry.logicalPath }
            require(index >= 2)
            val columns = rows[index].split(',').toMutableList()
            columns[2] = replacement.size.toString(); columns[3] = crc32(replacement.toByteArray()).toString()
            rows[index] = columns.joinToString(",")
        }
        if (selected.all { it.first.bytes == it.second }) return@nativeAttempt original.bytes
        val body = rows.drop(1).joinToString("\n")
        val plain = ("~${crc32(body.toByteArray(Charsets.UTF_8))}\n" + body).toByteArray(Charsets.UTF_8)
        val cipher = envelope.encrypt(packText(plain, TextCompression.GZIP))
        val bom = if (content.text.startsWith("\uFEFF")) "\uFEFF" else ""
        AssetBytes.copyOf((bom + Base64.getEncoder().encodeToString(cipher)).toByteArray(Charsets.UTF_8)).also { checkCancelled() }
    }

    /** Ensures the manifest's size/CRC actually describe the captured bytes before any rebuilding. */
    fun verifyAsset(originalManifest: OriginalAsset, originalAsset: OriginalAsset): NativeCodecResult<Entry> = nativeAttempt {
        val entry = contents(originalManifest).entries.single { it.assetPath == originalAsset.fingerprint.path }
        verifyEntry(entry, originalAsset)
        entry
    }

    private fun verifyEntry(entry: Entry, originalAsset: OriginalAsset) {
        require(entry.installed && entry.size == originalAsset.fingerprint.sizeBytes && entry.crc32 == crc32(originalAsset.bytes.toByteArray())) {
            "Installed manifest and captured asset do not match"
        }
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
