package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.codec.crc32
import com.fgogotran.chapter.packs.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** Quarantined developer artifacts, deliberately not the source archive or a production release. */
internal object NativeCandidateStore {
    fun validateMetadata(bytes: ByteArray): JsonObject {
        val value = PreparationFiles.json(bytes).exact("type", "schema_version", "status", "installable", "native_playback_verified", "target_language",
            "game_package", "codec_profile", "resource_profile", "game_version", "alignment_profile", "scope", "source_snapshot_sha256", "source_upstream", "captured_index_sha256",
            "baseline_assets", "payloads", "coverage", "sources", "translated_scripts", "changed_fields")
        require(PreparationFiles.canonical(value).contentEquals(bytes)) { "Native candidate metadata is not canonical" }
        require(value.string("type") == "fgogotran-native-candidate" && value.int("schema_version") == 1 && value.string("status") == "development_unverified")
        require(value.getValue("installable") == JsonPrimitive(false) && value.getValue("native_playback_verified") == JsonPrimitive(false))
        val language = StoryLanguage.parse(value.string("target_language"))
        require(value.string("game_package") == StoryPackPolicy.GAME_PACKAGE && value.string("codec_profile") == StoryPackPolicy.CODEC_PROFILE)
        StoryPackPolicy.id(value.string("resource_profile"))
        require(value.string("alignment_profile") == "complete-script-exact-program@1")
        require(value.string("scope") == "Supplied JP captures and requested Atlas wars only; not full JP coverage or native acceptance")
        val version = value.getValue("game_version").jsonObject.exact("name", "code")
        StoryGameVersion(version.string("name"), version.getValue("code").jsonPrimitive.long)
        val upstream = value.getValue("source_upstream").jsonObject
        require(upstream.keys == AtlasEndpoints.regions.toSet())
        upstream.values.forEach { item ->
            val region = item.jsonObject.exact("hash", "timestamp")
            require(region.string("hash").matches(Regex("[0-9a-f]{6,64}")) && region.getValue("timestamp").jsonPrimitive.long > 0)
        }
        StoryPackPolicy.digest(value.string("source_snapshot_sha256")); StoryPackPolicy.digest(value.string("captured_index_sha256"))
        val assets = value.getValue("baseline_assets").jsonArray.map { it.jsonObject.exact("logical_bundle", "asset_filename", "sha256", "size", "script_count") }
        require(assets.size in 1..16 && assets.map { it.string("asset_filename") }.distinct().size == assets.size && assets.map { it.string("logical_bundle") }.distinct().size == assets.size)
        assets.forEach { asset ->
            require(asset.string("logical_bundle").matches(Regex("ScriptActionEncrypt/[A-Za-z0-9_]{1,64}")) &&
                asset.string("logical_bundle") !in setOf("ScriptActionEncrypt/Common", "ScriptActionEncrypt/ScriptFileList"))
            require(asset.string("asset_filename").matches(Regex("[0-9a-f]{40}\\.bin")))
            StoryPackPolicy.digest(asset.string("sha256")); require(asset.int("size") in 1..StoryPackPolicy.MAX_ASSET && asset.int("script_count") in 1..8192)
        }
        val rows = value.getValue("payloads").jsonArray.map { it.jsonObject.exact("logical_bundle", "asset_filename", "original_sha256", "original_size", "path", "sha256", "size", "crc32", "translated_scripts") }
        require(rows.size <= assets.size && rows.map { it.string("asset_filename") }.distinct().size == rows.size && rows.map { it.string("path") }.distinct().size == rows.size)
        rows.forEach { row ->
            val baseline = assets.single { it.string("asset_filename") == row.string("asset_filename") }
            require(row.string("logical_bundle") == baseline.string("logical_bundle") && row.string("original_sha256") == baseline.string("sha256") && row.int("original_size") == baseline.int("size"))
            StoryPackPolicy.digest(row.string("sha256")); require(row.string("path") == "bundles/${row.string("sha256")}.bin")
            require(row.int("size") in 1..StoryPackPolicy.MAX_ASSET && row.getValue("crc32").jsonPrimitive.long in 0..0xffffffffL)
            require(row.string("sha256") != row.string("original_sha256"))
            require(row.getValue("translated_scripts").jsonArray.isNotEmpty())
        }
        require(rows.sumOf { it.int("size").toLong() } <= 32 * 1024 * 1024)
        val coverage = value.getValue("coverage").jsonArray.map { it.jsonObject }
        require(coverage.size in 1..8192 && coverage.map { it.string("script_id") }.distinct().size == coverage.size)
        coverage.forEach { row ->
            require(row.keys == setOf("script_id", "asset_filename", "result", "field_count", "changed_fields") + if ("review_sha256" in row) setOf("review_sha256") else emptySet<String>())
            StoryPackPolicy.scriptId(row.string("script_id")); val reason = AlignmentReason.valueOf(row.string("result"))
            require(row.int("field_count") in 0..4096 && row.int("changed_fields") in 0..row.int("field_count"))
            row["review_sha256"]?.let { StoryPackPolicy.digest(it.jsonPrimitive.content) }
            if (row.getValue("asset_filename") != JsonNull) require(assets.any { it.string("asset_filename") == row.string("asset_filename") })
            else require(reason == AlignmentReason.NOT_IN_NATIVE_BASELINE)
            require((row.getValue("asset_filename") == JsonNull) == (reason == AlignmentReason.NOT_IN_NATIVE_BASELINE))
            if (reason == AlignmentReason.TRANSLATED) require(row.int("changed_fields") > 0)
            else require(row.int("changed_fields") == 0)
        }
        assets.forEach { asset -> require(coverage.count { it.getValue("asset_filename") == JsonPrimitive(asset.string("asset_filename")) } == asset.int("script_count")) {
            "Candidate coverage omitted a captured native script"
        } }
        val translated = coverage.filter { it.string("result") == AlignmentReason.TRANSLATED.name }
        require(value.int("translated_scripts") == translated.size && value.int("changed_fields") == translated.sumOf { it.int("changed_fields") })
        require(rows.flatMap { it.getValue("translated_scripts").jsonArray.map { id -> id.jsonPrimitive.content } }.sorted() == translated.map { it.string("script_id") }.sorted())
        rows.forEach { row -> row.getValue("translated_scripts").jsonArray.forEach { id ->
            require(translated.single { it.string("script_id") == id.jsonPrimitive.content }.string("asset_filename") == row.string("asset_filename"))
        } }
        val sources = value.getValue("sources").jsonArray.map { it.jsonObject.exact("script_id", "native_decoded_sha256", "jp", "target") }
        require(sources.map { it.string("script_id") }.sorted() == translated.map { it.string("script_id") }.sorted())
        sources.forEach { source ->
            StoryPackPolicy.digest(source.string("native_decoded_sha256"))
            val links = listOf("jp" to "JP", "target" to language.region).map { (key, region) ->
                val item = source.getValue(key).jsonObject.exact("region", "script_id", "url", "sha256", "size", "links")
                require(item.string("region") == region && item.string("script_id") == source.string("script_id"))
                StoryPackPolicy.digest(item.string("sha256")); require(item.int("size") in 1..PreparationFiles.MAX_SCRIPT)
                val references = item.getValue("links").jsonArray.map { it.jsonPrimitive.content }.also { list -> require(list.size in 1..1024 && list.distinct().size == list.size) }
                val parsed = references.map { link -> link.split(':').let { parts ->
                    require(parts.size == 4)
                    val numbers = parts.map { it.toInt() }
                    require(numbers.take(3).all { it > 0 } && numbers[3] >= 0)
                    AtlasLink(numbers[0], numbers[1], numbers[2], numbers[3])
                } }
                AtlasEndpoints.reference(region, item.string("script_id"), item.string("url"), parsed)
                references
            }
            require(links[0] == links[1])
        }
        return value
    }

