package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import java.nio.ByteBuffer

/** The observed JP outer asset wrapper and UnityFS8/serialized22 TextAssets only. */
class FgoJpContainerCodec(private val envelope: RijndaelEnvelope, private val obfuscated: Boolean = true) : NativeContainerCodec {
    override val format = NativeFormat("fgo-jp-unityfs8-serialized22", 1)

    private fun unityBytes(bytes: AssetBytes): ByteArray {
        val raw = envelope.decrypt(bytes.toByteArray(), MAX_NATIVE_BYTES)
        if (!obfuscated) return raw
        for (index in 0 until raw.size - 1 step 2) {
            val first = raw[index]
            raw[index] = (raw[index + 1].toInt() xor 0xd2).toByte()
            raw[index + 1] = (first.toInt() xor 0xce).toByte()
        }
        return raw
    }

    fun decodeUnity(bytes: AssetBytes): NativeCodecResult<AssetBytes> = nativeAttempt {
        AssetBytes.copyOf(unityBytes(bytes).also { UnityFsArchive.decode(it) })
    }

    private fun encodeUnity(bytes: ByteArray): AssetBytes {
        val raw = bytes.copyOf()
        if (!obfuscated) return AssetBytes.copyOf(envelope.encrypt(raw))
        for (index in 0 until raw.size - 1 step 2) {
            val first = raw[index]
            raw[index] = (raw[index + 1].toInt() xor 0xce).toByte()
            raw[index + 1] = (first.toInt() xor 0xd2).toByte()
        }
        return AssetBytes.copyOf(envelope.encrypt(raw))
    }

    override fun decode(original: OriginalAsset): NativeCodecResult<DecodedNativeContainer> = nativeAttempt {
        val archive = UnityFsArchive.decode(unityBytes(original.bytes))
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

    data class ScriptReplacement(val source: NativeScriptSource, val replacement: AssetBytes)

    fun replaceScript(original: OriginalAsset, source: NativeScriptSource, replacement: AssetBytes): NativeCodecResult<AssetBytes> =
        replaceScripts(original, listOf(ScriptReplacement(source, replacement)))

    fun replaceScripts(original: OriginalAsset, replacements: Collection<ScriptReplacement>, checkCancelled: () -> Unit = {}): NativeCodecResult<AssetBytes> = nativeAttempt {
        val selected = replacements.toList()
        require(selected.isNotEmpty() && selected.size <= 4096 && selected.map { it.source.binding }.distinct().size == selected.size)
        checkCancelled()
        val archive = UnityFsArchive.decode(unityBytes(original.bytes))
        val edits = linkedMapOf<Int, MutableMap<Long, Pair<ByteArray, ByteArray>>>()
        val parsed = mutableMapOf<Int, UnityTextAssets>()
        selected.forEach { (source, replacement) ->
            checkCancelled()
            require(source.originalAsset == original.fingerprint && source.binding.locator.format == format)
            val locator = ByteBuffer.wrap(source.binding.locator.payload.toByteArray())
            require(locator.remaining() == 12)
            val nodeIndex = locator.int; val pathId = locator.long
            require(nodeIndex in archive.nodes.indices)
            val serialized = parsed.getOrPut(nodeIndex) {
                val node = archive.nodes[nodeIndex]
                require(node.flags == 4)
                UnityTextAssets.decode(archive.data.copyOfRange(node.offset, node.offset + node.size))
            }
            val canonical = serialized.texts.single { it.pathId == pathId }
            require(canonical.name == source.binding.scriptId.value && canonical.payload.contentEquals(source.payload.toByteArray())) { "Script baseline mismatch" }
            require(edits.getOrPut(nodeIndex) { linkedMapOf() }.put(pathId, source.payload.toByteArray() to replacement.toByteArray()) == null)
        }
        val nodes = edits.mapValues { (index, patches) -> parsed.getValue(index).replaceTexts(patches, checkCancelled) }
        checkCancelled()
        encodeUnity(archive.replaceNodes(nodes, checkCancelled)).also { checkCancelled() }
    }
}
