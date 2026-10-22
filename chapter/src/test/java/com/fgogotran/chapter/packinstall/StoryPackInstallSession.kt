package com.fgogotran.chapter.packinstall

// Historical source-pack preparation/install orchestration: offline tests only.

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.compile.StoryPackCompiler
import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.library.StoryPackLibrary
import com.fgogotran.chapter.packs.StoryGameVersion
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class StoryPackInstallSession internal constructor(
    store: StoryPackOperationStore, private val library: StoryPackLibrary,
    transport: ChapterInstallTransport, private val gate: StoryNativeGateSource,
    private val compiler: StoryPackCompiler = StoryPackCompiler(),
    private val snapshotPolicy: (ChapterInstallRecord) -> Unit = ChapterInstallPolicy::requireOriginals,
    private val validatePair: (ByteArray, ByteArray) -> Unit = ::validateNativeInstallPair
) : StoryPackRecoverySession(store, transport) {
    suspend fun prepare(storage: ChapterOriginalStorage) = operation {
        check(store.load()?.transport?.state in listOf(null, ChapterInstallState.RESTORED)) { "Restore/close the previous composition before preparing changes" }
        val evidence = gate.verified() // Fail before any game read, backup or compiler work if native gate is unproven.
        val desired = store.loadSelection()
        require(desired.archives.isNotEmpty()) { "Select at least one imported language pack" }
        val entries = library.list().filter { it.sha256 in desired.archives }
        require(entries.size == desired.archives.size && entries.all { it.language == desired.language }) { "Saved selection is unavailable; reload imported packs" }
        val context = currentCoroutineContext()
        val packs = library.load(entries) { context.ensureActive() }
        require(packs.flatMap { it.manifest.scripts }.all { it.logicalBundle == ChapterInstallPolicy.GROUP && it.assetFilename == ChapterInstallPolicy.BUNDLE }) {
            "Write-back is limited to reviewed group 01; other bundles remain preview-only"
        }
        val environment = transport.environment()
        check(environment.stopped && environment.identity == evidence.game) { "Force stop the same gate-verified JP installation" }
        suspend fun read(name: String): OriginalAsset = when (val value = storage.readOriginal(GameAssetPath.parse(name))) {
            is OriginalAssetReadResult.Read -> value.original.also { require(it.fingerprint.path.value == name) }
            else -> error("Required original story assets are unavailable")
        }
        val manifest = read(ChapterInstallPolicy.MANIFEST); val bundle = read(ChapterInstallPolicy.BUNDLE)
        val originalBundle = bundle.bytes.toByteArray(); val originalManifest = manifest.bytes.toByteArray()
        evidence.bundle.verify(originalBundle); evidence.manifest.verify(originalManifest)
        val candidate = ChapterInstallRecord("0".repeat(32), environment.identity, evidence.bundle, evidence.manifest)
        snapshotPolicy(candidate); validatePair(originalBundle, originalManifest)
        check(transport.inspect(candidate) == ChapterPairState.ORIGINAL)
        val compiled = compiler.compile(packs, StoryGameVersion(environment.identity.versionName, environment.identity.versionCode), manifest, listOf(bundle)).valueOrThrow()
        require(compiled.bundles.size == 1)
        val rebuilt = compiled.bundles.single().replacement.toByteArray(); val rebuiltManifest = compiled.manifest.toByteArray()
        validatePair(rebuilt, rebuiltManifest)
        check(read(ChapterInstallPolicy.MANIFEST).fingerprint == manifest.fingerprint && read(ChapterInstallPolicy.BUNDLE).fingerprint == bundle.fingerprint)
        check(transport.environment() == environment && gate.verified() == evidence && store.loadSelection() == desired) { "Gate, game or selection changed during preparation" }
        currentCoroutineContext().ensureActive()
        val references = entries.map(StoryPackReference::from)
        val digest = StoryPackOperation.composition(evidence, desired.revision, desired.language, references)
        val record = ChapterInstallRecord(UUID.randomUUID().toString().replace("-", ""), environment.identity, evidence.bundle, evidence.manifest,
            state = ChapterInstallState.PREPARED, mode = ChapterRebuildMode.LANGUAGE_PACK,
            replacementBundle = InstallFileHash.of(rebuilt), replacementManifest = InstallFileHash.of(rebuiltManifest),
            unchangedVerified = true, packCompositionSha256 = digest)
        publish(store.create(StoryPackOperation(record, desired.revision, desired.language, references, evidence), originalBundle, originalManifest, rebuilt, rebuiltManifest), false)
    }

    suspend fun install(approved: Boolean) = operation {
        check(approved) { "Confirm disposable installation and independently verified PC originals" }
        var value = active(); val record = value.transport
        check(!mutableState.value.needsInspection && record.state == ChapterInstallState.PREPARED && record.exportSha256 != null)
        check(value.matches(store.loadSelection())) { "Desired selection changed; restore/close this plan and prepare again" }
        check(gate.verified() == value.gate) { "Native gate changed; do not install this prepared operation" }
        snapshotPolicy(record)
        for (prepared in listOf(false, true)) for (bundle in listOf(false, true)) store.bytes(record, bundle, prepared)
        transport.stage(record)
        currentCoroutineContext().ensureActive()
        value = change(value, ChapterInstallState.STAGED); publish(value, true)
        critical {
            // Recheck intent immediately before the durable COMMITTING boundary.
            check(value.matches(store.loadSelection()) && gate.verified() == value.gate)
            value = change(value, ChapterInstallState.COMMITTING); publish(value, true)
            transport.commit(value.transport)
            check(transport.inspect(value.transport) == ChapterPairState.REPLACEMENT)
            value = change(value, ChapterInstallState.INSTALLED); publish(value, false)
        }
    }
}
