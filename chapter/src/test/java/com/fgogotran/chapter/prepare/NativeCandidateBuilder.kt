package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.packs.*
import kotlinx.serialization.json.*

internal data class CandidateProof(val asset: String, val originalUnity: ByteArray, val rebuiltUnity: ByteArray, val changedScripts: List<String>)
internal data class BuiltNativeCandidate(val metadata: ByteArray, val payloads: Map<String, AssetBytes>, val proofs: List<CandidateProof>)

/** Desktop-only cumulative builder. One rebuild per shared bundle, always from captured JP originals. */
internal class NativeCandidateBuilder(
    private val containers: FgoJpContainerCodec = FgoJpFormatProfile.containerCodec(),
    private val scripts: FgoNativeScriptCodec = FgoJpFormatProfile.scriptCodec(),
    private val manifests: FgoInstalledManifestCodec = FgoJpFormatProfile.manifestCodec(),
    private val baselineProfile: ReviewedNativeBaseline = ReviewedNativeBaseline.observed()
) {
    fun build(language: StoryLanguage, snapshot: AtlasSnapshot, version: StoryGameVersion, manifest: OriginalAsset,
        assets: List<OriginalAsset>, reviewed: Map<String, ReviewedAlignment> = emptyMap(), checkCancelled: () -> Unit = {}): BuiltNativeCandidate {
        require(assets.size in 1..16 && assets.map { it.fingerprint.path }.distinct().size == assets.size)
        baselineProfile.requireOriginals(version, assets)
        require(manifest.fingerprint.path.value == FgoJpFormatProfile.MANIFEST_NAME && manifest.bytes.size <= StoryPackPolicy.MAX_ASSET)
        require(assets.all { it.bytes.size <= StoryPackPolicy.MAX_ASSET } && assets.sumOf { it.bytes.size.toLong() } + manifest.bytes.size <= 32 * 1024 * 1024)
        val jp = snapshot.references("JP").associateBy { it.id }
        val target = snapshot.references(language.region).associateBy { it.id }
        val alignment = CompleteScriptAlignment(scripts)
        val coverage = mutableListOf<JsonObject>(); val provenance = mutableListOf<JsonObject>()
        val baseline = mutableListOf<JsonObject>(); val payloadRows = mutableListOf<JsonObject>()
        val payloads = linkedMapOf<String, AssetBytes>(); val proofs = mutableListOf<CandidateProof>()
        val seen = mutableSetOf<String>(); val usedReviews = mutableSetOf<String>()
        var translated = 0; var changedFields = 0
        assets.sortedBy { it.fingerprint.path.value }.forEach { original ->
            checkCancelled()
            val entry = manifests.verifyAsset(manifest, original).valueOrThrow()
            require(entry.logicalPath == baselineProfile.assets.getValue(original.fingerprint.path.value).logical)
            require(entry.logicalPath.matches(Regex("ScriptActionEncrypt/[A-Za-z0-9_]{1,64}")) &&
                entry.logicalPath !in setOf("ScriptActionEncrypt/Common", "ScriptActionEncrypt/ScriptFileList"))
            val before = containers.decode(original).valueOrThrow()
            val edits = mutableListOf<FgoJpContainerCodec.ScriptReplacement>()
            before.scripts.sortedBy { it.binding.scriptId.value }.forEach { source ->
                checkCancelled()
                val id = source.binding.scriptId.value
                require(seen.add(id)) { "Script identity occurs in multiple captured bundles" }
                val a = jp[id]; val b = target[id]
                val jpBytes = a?.let(snapshot::bytes); val targetBytes = b?.let(snapshot::bytes)
                val result = when {
                    a == null -> AlignedScript(AlignmentReason.OUTSIDE_SNAPSHOT_SCOPE)
                    jpBytes == null -> AlignedScript(AlignmentReason.JP_SOURCE_MISSING)
                    b == null || targetBytes == null -> AlignedScript(AlignmentReason.REGIONAL_MISSING)
                    else -> alignment.align(source, a, jpBytes, b, targetBytes, reviewed[id]).also { if (id in reviewed) usedReviews += id }
                }
                coverage += buildJsonObject {
                    put("script_id", id); put("asset_filename", original.fingerprint.path.value)
                    put("result", result.reason.name); put("field_count", result.fieldCount); put("changed_fields", result.changedFields)
                    if (id in usedReviews) put("review_sha256", reviewed.getValue(id).sha256)
                }
                if (result.reason == AlignmentReason.TRANSLATED) {
                    translated++; changedFields += result.changedFields
                    edits += FgoJpContainerCodec.ScriptReplacement(source, requireNotNull(result.replacement))
                    provenance += buildJsonObject {
                        put("script_id", id); put("native_decoded_sha256", scripts.decodedBytes(source).valueOrThrow().sha256.hex)
                        put("jp", snapshot.sourceMetadata(requireNotNull(a))); put("target", snapshot.sourceMetadata(requireNotNull(b)))
                    }
                }
            }
            baseline += buildJsonObject {
                put("logical_bundle", entry.logicalPath); put("asset_filename", original.fingerprint.path.value)
                put("sha256", original.fingerprint.sha256.hex); put("size", original.bytes.size); put("script_count", before.scripts.size)
            }
            if (edits.isNotEmpty()) {
                val output = containers.replaceScripts(original, edits, checkCancelled).valueOrThrow()
                require(output.size <= StoryPackPolicy.MAX_ASSET)
                val after = containers.decode(OriginalAsset.capture(original.fingerprint.path, output.toByteArray())).valueOrThrow()
                require(before.scripts.map { it.binding } == after.scripts.map { it.binding })
                val expected = edits.associate { it.source.binding to it.replacement }
                before.scripts.zip(after.scripts).forEach { (a, b) -> require(b.payload == (expected[a.binding] ?: a.payload)) { "Uncovered native script changed" } }
                val path = "bundles/${output.sha256.hex}.bin"
                require(payloads.put(path, output) == null)
                payloadRows += buildJsonObject {
                    put("logical_bundle", entry.logicalPath); put("asset_filename", original.fingerprint.path.value)
                    put("original_sha256", original.fingerprint.sha256.hex); put("original_size", original.bytes.size)
                    put("path", path); put("sha256", output.sha256.hex); put("size", output.size); put("crc32", crc32(output.toByteArray()))
                    put("translated_scripts", JsonArray(edits.map { it.source.binding.scriptId.value }.sorted().map(::JsonPrimitive)))
                }
                proofs += CandidateProof(original.fingerprint.path.value, containers.decodeUnity(original.bytes).valueOrThrow().toByteArray(),
                    containers.decodeUnity(output).valueOrThrow().toByteArray(), edits.map { it.source.binding.scriptId.value }.sorted())
            }
        }
        require(usedReviews == reviewed.keys) { "A reviewed mapping was not used; inspect missing/changed inputs" }
        (jp.keys - seen).sorted().forEach { id -> coverage += buildJsonObject {
            put("script_id", id); put("asset_filename", JsonNull); put("result", AlignmentReason.NOT_IN_NATIVE_BASELINE.name)
            put("field_count", 0); put("changed_fields", 0)
        } }
        require(seen.size <= 8192 && payloads.values.sumOf { it.size.toLong() } <= 32 * 1024 * 1024)
        checkCancelled()
        val metadata = PreparationFiles.canonical(buildJsonObject {
            put("type", "fgogotran-native-candidate"); put("schema_version", 1)
            put("status", "development_unverified"); put("installable", false); put("native_playback_verified", false)
            put("target_language", language.tag); put("game_package", StoryPackPolicy.GAME_PACKAGE); put("codec_profile", StoryPackPolicy.CODEC_PROFILE)
            put("resource_profile", baselineProfile.id)
            put("game_version", buildJsonObject { put("name", version.name); put("code", version.code) })
            put("alignment_profile", "complete-script-exact-program@1")
            put("scope", "Supplied JP captures and requested Atlas wars only; not full JP coverage or native acceptance")
            put("source_snapshot_sha256", snapshot.contentSha256); put("source_upstream", snapshot.index.getValue("upstream"))
            // Provenance only, not a universal install prerequisite or a shipped index.
            put("captured_index_sha256", manifest.fingerprint.sha256.hex)
            put("baseline_assets", JsonArray(baseline)); put("payloads", JsonArray(payloadRows))
            put("coverage", JsonArray(coverage.sortedBy { it.string("script_id") })); put("sources", JsonArray(provenance.sortedBy { it.string("script_id") }))
            put("translated_scripts", translated); put("changed_fields", changedFields)
        })
        return BuiltNativeCandidate(metadata, payloads, proofs)
    }
}
