package com.fgogotran.chapter.install

import com.fgogotran.chapter.Sha256Digest
import java.io.Closeable
import kotlinx.serialization.json.*

/** Bound for one write. FGO may replace its inode later; owner/access must remain valid. */
data class ChapterFileIdentity(val device: Long, val inode: Long, val uid: Int, val gid: Int, val mode: Int) {
    init { require(device >= 0 && inode > 0 && uid >= 0 && gid >= 0 && mode in 0..511) }
    fun requireGameAccess(gameUid: Int) { check(uid == gameUid && mode and 384 == 384) { "Live file must remain FGO-owned and owner-readable/writable" } }
}

/** One existing regular inode, opened without CREATE/TRUNCATE; no rename/chmod/chown. */
interface ChapterLiveFile : Closeable {
    val identity: ChapterFileIdentity
    fun verifyIdentity()
    fun read(): ByteArray
    fun write(offset: Int, bytes: ByteArray, count: Int)
    fun truncate(size: Int)
    fun sync()
}

/** Immutable intent, published/read back/fsynced before the first live byte changes. */
internal data class ChapterWriteIntent(
    val plan: String, val identity: ChapterFileIdentity, val before: InstallFileHash, val after: InstallFileHash
) {
    fun encode(): ByteArray = buildJsonObject {
        put("schema", 1); put("plan", plan)
        put("identity", buildJsonObject {
            put("device", identity.device); put("inode", identity.inode); put("uid", identity.uid); put("gid", identity.gid); put("mode", identity.mode)
        })
        fun hash(value: InstallFileHash) = buildJsonObject { put("sha256", value.sha256); put("size", value.size) }
        put("before", hash(before)); put("after", hash(after))
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): ChapterWriteIntent {
            require(bytes.size <= ChapterInstallPolicy.MAX_RECORD)
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(json.getValue("schema").jsonPrimitive.int == 1)
            val identity = json.getValue("identity").jsonObject
            fun hash(name: String) = json.getValue(name).jsonObject.let { InstallFileHash(it.getValue("sha256").jsonPrimitive.content, it.getValue("size").jsonPrimitive.int) }
            val plan = json.getValue("plan").jsonPrimitive.content.also { Sha256Digest.parse(it) }
            return ChapterWriteIntent(plan, ChapterFileIdentity(identity.getValue("device").jsonPrimitive.long, identity.getValue("inode").jsonPrimitive.long,
                identity.getValue("uid").jsonPrimitive.int, identity.getValue("gid").jsonPrimitive.int, identity.getValue("mode").jsonPrimitive.int), hash("before"), hash("after"))
        }
    }
}

/** Bind fixed role/direction plus immutable job/game/baseline/replacement fields, not mutable UI state. */
internal fun writePlan(record: ChapterInstallRecord, role: String, restore: Boolean, restoreManifest: InstallFileHash? = null): String {
    // Frozen binding schema, independent of later app-journal/UI fields and their JSON schema.
    fun InstallFileHash?.binding() = this?.let { "${it.sha256}:${it.size}" } ?: "NONE"
    val stable = buildJsonObject {
        put("binding_schema", if (record.mode == ChapterRebuildMode.LANGUAGE_PACK) 2 else 1)
        if (record.mode == ChapterRebuildMode.LANGUAGE_PACK) put("pack_composition", record.packCompositionSha256)
        put("job", record.id); put("protocol", record.writeProtocol.name)
        put("root", ChapterInstallPolicy.ROOT_ID); put("role", role); put("restore", restore)
        put("version", record.game.versionName); put("code", record.game.versionCode)
        put("updated", record.game.updatedAt); put("uid", record.game.uid); put("mode", record.mode?.name ?: "ORIGINAL")
        put("original_bundle", record.originalBundle.binding()); put("original_manifest", record.originalManifest.binding())
        put("replacement_bundle", record.replacementBundle.binding()); put("replacement_manifest", record.replacementManifest.binding())
    }
    val original = Sha256Digest.compute(stable.toString().toByteArray(Charsets.UTF_8)).hex
    if (restoreManifest == null) return original // Existing receipts retain their exact binding.
    require(role == "manifest" && restore)
    return Sha256Digest.compute("$original:current-index-v1:${restoreManifest.binding()}".toByteArray(Charsets.UTF_8)).hex
}

