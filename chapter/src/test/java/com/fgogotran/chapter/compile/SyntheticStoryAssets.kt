package com.fgogotran.chapter.compile

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Entirely synthetic game bytes and source metadata; never reads a device. */
internal class SyntheticStoryAssets {
    val envelope = RijndaelEnvelope(AssetBytes.copyOf(ByteArray(32) { it.toByte() }), AssetBytes.copyOf(ByteArray(32) { (it + 32).toByte() }))
    val scripts = FgoNativeScriptCodec(envelope)
    val containers = FgoJpContainerCodec(envelope)
    val manifests = FgoInstalledManifestCodec(envelope)
    val version = StoryGameVersion("2.139.5", 502)
    fun compiler() = StoryPackCompiler(containers, scripts, manifests)

    fun encoded(text: String, compression: TextCompression = TextCompression.BZIP2): AssetBytes =
        AssetBytes.copyOf(Base64.getEncoder().encode(envelope.encrypt(packText(text.toByteArray(Charsets.UTF_8), compression))))
    fun path(group: String): GameAssetPath = GameAssetPath.parse(MessageDigest.getInstance("SHA-1")
        .digest((group.replace('/', '@') + ".unity3d").toByteArray()).joinToString("") { "%02x".format((it.toInt() and 255) xor 0xaa) } + ".bin")
    fun asset(groups: List<Map<String, AssetBytes>>, logical: String = "ScriptActionEncrypt/01"): OriginalAsset {
        val raw = unity(groups.map(::serialized))
        for (index in 0 until raw.size - 1 step 2) {
            val first = raw[index]; raw[index] = (raw[index + 1].toInt() xor 0xce).toByte(); raw[index + 1] = (first.toInt() xor 0xd2).toByte()
        }
        return OriginalAsset.capture(path(logical), envelope.encrypt(raw))
    }
    fun asset(scripts: Map<String, AssetBytes>, logical: String = "ScriptActionEncrypt/01"): OriginalAsset = asset(listOf(scripts), logical)
    fun manifest(assets: List<Pair<String, OriginalAsset>>): OriginalAsset {
        val body = "@FgoDataVersion0066,synthetic,preserved\n" + assets.joinToString("") { (logical, original) ->
            "1,DATA0,${original.bytes.size},${crc32(original.bytes.toByteArray())},$logical\n"
        } + "0,OPAQUE,9,17,Other/Synthetic\n"
        return OriginalAsset.capture(GameAssetPath.parse(FgoJpFormatProfile.MANIFEST_NAME),
            ("\uFEFF" + Base64.getEncoder().encodeToString(envelope.encrypt(packText(("~${crc32(body.toByteArray())}\n$body").toByteArray(), TextCompression.GZIP)))).toByteArray())
    }
    fun source(original: OriginalAsset, id: String = "0100000011") = containers.decode(original).valueOrThrow().scripts.single { it.binding.scriptId.value == id }
    fun plain(original: NativeScriptSource) = scripts.decodedBytes(original).valueOrThrow().toByteArray().toString(Charsets.UTF_8)
    fun field(source: NativeScriptSource, text: String) = scripts.decode(source).valueOrThrow().fields.single { it.sourceText == text }
    fun change(source: NativeScriptSource, oldText: String, vararg tokens: StoryTextToken): StoryTextChange {
        val field = field(source, oldText)
        return StoryTextChange(field.id.value, StoryFieldKind.entries.single { it.wire == field.kind.name.lowercase() },
            Sha256Digest.compute(field.sourceText.toByteArray()).hex, tokens.toList())
    }
    fun pack(folder: File, originals: List<Pair<String, OriginalAsset>>, changes: Map<String, List<StoryTextChange>>, chapter: String = "intro",
        language: StoryLanguage = StoryLanguage.TRADITIONAL_CHINESE, revision: Int = 1,
        descriptorTransform: (StoryScriptDescriptor) -> StoryScriptDescriptor = { it }, packId: String = "$chapter-pack"): ValidatedStoryPack {
        val patchBytes = changes.mapValues { (id, edits) -> StoryPackJson.encodePatch(StoryScriptPatch(id, edits)) }
        val descriptors = changes.map { (id, _) ->
            val (logical, original, source) = originals.flatMap { (logical, original) -> containers.decode(original).valueOrThrow().scripts.map { Triple(logical, original, it) } }
                .single { it.third.binding.scriptId.value == id }
            StoryScriptDescriptor(id, logical, original.fingerprint.path.value, original.bytes.sha256.hex, original.bytes.size,
                source.payload.sha256.hex, scripts.decodedBytes(source).valueOrThrow().sha256.hex, Sha256Digest.compute(patchBytes.getValue(id)).hex, patchBytes.getValue(id).size)
        }
        val checkedDescriptors = descriptors.map(descriptorTransform)
        val sources = changes.keys.flatMap { id -> listOf("JP", language.region).map {
            StorySource(it, id, "0".repeat(64), 1, "https://static.atlasacademy.io/$it/Script/01/$id.txt")
        } }
        val manifest = StoryPackManifest(packId, revision, chapter, mapOf("en" to "Synthetic $chapter"), language, StoryCoverage.SAMPLE,
            listOf(1), listOf(version), checkedDescriptors, sources)
        val file = File.createTempFile("synthetic-pack-", ".zip", folder)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(StoryPackJson.encodeManifest(manifest)); zip.closeEntry()
            patchBytes.forEach { (id, bytes) -> zip.putNextEntry(ZipEntry(StoryPackPolicy.entry(id))); zip.write(bytes); zip.closeEntry() }
        }
        return StoryPackArchive.read(file)
    }

    private fun binary(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun leInt(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun leLong(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    fun serialized(scripts: Map<String, AssetBytes>): ByteArray {
        data class Object(val id: Long, val start: Int, val bytes: ByteArray, val type: Int)
        val bodies = ByteArrayOutputStream()
        val objects = scripts.entries.mapIndexed { index, (name, payload) ->
            while (bodies.size() % 16 != 0) bodies.write(0x5a)
            val body = ByteArrayOutputStream().apply {
                val encodedName = name.toByteArray(); write(leInt(encodedName.size)); write(encodedName)
                while (size() % 4 != 0) write(0)
                write(leInt(payload.size)); write(payload.toByteArray()); while (size() % 4 != 0) write(0)
            }.toByteArray()
            Object(100L + index, bodies.size(), body, 0).also { bodies.write(body) }
        }.toMutableList()
        while (bodies.size() % 16 != 0) bodies.write(0x6b)
        val opaque = byteArrayOf(4, 9, 3, 7)
        objects += Object(42, bodies.size(), opaque, 1); bodies.write(opaque)
        val metadata = ByteArrayOutputStream().apply {
            write("6000.0.68f1\u0000".toByteArray()); write(leInt(13)); write(0); write(leInt(2))
            listOf(49, 1234).forEach { write(leInt(it)); write(byteArrayOf(0, -1, -1)); write(ByteArray(16)) }
            write(leInt(objects.size)); while (size() % 4 != 0) write(0)
            objects.forEach { write(leLong(it.id)); write(leLong(it.start.toLong())); write(leInt(it.bytes.size)); write(leInt(it.type)) }
            repeat(3) { write(leInt(0)) }; write(0)
        }.toByteArray()
        val dataOffset = (48 + metadata.size + 15) and 15.inv()
        return binary {
            writeInt(0); writeInt(0); writeInt(22); writeInt(0); writeInt(0); writeInt(metadata.size)
            writeLong((dataOffset + bodies.size()).toLong()); writeLong(dataOffset.toLong()); writeLong(0)
            write(metadata); write(ByteArray(dataOffset - 48 - metadata.size)); write(bodies.toByteArray())
        }
    }

    fun unity(nodes: List<ByteArray>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = nodes.map { node ->
            while (data.size() % 16 != 0) data.write(0x6d)
            data.size().also { data.write(node) }
        }
        val info = binary {
            write(ByteArray(16)); writeInt(1); writeInt(data.size()); writeInt(data.size()); writeShort(0x40); writeInt(nodes.size)
            nodes.forEachIndexed { i, node -> writeLong(offsets[i].toLong()); writeLong(node.size.toLong()); writeInt(4); write("CAB-synthetic-$i\u0000".toByteArray()) }
        }
        return ByteArrayOutputStream().apply {
            write(binary { write("UnityFS\u0000".toByteArray()); writeInt(8); write("5.x.x\u00006000.0.68f1\u0000".toByteArray()); writeLong(0); writeInt(info.size); writeInt(info.size); writeInt(0x240) })
            while (size() % 16 != 0) write(0)
            write(info); while (size() % 16 != 0) write(0); write(data.toByteArray())
        }.toByteArray().also { ByteBuffer.wrap(it).putLong(30, it.size.toLong()) }
    }
}