    fun verify(directory: File): JsonObject {
        PreparationFiles.confined(requireNotNull(directory.parentFile), directory)
        val metadata = validateMetadata(PreparationFiles.read(File(directory, "candidate.json"), PreparationFiles.MAX_JSON))
        val rows = metadata.getValue("payloads").jsonArray.map { it.jsonObject }
        require(directory.listFiles()!!.map { it.name }.toSet() == setOf("candidate.json") + if (rows.isEmpty()) emptySet() else setOf("bundles"))
        if (rows.isNotEmpty()) {
            val folder = PreparationFiles.confined(directory, File(directory, "bundles"))
            require(folder.isDirectory && folder.listFiles()!!.map { "bundles/${it.name}" }.toSet() == rows.map { it.string("path") }.toSet())
        }
        rows.forEach { row ->
            val bytes = PreparationFiles.read(PreparationFiles.confined(directory, File(directory, row.string("path"))), StoryPackPolicy.MAX_ASSET)
            require(bytes.size == row.int("size") && PreparationFiles.hash(bytes) == row.string("sha256") && crc32(bytes) == row.getValue("crc32").jsonPrimitive.long)
        }
        return metadata
    }

    fun write(repository: File, candidate: BuiltNativeCandidate): File {
        val metadata = validateMetadata(candidate.metadata)
        require(candidate.payloads.keys == metadata.getValue("payloads").jsonArray.map { it.jsonObject.string("path") }.toSet())
        val root = PreparationFiles.confined(repository, File(repository, "story_assets/native-candidates"))
        Files.createDirectories(root.toPath())
        val lock = PreparationFiles.confined(root, File(root, ".candidate-publish.lock"))
        FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { return publishLocked(root, candidate) }
        }
    }

    private fun publishLocked(root: File, candidate: BuiltNativeCandidate): File {
        val destination = PreparationFiles.confined(root, File(root, PreparationFiles.hash(candidate.metadata)))
        if (destination.exists()) {
            verify(destination); require(PreparationFiles.read(File(destination, "candidate.json"), PreparationFiles.MAX_JSON).contentEquals(candidate.metadata))
            return destination
        }
        val pending = PreparationFiles.confined(root, File(root, ".partial-${UUID.randomUUID()}"))
        Files.createDirectory(pending.toPath())
        candidate.payloads.toSortedMap().forEach { (path, bytes) -> PreparationFiles.writeNew(PreparationFiles.confined(pending, File(pending, path)), bytes.toByteArray()) }
        PreparationFiles.writeNew(File(pending, "candidate.json"), candidate.metadata)
        verify(pending)
        // New immutable digest directory only. Concurrent identical builds fail, never overwrite.
        Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return destination
    }
}

