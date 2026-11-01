package com.fgogotran.chapter.install

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

/** Authentic bytes, but only JVM temp directories. NEVER binds Shizuku, calls ADB or installs. */
class LocalChapterInstallTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `authentic captured startup rewrite restores index control without a false native pass`() = runBlocking<Unit> {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        val afterPath = System.getenv("CHAPTER_COMPARE_CAPTURE").orEmpty()
        assumeTrue("Two independent real captures are opt-in", directory.isNotEmpty() && afterPath.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        val capture = LocalChapterPaths.capture(module, directory)
        val after = LocalChapterPaths.capture(module, afterPath)
        val bundle = File(capture, ChapterInstallPolicy.BUNDLE).readBytes()
        val manifest = File(capture, ChapterInstallPolicy.MANIFEST).readBytes()
        val gameIndex = File(after, ChapterInstallPolicy.MANIFEST).readBytes()
        assertContentEquals(bundle, File(after, ChapterInstallPolicy.BUNDLE).readBytes())
        assertNotEquals(InstallFileHash.of(manifest), InstallFileHash.of(gameIndex))
        val store = ChapterBackupStore(temporary.newFolder(), TestChapterDurableFiles())
        val game = ChapterGameIdentity("2.139.5", 502, 1792159313454, 10075)
        val baseline = store.create(game, bundle, manifest)
        store.replace(baseline, baseline.copy(exportSha256 = "e".repeat(64)))
        val live = temporary.newFolder()
        val files = TestChapterInstallFiles(live).also { it.putOriginals(bundle, manifest) }
        val transport = TestInstallTransport(files, store, ::validateNativeInstallPair, nativeRecovery = true).also {
            it.game = ChapterGameStatus(game, true)
        }
        val session = ChapterInstallSession(store, transport)
        session.prepare(ChapterRebuildMode.INDEX_CONTROL); assertNull(session.state.value.error)
        session.install(true); assertNull(session.state.value.error)
        File(live, ChapterInstallPolicy.MANIFEST).writeBytes(gameIndex)
        val old = files.identity(ChapterInstallPolicy.MANIFEST)
        files.accessOverrides[ChapterInstallPolicy.MANIFEST] = old.copy(inode = old.inode + 1)
        session.inspect(); assertNull(session.state.value.error)
        assertTrue(session.state.value.needsInspection)
        session.confirmPlayback(); assertNotNull(session.state.value.error)
        session.restore(true); assertNull(session.state.value.error)
        assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
        assertFalse(requireNotNull(store.load()).unchangedVerified)
        assertFalse(requireNotNull(store.load()).playbackConfirmed)
        assertContentEquals(bundle, files.read(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(manifest, files.read(ChapterInstallPolicy.MANIFEST))
        session.prepare(ChapterRebuildMode.UNCHANGED); assertNull(session.state.value.error)
        session.install(true); assertNull(session.state.value.error)
        // A real rejection (official bundle back) still cannot confirm playback.
        File(live, ChapterInstallPolicy.BUNDLE).writeBytes(bundle)
        File(live, ChapterInstallPolicy.MANIFEST).writeBytes(gameIndex)
        files.accessOverrides[ChapterInstallPolicy.MANIFEST] = old.copy(inode = old.inode + 2)
        session.inspect(); assertNull(session.state.value.error)
        session.confirmPlayback(); assertNotNull(session.state.value.error)
        assertFalse(requireNotNull(store.load()).playbackConfirmed)
        session.restore(true); assertNull(session.state.value.error)
        assertFalse(requireNotNull(store.load()).unchangedVerified)
        assertFails { com.fgogotran.chapter.packinstall.RestoredStoryNativeGate(store).verified() }
    }
    @Test fun `authentic index-only control cannot confirm bundle playback or grant native gate`() = runBlocking<Unit> {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Local native fixtures are opt-in", directory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        val capture = LocalChapterPaths.capture(module, directory)
        val bundle = File(capture, ChapterInstallPolicy.BUNDLE).readBytes()
        val manifest = File(capture, ChapterInstallPolicy.MANIFEST).readBytes()
        val game = ChapterGameIdentity("2.139.5", 502, 1792159313454, 10075)
        val store = ChapterBackupStore(temporary.newFolder(), TestChapterDurableFiles())
        val baseline = store.create(game, bundle, manifest)
        store.replace(baseline, baseline.copy(exportSha256 = "e".repeat(64)))
        val files = TestChapterInstallFiles(temporary.newFolder()).also { it.putOriginals(bundle, manifest) }
        val transport = TestInstallTransport(files, store, ::validateNativeInstallPair).also { it.game = ChapterGameStatus(game, true) }
        val session = ChapterInstallSession(store, transport)
        session.prepare(ChapterRebuildMode.INDEX_CONTROL)
        assertNull(session.state.value.error)
        val prepared = requireNotNull(store.load())
        assertEquals(ChapterRebuildMode.INDEX_CONTROL, prepared.mode)
        assertEquals(prepared.originalBundle, prepared.replacementBundle)
        assertNotEquals(prepared.originalManifest, prepared.replacementManifest)
        assertEquals(prepared, ChapterInstallRecordCodec.decode(ChapterInstallRecordCodec.encode(prepared)))
        assertFails { prepared.copy(playbackConfirmed = true) }
        val codec = FgoJpFormatProfile.manifestCodec()
        assertEquals(codec.diagnosticLayers(OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), manifest)).valueOrThrow().decodedText,
            codec.diagnosticLayers(OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.MANIFEST), store.prepared(prepared, false))).valueOrThrow().decodedText)
        session.install(true)
        assertNull(session.state.value.error)
        assertEquals(ChapterInstallState.INSTALLED, store.load()?.state)
        session.confirmPlayback()
        assertNotNull(session.state.value.error)
        assertFalse(requireNotNull(store.load()).playbackConfirmed)
        session.restore(true)
        assertNull(session.state.value.error)
        assertEquals(ChapterInstallState.RESTORED, store.load()?.state)
        assertFalse(requireNotNull(store.load()).unchangedVerified)
        assertContentEquals(bundle, files.read(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(manifest, files.read(ChapterInstallPolicy.MANIFEST))
        assertFails { com.fgogotran.chapter.packinstall.RestoredStoryNativeGate(store).verified() }
    }
    @Test fun `authentic unchanged and marker pair install restore orchestration works offline`() = runBlocking<Unit> {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Local native fixtures are opt-in", directory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val capture = LocalChapterPaths.capture(module, directory)
        val bundle = File(capture, ChapterInstallPolicy.BUNDLE).readBytes()
        val manifest = File(capture, ChapterInstallPolicy.MANIFEST).readBytes()
        assertEquals(ChapterInstallPolicy.ORIGINAL_BUNDLE_SHA256, Sha256Digest.compute(bundle).hex)
        val captureRecord = Json.parseToJsonElement(File(capture, "capture.json").readText()).jsonObject
        val capturedGame = ChapterGameIdentity(captureRecord.getValue("version_name").jsonPrimitive.content,
            captureRecord.getValue("version_code").jsonPrimitive.content.toLong(), 123456789, 10075)
        ChapterInstallPolicy.requireOriginals(ChapterInstallRecord("0".repeat(32), capturedGame, InstallFileHash.of(bundle), InstallFileHash.of(manifest)))
        val store = ChapterBackupStore(temporary.newFolder(), TestChapterDurableFiles())
        val files = TestChapterInstallFiles(temporary.newFolder()).also { it.putOriginals(bundle, manifest) }
        val transport = TestInstallTransport(files, store, ::validateNativeInstallPair).also { it.game = ChapterGameStatus(capturedGame, true) }
        val session = ChapterInstallSession(store, transport)
        val source = object : ChapterOriginalStorage {
            override suspend fun capability() = ChapterStorageCapability.ReadOnly("local-fixture")
            override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult {
                val bytes = when (path.value) { ChapterInstallPolicy.BUNDLE -> bundle; ChapterInstallPolicy.MANIFEST -> manifest; else -> return OriginalAssetReadResult.NotFound }
                return OriginalAssetReadResult.Read(OriginalAsset.capture(path, bytes))
            }
        }
        session.backup(source); assertNull(session.state.value.error)
        val output = ByteArrayOutputStream(); session.export({ output }, { output.toByteArray().inputStream() })
        assertNull(session.state.value.error); assertNotNull(store.load()?.exportSha256)
        session.prepare(ChapterRebuildMode.MARKER); assertNotNull(session.state.value.error)
        session.prepare(ChapterRebuildMode.UNCHANGED); assertNull(session.state.value.error)
        val before = FgoJpFormatProfile.containerCodec().decode(OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), bundle)).valueOrThrow()
        session.install(true); assertNull(session.state.value.error); assertEquals(ChapterInstallState.INSTALLED, store.load()?.state)
        val after = FgoJpFormatProfile.containerCodec().decode(OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), files.read(ChapterInstallPolicy.BUNDLE))).valueOrThrow()
        assertEquals(before.scripts.map { it.binding to it.payload }, after.scripts.map { it.binding to it.payload })
        session.confirmPlayback() // Simulated operator observation; NOT actual game playback proof.
        session.restore(true); assertNull(session.state.value.error)
        assertTrue(requireNotNull(store.load()).unchangedVerified)
        assertContentEquals(bundle, files.read(ChapterInstallPolicy.BUNDLE)); assertContentEquals(manifest, files.read(ChapterInstallPolicy.MANIFEST))
        session.prepare(ChapterRebuildMode.MARKER); assertNull(session.state.value.error)
        val preview = requireNotNull(session.state.value.preview)
        assertEquals("dialogue:691", preview.fieldId); assertEquals("テスト表示", preview.replacement)
        session.install(true); assertNull(session.state.value.error)
        val marked = FgoJpFormatProfile.containerCodec().decode(OriginalAsset.capture(GameAssetPath.parse(ChapterInstallPolicy.BUNDLE), files.read(ChapterInstallPolicy.BUNDLE))).valueOrThrow()
        assertEquals(before.scripts.map { it.binding }, marked.scripts.map { it.binding })
        before.scripts.zip(marked.scripts).forEach { (a, b) -> if (a.binding.scriptId.value != ChapterInstallPolicy.SCRIPT) assertEquals(a.payload, b.payload) }
        val script = marked.scripts.single { it.binding.scriptId.value == ChapterInstallPolicy.SCRIPT }
        assertEquals("テスト表示", FgoJpFormatProfile.scriptCodec().decode(script).valueOrThrow().fields.single { it.id.value == "dialogue:691" }.sourceText)
        session.restore(true); assertNull(session.state.value.error)
        assertContentEquals(bundle, files.read(ChapterInstallPolicy.BUNDLE)); assertContentEquals(manifest, files.read(ChapterInstallPolicy.MANIFEST))
        // With the official index kept untouched, only the bundle is ever written;
        // an identical index is a verified no-op.
        val completed = requireNotNull(store.load())
        val preparedManifest = store.prepared(completed, false)
        val writes = if (preparedManifest.contentEquals(manifest)) 1 else 2
        for (write in 1..writes) {
            val local = TestChapterInstallFiles(temporary.newFolder()).also { it.putOriginals(bundle, manifest) }
            val transaction = ChapterTransactionEngine(local, { ChapterGameStatus(capturedGame, true) })
            val prepared = completed.copy(state = ChapterInstallState.PREPARED)
            transaction.stage(prepared, store.prepared(completed, true), store.prepared(completed, false))
            local.failDuringWrite = write; local.partialBytes = 7000
            assertFails { transaction.commit(prepared.copy(state = ChapterInstallState.COMMITTING)) }
            val recovery = prepared.copy(state = ChapterInstallState.RECOVERY_REQUIRED)
            assertEquals(ChapterPairState.INTERRUPTED, transaction.inspect(recovery))
            local.failDuringWrite = 0
            transaction.restore(recovery.copy(state = ChapterInstallState.RESTORING), bundle, manifest)
            assertContentEquals(bundle, local.read(ChapterInstallPolicy.BUNDLE))
            assertContentEquals(manifest, local.read(ChapterInstallPolicy.MANIFEST))
        }
        println("Native JVM install/restore and partial-write recovery passed: ${capturedGame.versionName}; manifest ${Sha256Digest.compute(manifest).hex}")
    }
}
