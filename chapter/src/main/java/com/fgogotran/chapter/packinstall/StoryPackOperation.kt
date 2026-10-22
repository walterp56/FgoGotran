package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.*
import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.library.StoryPackSelection
import com.fgogotran.chapter.packs.*
import kotlinx.serialization.json.*

data class StoryPackReference(val id: String, val revision: Int, val chapter: String, val language: StoryLanguage, val sha256: String) {
    init { StoryPackPolicy.id(id); StoryPackPolicy.id(chapter); require(revision > 0); StoryPackPolicy.digest(sha256) }
    companion object { fun from(entry: StoryCatalogueEntry) = StoryPackReference(entry.packId, entry.revision, entry.chapterId, entry.language, entry.sha256) }
}

/** Evidence is derived from the separate restored unchanged test; never an owner/AI-editable switch. */
data class StoryNativeGate(val job: String, val recordSha256: String, val game: ChapterGameIdentity, val bundle: InstallFileHash, val manifest: InstallFileHash) {
    init { ChapterInstallPolicy.transactionId(job); StoryPackPolicy.digest(recordSha256) }
}

fun interface StoryNativeGateSource { fun verified(): StoryNativeGate }

class RestoredStoryNativeGate(private val tests: ChapterBackupStore) : StoryNativeGateSource {
    override fun verified(): StoryNativeGate {
        val record = requireNotNull(tests.load()) { "Complete the unchanged native playback/restoration test first" }
        check(record.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1 && record.state == ChapterInstallState.RESTORED &&
            record.mode == ChapterRebuildMode.UNCHANGED && record.playbackConfirmed && record.unchangedVerified && record.exportSha256 != null) {
            "Unchanged native playback and exact restoration have not passed; language-pack writes remain blocked"
        }
        ChapterInstallPolicy.requireOriginals(record)
        tests.original(record, true); tests.original(record, false)
        tests.prepared(record, true); tests.prepared(record, false)
        return StoryNativeGate(record.id, Sha256Digest.compute(ChapterInstallRecordCodec.encode(record).toByteArray()).hex,
            record.game, record.originalBundle, record.originalManifest)
    }
}

class StoryPackOperation(val transport: ChapterInstallRecord, val desiredRevision: Long, val language: StoryLanguage,
    references: Collection<StoryPackReference>, val gate: StoryNativeGate) {
    val references = immutableList(references.sortedBy { it.id })
    init {
        require(desiredRevision > 0 && this.references.size in 1..32)
        require(this.references.map { it.id }.distinct().size == this.references.size && this.references.map { it.chapter }.distinct().size == this.references.size)
        require(this.references.all { it.language == language })
        require(transport.mode == ChapterRebuildMode.LANGUAGE_PACK && transport.writeProtocol == ChapterWriteProtocol.IN_PLACE_V1)
        require(transport.state != ChapterInstallState.BACKED_UP)
        require(gate.game == transport.game && gate.bundle == transport.originalBundle && gate.manifest == transport.originalManifest)
        require(transport.packCompositionSha256 == composition(gate, desiredRevision, language, this.references)) { "Language-pack composition binding differs" }
    }
    fun withTransport(value: ChapterInstallRecord) = StoryPackOperation(value, desiredRevision, language, references, gate)
    fun matches(selection: StoryPackSelection) = desiredRevision == selection.revision && language == selection.language && references.map { it.sha256 }.toSet() == selection.archives

    companion object {
        fun composition(gate: StoryNativeGate, revision: Long, language: StoryLanguage, references: Collection<StoryPackReference>): String =
            Sha256Digest.compute(buildJsonObject {
                put("schema", 1); put("gate", gate.recordSha256); put("gate_job", gate.job); put("selection_revision", revision); put("language", language.tag)
                put("original_bundle", "${gate.bundle.sha256}:${gate.bundle.size}"); put("original_manifest", "${gate.manifest.sha256}:${gate.manifest.size}")
                put("packs", buildJsonArray { references.sortedBy { it.id }.forEach { add(it.json()) } })
            }.toString().toByteArray()).hex
    }
}

private fun StoryPackReference.json() = buildJsonObject {
    put("id", id); put("revision", revision); put("chapter", chapter); put("language", language.tag); put("sha256", sha256)
}

internal object StoryPackOperationJson {
    const val MAX = 24 * 1024
    fun selection(value: StoryPackSelection): ByteArray = buildJsonObject {
        put("schema", 1); put("type", "story-pack-desired"); put("revision", value.revision); put("language", value.language.tag)
        put("archives", JsonArray(value.archives.map(::JsonPrimitive)))
    }.toString().toByteArray()
    fun selection(bytes: ByteArray): StoryPackSelection {
        val obj = StoryPackJson.metadata(bytes, MAX)
        require(obj.keys == setOf("schema", "type", "revision", "language", "archives") && obj.getValue("schema").jsonPrimitive.int == 1 && obj.getValue("type").jsonPrimitive.content == "story-pack-desired")
        return StoryPackSelection(obj.getValue("revision").jsonPrimitive.long, StoryLanguage.parse(obj.getValue("language").jsonPrimitive.content), obj.getValue("archives").jsonArray.map { it.jsonPrimitive.content })
    }
    fun encode(value: StoryPackOperation): ByteArray = buildJsonObject {
        put("schema", 1); put("type", "story-pack-operation"); put("transport", Json.parseToJsonElement(ChapterInstallRecordCodec.encode(value.transport)))
        put("desired_revision", value.desiredRevision); put("language", value.language.tag)
        put("references", buildJsonArray { value.references.forEach { add(it.json()) } })
        put("gate_job", value.gate.job); put("gate_record_sha256", value.gate.recordSha256)
    }.toString().toByteArray().also { require(it.size <= MAX) }
    fun decode(bytes: ByteArray): StoryPackOperation {
        val obj = StoryPackJson.metadata(bytes, MAX)
        require(obj.keys == setOf("schema", "type", "transport", "desired_revision", "language", "references", "gate_job", "gate_record_sha256") &&
            obj.getValue("schema").jsonPrimitive.int == 1 && obj.getValue("type").jsonPrimitive.content == "story-pack-operation")
        val plan = ChapterInstallRecordCodec.decode(obj.getValue("transport").toString())
        val references = obj.getValue("references").jsonArray.map { item ->
            val ref = item.jsonObject
            require(ref.keys == setOf("id", "revision", "chapter", "language", "sha256"))
            StoryPackReference(ref.getValue("id").jsonPrimitive.content, ref.getValue("revision").jsonPrimitive.int, ref.getValue("chapter").jsonPrimitive.content,
                StoryLanguage.parse(ref.getValue("language").jsonPrimitive.content), ref.getValue("sha256").jsonPrimitive.content)
        }
        return StoryPackOperation(plan, obj.getValue("desired_revision").jsonPrimitive.long, StoryLanguage.parse(obj.getValue("language").jsonPrimitive.content), references,
            StoryNativeGate(obj.getValue("gate_job").jsonPrimitive.content, obj.getValue("gate_record_sha256").jsonPrimitive.content, plan.game, plan.originalBundle, plan.originalManifest))
    }
}
