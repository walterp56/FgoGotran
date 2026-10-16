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
    @Test fun `authentic unchanged and marker pair install restore orchestration works offline`() = runBlocking<Unit> {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Local native fixtures are opt-in", directory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val fixtureRoot = File(module, "local-fixtures").canonicalFile
        val capture = File(directory).canonicalFile
        require(capture.toPath().startsWith(fixtureRoot.toPath()) && capture != fixtureRoot)
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
    }
}