/** Sequential overwrite, then final truncate: prefix(new) + suffix(old), never arbitrary corruption. */
internal fun matchesInterruptedWrite(current: ByteArray, before: ByteArray, after: ByteArray): Boolean {
    if (current.contentEquals(after)) return true
    if (current.size < before.size || current.size > maxOf(before.size, after.size)) return false
    var prefix = 0
    while (prefix < minOf(current.size, after.size) && current[prefix] == after[prefix]) prefix++
    if (current.size > before.size) return prefix == current.size
    var cut = before.size
    while (cut > 0 && current[cut - 1] == before[cut - 1]) cut--
    return cut <= prefix
}

internal class ChapterInPlaceWriter(private val files: ChapterInstallFiles, private val manifestRecovery: ChapterManifestRecovery? = null) {
    private fun name(record: ChapterInstallRecord, role: String, restore: Boolean, part: String) = ChapterInstallPolicy.writeName(record.id, role, restore, part)
    private fun intent(record: ChapterInstallRecord, role: String, restore: Boolean): ChapterWriteIntent? {
        val name = name(record, role, restore, "intent")
        if (!files.exists(name)) return null
        return ChapterWriteIntent.decode(files.read(name)).also {
            val expected = if (restore) { if (role == "bundle") record.originalBundle else record.originalManifest }
                else { if (role == "bundle") record.replacementBundle else record.replacementManifest }
            val adapted = restore && role == "manifest" && it.after != expected
            check(it.plan == writePlan(record, role, restore, if (adapted) it.after else null)) { "Write intent belongs to a different plan" }
            if (adapted) {
                val recovery = requireNotNull(manifestRecovery) { "Current-index recovery references required" }
                val before = files.read(name(record, role, true, "before")).also(it.before::verify)
                val after = files.read(ChapterInstallPolicy.stageName(record.id, role, true)).also(it.after::verify)
                recovery.verifyRestore(before, after)
            } else check(it.after == expected) { "Write target differs from the journal" }
        }
    }
    private fun completed(record: ChapterInstallRecord, role: String, restore: Boolean, receipt: ChapterWriteIntent): Boolean {
        val done = name(record, role, restore, "done")
        return files.exists(done) && files.read(done).contentEquals(Sha256Digest.compute(receipt.encode()).hex.toByteArray(Charsets.UTF_8))
    }
    fun verifyKnownIdentity(record: ChapterInstallRecord, role: String, identity: ChapterFileIdentity, afterCompletedRun: Boolean = false) {
        identity.requireGameAccess(record.game.uid)
        for (restore in listOf(false, true)) intent(record, role, restore)?.let {
            val old = it.identity
            check(old == identity || (afterCompletedRun && completed(record, role, restore, it) &&
                old.uid == identity.uid && old.gid == identity.gid && old.mode == identity.mode)) {
                "Live inode or access changed during an unfinished write; preserve backups for review"
            }
        }
    }
    fun completed(record: ChapterInstallRecord, role: String): Boolean {
        val original = if (role == "bundle") record.originalBundle else record.originalManifest
        val replacement = if (role == "bundle") record.replacementBundle else record.replacementManifest
        if (original == replacement) return true // No live write was necessary.
        val receipt = intent(record, role, false) ?: return false
        return completed(record, role, false, receipt)
    }
    fun savedRestoreManifest(record: ChapterInstallRecord): ByteArray? {
        val receipt = intent(record, "manifest", true) ?: return null
        return files.read(ChapterInstallPolicy.stageName(record.id, "manifest", true)).also(receipt.after::verify)
    }
    fun interrupted(record: ChapterInstallRecord, role: String, identity: ChapterFileIdentity, current: ByteArray): Boolean {
        if (record.state !in listOf(
                ChapterInstallState.COMMITTING, ChapterInstallState.RESTORING, ChapterInstallState.RECOVERY_REQUIRED)) return false
        val restore = files.exists(name(record, role, true, "intent"))
        if (record.writeProtocol != ChapterWriteProtocol.IN_PLACE_V1 && !restore) return false
        val intent = intent(record, role, restore) ?: return false
        if (intent.identity != identity || files.exists(name(record, role, restore, "done"))) return false
        val before = files.read(name(record, role, restore, "before")).also(intent.before::verify)
        val after = files.read(ChapterInstallPolicy.stageName(record.id, role, restore)).also(intent.after::verify)
        return matchesInterruptedWrite(current, before, after)
    }
    fun write(record: ChapterInstallRecord, role: String, restore: Boolean, desired: ByteArray,
        afterCompletedRun: Boolean = false, gate: () -> Unit) {
        val live = if (role == "bundle") ChapterInstallPolicy.BUNDLE else ChapterInstallPolicy.MANIFEST
        val desiredHash = InstallFileHash.of(desired)
        val adapted = restore && role == "manifest" && desiredHash != record.originalManifest
        val expected = if (adapted) {
            check(requireNotNull(manifestRecovery).state(desired) == 0) { "Restore index must contain the original story entry" }
            desiredHash
        } else if (restore) { if (role == "bundle") record.originalBundle else record.originalManifest }
            else { if (role == "bundle") record.replacementBundle else record.replacementManifest }
        check(desiredHash == expected) { "Write bytes differ from the prepared plan" }
        gate()
        files.openLive(live, record.game.uid).use { target ->
            target.verifyIdentity(); verifyKnownIdentity(record, role, target.identity, afterCompletedRun)
            val current = target.read()
            if (current.contentEquals(desired)) return // Never discard a valid before-image on a retry.
            val original = if (role == "bundle") record.originalBundle else record.originalManifest
            val replacement = if (role == "bundle") record.replacementBundle else record.replacementManifest
            val currentHash = current.takeIf { it.isNotEmpty() }?.let(InstallFileHash::of)
            val knownIndex = afterCompletedRun && role == "manifest" && manifestRecovery?.state(current) != null
            check(if (restore) currentHash in listOfNotNull(original, replacement) || knownIndex || interrupted(record, role, target.identity, current)
                else currentHash == original) { "Live bytes changed before write; preserve backups" }
            val existing = intent(record, role, restore)
            if (knownIndex && existing == null) requireNotNull(manifestRecovery).verifyRestore(current, desired)
            val receipt = existing ?: ChapterWriteIntent(writePlan(record, role, restore, if (adapted) desiredHash else null), target.identity, InstallFileHash.of(current), desiredHash).also {
                files.stage(name(record, role, restore, "before"), current)
                it.before.verify(files.read(name(record, role, restore, "before")))
                files.stage(name(record, role, restore, "intent"), it.encode())
            }
            check(receipt == intent(record, role, restore) && receipt.after == desiredHash && receipt.identity == target.identity)
            if (existing != null) {
                check(!files.exists(name(record, role, restore, "done"))) { "A completed write changed externally" }
                val before = files.read(name(record, role, restore, "before")).also(receipt.before::verify)
                check(matchesInterruptedWrite(current, before, desired)) { "Unrecognised interrupted write" }
                // Re-establish durability if a previous attempt failed during artifact sync.
                files.stage(name(record, role, restore, "before"), before)
                files.stage(name(record, role, restore, "intent"), receipt.encode())
            }
            gate(); target.verifyIdentity()
            check(target.read().contentEquals(current)) { "Live bytes changed after durable intent" }
            // No initial truncation: interrupted growth/overwrite retains a verifiable old suffix.
            var offset = 0
            while (offset < desired.size) {
                val count = minOf(8192, desired.size - offset)
                target.write(offset, desired, count); offset += count
            }
            target.truncate(desired.size); target.sync(); target.verifyIdentity()
            check(target.read().contentEquals(desired)) { "Live read-back differs" }
            gate()
            // Immutable completion marker. Later inspection still requires known content.
            files.stage(name(record, role, restore, "done"), Sha256Digest.compute(receipt.encode()).hex.toByteArray(Charsets.UTF_8))
        }
    }
}
