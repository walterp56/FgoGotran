package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.install.*
import com.fgogotran.chapter.library.StoryPackLibrary
import com.fgogotran.chapter.packs.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Authentic bytes in PC-only temporary files, SIMULATED native gate/UID. Not a device approval. */
class LocalStoryPackTransactionTest {
    @Test fun `authentic source pack composes exports writes and restores only local captured copies`(): Unit = runBlocking {
        val property = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Authentic native copies are opt-in", property.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir"))).canonicalFile
        val capture = LocalChapterPaths.capture(module, property)
        val meta = Json.parseToJsonElement(File(capture, "capture.json").readText()).jsonObject
        val originals = meta.getValue("files").jsonArray.associate { item ->
            val obj = item.jsonObject; val path = GameAssetPath.parse(obj.getValue("path").jsonPrimitive.content)
            require('/' !in path.value)
            val file = File(capture, path.value).absoluteFile
            require(file.canonicalFile == file && file.length() <= ChapterInstallPolicy.MAX_FILE)
            val original = OriginalAsset.capture(path, file.readBytes())
            assertEquals(obj.getValue("sha256").jsonPrimitive.content, original.fingerprint.sha256.hex)
            assertEquals(obj.getValue("size_bytes").jsonPrimitive.long, original.fingerprint.sizeBytes)
            path.value to original
        }
        val bundle = originals.getValue(ChapterInstallPolicy.BUNDLE); val index = originals.getValue(ChapterInstallPolicy.MANIFEST)
        val output = File(module.parentFile, "story_assets/runs/language-pack-p4-fixture-${UUID.randomUUID()}").absoluteFile
        require(output.canonicalFile == output); check(output.mkdirs())
        val library = StoryPackLibrary(File(output, "library"))
        val archive = File(module.parentFile, "story_assets/published/packs/intro-tw-one-line/1.fgostory.zip")
        val entry = library.import({ archive.inputStream() })
        assertEquals("333306e6fdff50648ff36387f7e62f6b186ddb6faec592a1395c8a352f2d3afd", entry.sha256)
        val store = StoryPackOperationStore(File(output, "operation-store"), PackDurable())
        store.saveSelection(store.loadSelection(), entry.language, setOf(entry.sha256))
        val game = ChapterGameIdentity(meta.getValue("version_name").jsonPrimitive.content, meta.getValue("version_code").jsonPrimitive.content.toLong(), 123456789, 10075)
        val live = File(output, "simulated-game-files").also { check(it.mkdir()) }
        val files = TestChapterInstallFiles(live).also { it.putOriginals(bundle.bytes.toByteArray(), index.bytes.toByteArray()) }
        val engine = ChapterTransactionEngine(files, { ChapterGameStatus(game, true) })
        val transport = object : ChapterInstallTransport {
            override suspend fun environment() = ChapterGameStatus(game, true)
            override suspend fun inspect(record: ChapterInstallRecord) = engine.inspect(record)
            override suspend fun stage(record: ChapterInstallRecord) = engine.stage(record, store.bytes(record, true, true), store.bytes(record, false, true))
            override suspend fun commit(record: ChapterInstallRecord) = engine.commit(record)
            override suspend fun restore(record: ChapterInstallRecord) = engine.restore(record, store.bytes(record, true, false), store.bytes(record, false, false))
        }
        val reader = object : ChapterOriginalStorage {
            override suspend fun capability() = ChapterStorageCapability.ReadOnly("local-simulation")
            override suspend fun readOriginal(path: GameAssetPath) = OriginalAssetReadResult.Read(OriginalAsset.capture(path, files.read(path.value)))
        }
        // Intentionally injected only in this desktop test. Never creates a real restored-test receipt.
        val fakeGate = StoryNativeGate("d".repeat(32), "e".repeat(64), game, InstallFileHash.of(bundle.bytes.toByteArray()), InstallFileHash.of(index.bytes.toByteArray()))
        val session = StoryPackInstallSession(store, library, transport, StoryNativeGateSource { fakeGate })
        session.prepare(reader); assertNull(session.state.value.error)
        val prepared = assertNotNull(store.load())
        val recovery = ByteArrayOutputStream(); session.export({ recovery }, { recovery.toByteArray().inputStream() })
        assertNull(session.state.value.error); session.install(true); assertNull(session.state.value.error)
        assertEquals(ChapterInstallState.INSTALLED, store.load()!!.transport.state)
        assertEquals("7645fd923709d85ad999a5d8020783d20a5984b4b9c6dbcb79b675084d02fe07", InstallFileHash.of(files.read(ChapterInstallPolicy.BUNDLE)).sha256)
        val record = store.load()!!.transport
        FgoJpFormatProfile.manifestCodec().verifyAsset(OriginalAsset.capture(index.fingerprint.path, files.read(ChapterInstallPolicy.MANIFEST)),
            OriginalAsset.capture(bundle.fingerprint.path, files.read(ChapterInstallPolicy.BUNDLE))).let { assertIs<NativeCodecResult.Success<*>>(it) }
        session.restore(true); assertNull(session.state.value.error)
        assertContentEquals(bundle.bytes.toByteArray(), files.read(ChapterInstallPolicy.BUNDLE))
        assertContentEquals(index.bytes.toByteArray(), files.read(ChapterInstallPolicy.MANIFEST))
        assertEquals(ChapterInstallState.RESTORED, store.load()!!.transport.state)
        File(output, "RESULT.txt").writeText("P4 DESKTOP-ONLY transaction simulation; native gate/UID simulated. No phone/emulator/native playback.\n" +
            "archive_sha256=${entry.sha256}\ncomposition_sha256=${record.packCompositionSha256}\n" +
            "original_bundle=${record.originalBundle.sha256}\noriginal_manifest=${record.originalManifest.sha256}\n" +
            "prepared_bundle=${record.replacementBundle!!.sha256}\nprepared_manifest=${record.replacementManifest!!.sha256}\n" +
            "recovery_export_verified=true; final_state=RESTORED; exact_original_bytes=true\n" +
            "Actual device native gate remains UNPROVEN; never install this fixture journal.\n")
        println("P4 authentic-byte LOCAL simulation: $output")
        assertEquals(prepared.references, store.load()!!.references)
    }
}
