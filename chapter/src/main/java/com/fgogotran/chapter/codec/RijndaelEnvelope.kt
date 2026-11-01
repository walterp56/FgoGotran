package com.fgogotran.chapter.codec

import com.fgogotran.chapter.AssetBytes
import org.bouncycastle.crypto.engines.RijndaelEngine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Asset-format primitive, not an account credential or network protocol. No global provider changes. */
class RijndaelEnvelope(key: AssetBytes, iv: AssetBytes) {
    private val keyBytes = key.toByteArray()
    private val ivBytes = iv.toByteArray()

    init {
        require(keyBytes.size == 32 && ivBytes.size == 32) { "Expected 256-bit key and block IV" }
    }

    fun decrypt(bytes: ByteArray, maxBytes: Int): ByteArray {
        require(bytes.isNotEmpty() && bytes.size % 32 == 0 && bytes.size <= maxBytes)
        return transform(bytes, false)
    }

    fun encrypt(bytes: ByteArray): ByteArray = transform(bytes, true)

    @Suppress("DEPRECATION")
    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher(RijndaelEngine(256)))
        cipher.init(encrypt, ParametersWithIV(KeyParameter(keyBytes), ivBytes))
        val output = ByteArray(cipher.getOutputSize(bytes.size))
        val length = cipher.processBytes(bytes, 0, bytes.size, output, 0)
        val total = length + cipher.doFinal(output, length)
        return output.copyOf(total)
    }
}

internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

internal fun decodeBase64Text(bytes: ByteArray, maxBytes: Int): ByteArray {
    require(bytes.size <= maxBytes) { "Envelope exceeds limit" }
    val text = strictUtf8(bytes).removePrefix("\uFEFF")
    require(text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=\r\n" }) {
        "Unsupported Base64 envelope"
    }
    return Base64.getDecoder().decode(text.replace("\r", "").replace("\n", ""))
}

internal fun gunzipBounded(bytes: ByteArray, maxBytes: Int): ByteArray {
    require(bytes.size >= 3 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() && bytes[2] == 8.toByte()) {
        "Unsupported compression (only verified gzip is accepted)"
    }
    return readBounded(GZIPInputStream(ByteArrayInputStream(bytes)), maxBytes)
}

internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray = input.use { stream ->
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(count <= maxBytes - output.size()) { "Decompressed payload exceeds limit" }
        output.write(buffer, 0, count)
    }
    output.toByteArray()
}

internal enum class TextCompression { GZIP, BZIP2 }
internal fun unpackText(bytes: ByteArray, maxBytes: Int): Pair<TextCompression, ByteArray> = when {
    bytes.take(3) == listOf(0x42.toByte(), 0x5a.toByte(), 0x68.toByte()) ->
        TextCompression.BZIP2 to invert(readBounded(BZip2CompressorInputStream(ByteArrayInputStream(bytes)), maxBytes))
    bytes.take(3) == listOf(0x1f.toByte(), 0x8b.toByte(), 8.toByte()) -> TextCompression.GZIP to invert(gunzipBounded(bytes, maxBytes))
    else -> throw UnsupportedNative("Unverified text compression")
}

internal fun packText(bytes: ByteArray, compression: TextCompression): ByteArray = when (compression) {
    TextCompression.GZIP -> gzip(invert(bytes))
    TextCompression.BZIP2 -> ByteArrayOutputStream().also { output ->
        BZip2CompressorOutputStream(output, 1).use { it.write(invert(bytes)) }
    }.toByteArray()
}

internal fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
    GZIPOutputStream(output).use { it.write(bytes) }
}.toByteArray()

internal fun invert(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor 0xff).toByte() }
