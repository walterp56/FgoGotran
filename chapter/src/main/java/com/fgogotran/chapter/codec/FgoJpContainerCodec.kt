package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import java.nio.ByteBuffer

/** The observed JP outer asset wrapper and UnityFS8/serialized22 TextAssets only. */
class FgoJpContainerCodec(private val envelope: RijndaelEnvelope) : NativeContainerCodec {
    override val format = NativeFormat("fgo-jp-unityfs8-serialized22", 1)

    fun decodeUnity(bytes: AssetBytes): NativeCodecResult<AssetBytes> = nativeAttempt {
        val raw = envelope.decrypt(bytes.toByteArray(), MAX_NATIVE_BYTES)
        for (index in 0 until raw.size - 1 step 2) {
            val first = raw[index]
            raw[index] = (raw[index + 1].toInt() xor 0xd2).toByte()
            raw[index + 1] = (first.toInt() xor 0xce).toByte()
        }
        UnityFsArchive.decode(raw)
        AssetBytes.copyOf(raw)
    }

    private fun encodeUnity(bytes: ByteArray): AssetBytes {
        val raw = bytes.copyOf()
        for (index in 0 until raw.size - 1 step 2) {
            val first = raw[index]
            raw[index] = (raw[index + 1].toInt() xor 0xce).toByte()
            raw[index + 1] = (first.toInt() xor 0xd2).toByte()
        }
        return AssetBytes.copyOf(envelope.encrypt(raw))
    }

    override fun decode(original: OriginalAsset): NativeCodecResult<DecodedNativeContainer> = nativeAttempt {
        val archive = UnityFsArchive.decode(decodeUnity(original.bytes).valueOrThrow().toByteArray())
        val scripts = archive.nodes.flatMapIndexed { index, node ->
            if (node.flags != 4) throw UnsupportedNative("Unverified Unity node kind")
            val serialized = UnityTextAssets.decode(archive.data.copyOfRange(node.offset, node.offset + node.size))
            // Names are native identities, not necessarily numeric quest IDs (e.g. WarEpilogue108).
            // Keep every TextAsset; unsupported script payloads must be explicit, never filtered out.
            serialized.texts.map { text ->
                val locator = NativeLocator(format, AssetBytes.copyOf(ByteBuffer.allocate(12).putInt(index).putLong(text.pathId).array()))
                NativeScriptSource(ScriptAssetBinding(ScriptId.parse(text.name), original.fingerprint.path, locator), original.fingerprint, AssetBytes.copyOf(text.payload))
            }
        }
        DecodedNativeContainer(original, format, scripts)
    }

    override fun encodeUnchanged(document: DecodedNativeContainer): NativeCodecResult<AssetBytes> = nativeAttempt {
        require(document.format == format)
        val decoded = decode(document.original).valueOrThrow()
        require(decoded.scripts.size == document.scripts.size && decoded.scripts.zip(document.scripts).all { (a, b) ->
            a.binding == b.binding && a.originalAsset == b.originalAsset && a.payload == b.payload
        }) { "Container document does not match original" }
        encodeUnity(decodeUnity(document.original.bytes).valueOrThrow().toByteArray()).also {
            require(it == document.original.bytes) { "Outer asset round trip differs" }
        }
    }

    fun replaceScript(original: OriginalAsset, source: NativeScriptSource, replacement: AssetBytes): NativeCodecResult<AssetBytes> = nativeAttempt {
        require(source.originalAsset == original.fingerprint)
        val decoded = decode(original).valueOrThrow()
        val canonical = decoded.scripts.single { it.binding == source.binding }
        require(canonical.payload == source.payload) { "Script baseline mismatch" }
        val locator = ByteBuffer.wrap(source.binding.locator.payload.toByteArray())
        require(locator.remaining() == 12)
        val nodeIndex = locator.int
        val pathId = locator.long
        val archive = UnityFsArchive.decode(decodeUnity(original.bytes).valueOrThrow().toByteArray())
        val node = archive.nodes[nodeIndex]
        val serialized = UnityTextAssets.decode(archive.data.copyOfRange(node.offset, node.offset + node.size))
        val replacedNode = serialized.replaceText(pathId, source.payload.toByteArray(), replacement.toByteArray())
        encodeUnity(archive.replaceNode(nodeIndex, replacedNode))
    }
}