/** Detached-signature contract test seam. No key generation/storage/provisioning or APK trust change. */
internal object NativeCandidateSignature {
    fun sign(metadata: ByteArray, keyId: String, privateKey: PrivateKey): ByteArray {
        NativeCandidateStore.validateMetadata(metadata)
        require(keyId.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        val signature = Signature.getInstance("Ed25519").apply { initSign(privateKey); update(metadata) }.sign()
        return PreparationFiles.canonical(buildJsonObject {
            put("type", "fgogotran-native-candidate-signature"); put("schema_version", 1); put("algorithm", "Ed25519")
            put("key_id", keyId); put("metadata_sha256", PreparationFiles.hash(metadata)); put("signature", Base64.getEncoder().encodeToString(signature))
        })
    }
    fun verify(metadata: ByteArray, envelope: ByteArray, trustedKeys: Map<String, PublicKey>) {
        NativeCandidateStore.validateMetadata(metadata)
        val value = PreparationFiles.json(envelope).exact("type", "schema_version", "algorithm", "key_id", "metadata_sha256", "signature")
        require(PreparationFiles.canonical(value).contentEquals(envelope))
        require(value.string("type") == "fgogotran-native-candidate-signature" && value.int("schema_version") == 1 && value.string("algorithm") == "Ed25519")
        require(value.string("metadata_sha256") == PreparationFiles.hash(metadata))
        val key = requireNotNull(trustedKeys[value.string("key_id")]) { "Unknown publisher key; no trust-on-first-use" }
        val signature = Base64.getDecoder().decode(value.string("signature")).also { require(it.size == 64) }
        require(Signature.getInstance("Ed25519").apply { initVerify(key); update(metadata) }.verify(signature)) { "Candidate signature mismatch" }
    }
}
