package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import kotlinx.serialization.json.*

/** Deliberately one observed test pair, not a general-purpose file installer. */
object ChapterInstallPolicy {
    const val BUNDLE = "3b6e743e6f41fb01a1037cb15169096d6a63198c.bin"
    const val MANIFEST = FgoJpFormatProfile.MANIFEST_NAME
    const val GROUP = "ScriptActionEncrypt/01"
    const val SCRIPT = "0100000011"
    const val ROOT_ID = "jp-fgo:android-user-0:external-data-d713"
    const val MAX_FILE = 8 * 1024 * 1024
    const val MAX_RECORD = 24 * 1024
    const val ORIGINAL_BUNDLE_SHA256 = "a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229"
    const val ORIGINAL_MANIFEST_SHA256 = "c35317334b5a99bd9f0ffcf09ac435706828cb0f0b9e6aaf7bffab6f5c75f7ec"
    const val UPDATED_MANIFEST_SHA256 = "9911747d1f6b6f1e150cd6ebf508ba5bcd136c78c8f7a92026c2a3ab8e5098ab"
    fun requireOriginals(record: ChapterInstallRecord) {
        val manifest = when (record.game.versionName to record.game.versionCode) {
            "2.139.3" to 501L -> ORIGINAL_MANIFEST_SHA256
            "2.139.5" to 502L -> UPDATED_MANIFEST_SHA256
            else -> error("Unsupported game snapshot")
        }
        require(record.originalBundle.sha256 == ORIGINAL_BUNDLE_SHA256 && record.originalManifest.sha256 == manifest) {
            "Unsupported original asset snapshot; keep this installation read-only"
        }
    }
    fun transactionId(id: String): String = id.also { require(it.matches(Regex("[0-9a-f]{32}"))) }
    fun stageName(id: String, role: String, restore: Boolean = false): String {
        transactionId(id); require(role in listOf("bundle", "manifest"))
        return ".fgogotran-$id-$role.${if (restore) "restore" else "new"}"
    }
    fun filename(name: String): String = name.also {
        require(it in listOf(BUNDLE, MANIFEST, ".fgogotran-install.lock") ||
            it.matches(Regex("\\.fgogotran-[0-9a-f]{32}-(?:(bundle|manifest)\\.(new|restore)|probe\\.[ab])")))
    }
}

data class ChapterGameIdentity(val versionName: String, val versionCode: Long, val updatedAt: Long, val uid: Int) {
    init {
        require((versionName == "2.139.3" && versionCode == 501L) || (versionName == "2.139.5" && versionCode == 502L)) {
            "Only the observed JP 2.139.3 and 2.139.5 test formats are enabled"
        }
        require(updatedAt > 0 && uid in 10000..99999)
    }
}

data class InstallFileHash(val sha256: String, val size: Int) {
    init { Sha256Digest.parse(sha256); require(sha256 == sha256.lowercase()); require(size in 1..ChapterInstallPolicy.MAX_FILE) }
    fun verify(bytes: ByteArray) { require(of(bytes) == this) { "File hash/size mismatch" } }
    companion object { fun of(bytes: ByteArray) = InstallFileHash(Sha256Digest.compute(bytes).hex, bytes.size) }
}

enum class ChapterRebuildMode { UNCHANGED, MARKER }
enum class ChapterInstallState { BACKED_UP, PREPARED, STAGED, COMMITTING, INSTALLED, RESTORING, RESTORED, RECOVERY_REQUIRED }
enum class ChapterPairState { ORIGINAL, REPLACEMENT, MIXED, UNKNOWN }

