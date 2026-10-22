package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.compile.SyntheticStoryAssets
import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.library.*
import com.fgogotran.chapter.packs.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.*

internal class PackDurable : ChapterDurableFiles {
    private val delegate = TestChapterDurableFiles()
    var beforeState: ChapterInstallState? = null
    var afterState: ChapterInstallState? = null
    override fun read(file: File, maxBytes: Int) = delegate.read(file, maxBytes)
    override fun syncDirectory(directory: File) = delegate.syncDirectory(directory)
    override fun write(file: File, bytes: ByteArray) {
        val state = if (file.name == "operation.json") StoryPackOperationJson.decode(bytes).transport.state else null
        if (state != null && state == beforeState) throw IOException("Before $state journal")
        delegate.write(file, bytes)
        if (state != null && state == afterState) throw IOException("After $state journal")
    }
}

/** Synthetic local files + simulated gate, never a device/native-playback approval. */
internal class PackInstallFixture(val folder: File) {
    val native = SyntheticStoryAssets()
    val raw = "＄synthetic\n＠Speaker\nFirst\n[k]\n＠Speaker\nSecond\n[k]\n"
    val original = native.asset(mapOf("0100000011" to native.encoded(raw)))
    val index = native.manifest(listOf(ChapterInstallPolicy.GROUP to original))
    val game = ChapterGameIdentity("2.139.5", 502, 123456789, 10075)
    var status = ChapterGameStatus(game, true)
    val evidence = StoryNativeGate("b".repeat(32), "e".repeat(64), game, InstallFileHash.of(original.bytes.toByteArray()), InstallFileHash.of(index.bytes.toByteArray()))
    var gate: StoryNativeGate? = evidence
    val durable = PackDurable()
    val root = File(folder, "operation-store")
    val store = StoryPackOperationStore(root, durable)
    val library = StoryPackLibrary(File(folder, "library"))
    val live = File(folder, "live").also { check(it.mkdir()) }
    val files = TestChapterInstallFiles(live).also { it.putOriginals(original.bytes.toByteArray(), index.bytes.toByteArray()) }
    var reads = 0
    val reader = object : ChapterOriginalStorage {
        override suspend fun capability() = ChapterStorageCapability.ReadOnly("test-read-only")
        override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult {
            reads++
            return OriginalAssetReadResult.Read(OriginalAsset.capture(path, files.read(path.value)))
        }
    }
    fun validate(bundle: ByteArray, manifest: ByteArray) {
        native.manifests.verifyAsset(OriginalAsset.capture(index.fingerprint.path, manifest), OriginalAsset.capture(original.fingerprint.path, bundle)).valueOrThrow()
    }
    val engine = ChapterTransactionEngine(files, { status }, ::validate)
    var stages = 0; var commits = 0; var restores = 0; var inspections = 0
    var beforeCommit: suspend () -> Unit = {}
    val transport = object : ChapterInstallTransport {
        override suspend fun environment() = status
        override suspend fun inspect(record: ChapterInstallRecord): ChapterPairState { inspections++; return engine.inspect(record) }
        override suspend fun stage(record: ChapterInstallRecord) {
            stages++; engine.stage(record, store.bytes(record, true, true), store.bytes(record, false, true))
        }
        override suspend fun commit(record: ChapterInstallRecord) {
            commits++; check(store.load()!!.transport.state == ChapterInstallState.COMMITTING)
            beforeCommit(); engine.commit(record)
        }
        override suspend fun restore(record: ChapterInstallRecord) {
            restores++; check(store.load()!!.transport.state == ChapterInstallState.RESTORING)
            engine.restore(record, store.bytes(record, true, false), store.bytes(record, false, false))
        }
    }
    fun session() = StoryPackInstallSession(store, library, transport, StoryNativeGateSource { checkNotNull(gate) { "Native gate unproven" } }, native.compiler(),
        { check(it.game == game && it.originalBundle == evidence.bundle && it.originalManifest == evidence.manifest) }, ::validate)
    fun import(chapter: String = "intro", field: String = "First", translated: String = "Translated $chapter", revision: Int = 1): StoryCatalogueEntry {
        val source = native.source(original)
        val pack = native.pack(folder, listOf(ChapterInstallPolicy.GROUP to original), mapOf("0100000011" to listOf(native.change(source, field, StoryTextToken.Text(translated)))), chapter, revision = revision)
        val archive = folder.listFiles().orEmpty().single { it.extension == "zip" && StoryPackArchive.fingerprint(it).first == pack.archiveSha256 }
        return library.import({ archive.inputStream() })
    }
    fun desired(vararg entries: StoryCatalogueEntry) = store.saveSelection(store.loadSelection(), StoryLanguage.TRADITIONAL_CHINESE, entries.map { it.sha256 }.toSet())
    suspend fun exported(session: StoryPackInstallSession) {
        val zip = ByteArrayOutputStream()
        session.export({ zip }, { zip.toByteArray().inputStream() })
        checkNotNull(store.load()!!.transport.exportSha256)
    }
    fun journal() = File(root, "${store.load()!!.transport.id}/operation.json")
    fun currentText() = native.plain(native.source(OriginalAsset.capture(original.fingerprint.path, files.read(ChapterInstallPolicy.BUNDLE))))
}