data class ChapterInstallRecord(
    val id: String,
    val game: ChapterGameIdentity,
    val originalBundle: InstallFileHash,
    val originalManifest: InstallFileHash,
    val state: ChapterInstallState = ChapterInstallState.BACKED_UP,
    val mode: ChapterRebuildMode? = null,
    val replacementBundle: InstallFileHash? = null,
    val replacementManifest: InstallFileHash? = null,
    val exportSha256: String? = null,
    val playbackConfirmed: Boolean = false,
    val unchangedVerified: Boolean = false
) {
    init {
        ChapterInstallPolicy.transactionId(id)
        require((replacementBundle == null) == (replacementManifest == null))
        require((mode == null) == (replacementBundle == null))
        require(state in listOf(ChapterInstallState.BACKED_UP, ChapterInstallState.RESTORED) || mode != null)
        exportSha256?.let { Sha256Digest.parse(it) }
        require(!playbackConfirmed || mode == ChapterRebuildMode.UNCHANGED)
        require(mode != ChapterRebuildMode.MARKER || unchangedVerified) { "Unchanged playback and restoration must pass first" }
    }

    fun classify(bundle: InstallFileHash?, manifest: InstallFileHash?): ChapterPairState = when {
        bundle == originalBundle && manifest == originalManifest -> ChapterPairState.ORIGINAL
        replacementBundle != null && bundle == replacementBundle && manifest == replacementManifest -> ChapterPairState.REPLACEMENT
        replacementBundle != null && bundle != null && manifest != null &&
            bundle in listOf(originalBundle, replacementBundle) && manifest in listOf(originalManifest, replacementManifest) -> ChapterPairState.MIXED
        else -> ChapterPairState.UNKNOWN
    }
}

/** Versioned, bounded metadata only. No story text, credentials or caller-supplied paths. */
object ChapterInstallRecordCodec {
    private fun InstallFileHash.json() = buildJsonObject { put("sha256", sha256); put("size", size) }
    fun encode(value: ChapterInstallRecord): String = buildJsonObject {
        put("schema", 1); put("id", value.id)
        put("package", FgoJpFormatProfile.GAME_PACKAGE); put("root", ChapterInstallPolicy.ROOT_ID)
        put("bundle", ChapterInstallPolicy.BUNDLE); put("manifest", ChapterInstallPolicy.MANIFEST)
        put("script", ChapterInstallPolicy.SCRIPT)
        put("field", if (value.mode == ChapterRebuildMode.MARKER) JsonPrimitive("dialogue:691") else JsonNull)
        put("game", buildJsonObject { put("name", value.game.versionName); put("code", value.game.versionCode); put("updated", value.game.updatedAt); put("uid", value.game.uid) })
        put("originalBundle", value.originalBundle.json()); put("originalManifest", value.originalManifest.json())
        put("state", value.state.name)
        value.mode?.let { put("mode", it.name) }
        value.replacementBundle?.let { put("replacementBundle", it.json()) }
        value.replacementManifest?.let { put("replacementManifest", it.json()) }
        value.exportSha256?.let { put("export", it) }
        put("playbackConfirmed", value.playbackConfirmed); put("unchangedVerified", value.unchangedVerified)
    }.toString()

    fun decode(text: String): ChapterInstallRecord {
        require(text.toByteArray(Charsets.UTF_8).size <= ChapterInstallPolicy.MAX_RECORD)
        val obj = Json.parseToJsonElement(text).jsonObject
        fun string(name: String) = obj.getValue(name).jsonPrimitive.content
        require(obj.getValue("schema").jsonPrimitive.int == 1)
        require(string("package") == FgoJpFormatProfile.GAME_PACKAGE && string("root") == ChapterInstallPolicy.ROOT_ID)
        require(string("bundle") == ChapterInstallPolicy.BUNDLE && string("manifest") == ChapterInstallPolicy.MANIFEST)
        require(string("script") == ChapterInstallPolicy.SCRIPT)
        val mode = obj["mode"]?.jsonPrimitive?.content?.let(ChapterRebuildMode::valueOf)
        require(obj.getValue("field").jsonPrimitive.contentOrNull == if (mode == ChapterRebuildMode.MARKER) "dialogue:691" else null)
        fun hash(name: String): InstallFileHash = obj.getValue(name).jsonObject.let { InstallFileHash(it.getValue("sha256").jsonPrimitive.content, it.getValue("size").jsonPrimitive.int) }
        val game = obj.getValue("game").jsonObject
        return ChapterInstallRecord(string("id"), ChapterGameIdentity(game.getValue("name").jsonPrimitive.content,
            game.getValue("code").jsonPrimitive.long, game.getValue("updated").jsonPrimitive.long, game.getValue("uid").jsonPrimitive.int),
            hash("originalBundle"), hash("originalManifest"), ChapterInstallState.valueOf(string("state")),
            mode,
            obj["replacementBundle"]?.let { hash("replacementBundle") }, obj["replacementManifest"]?.let { hash("replacementManifest") },
            obj["export"]?.jsonPrimitive?.content, obj.getValue("playbackConfirmed").jsonPrimitive.boolean, obj.getValue("unchangedVerified").jsonPrimitive.boolean)
    }
}
