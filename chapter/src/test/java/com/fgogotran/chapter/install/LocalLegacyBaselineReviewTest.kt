package com.fgogotran.chapter.install

import com.fgogotran.chapter.LocalChapterPaths
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.*

/** Authentic captures; JVM temporary files only. No device writes or playback claim. */
class LocalLegacyBaselineReviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `review retains legacy originals and resumes interrupted private publication`() {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        assumeTrue("Local fixtures are opt-in", directory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        val capture = LocalChapterPaths.capture(module, directory)
        val oldExport = File(module.parentFile, "story_assets/runs/native-v1a-20261019/legacy-export")
        assumeTrue("Independent old export required", oldExport.isDirectory)
        val old = reviewedLegacyRecord()
        val raw = ChapterInstallRecordCodec.encode(old).toByteArray(Charsets.UTF_8)
        val bundle = File(capture, ChapterInstallPolicy.BUNDLE).readBytes()
        val manifest = File(capture, ChapterInstallPolicy.MANIFEST).readBytes()
        val root = temporary.newFolder()
        val folder = File(root, old.id).also { check(it.mkdir()) }
        File(folder, "record.json").writeBytes(raw)
        File(folder, "original-bundle").writeBytes(File(oldExport, ChapterInstallPolicy.BUNDLE).readBytes())
        File(folder, "original-manifest").writeBytes(File(oldExport, ChapterInstallPolicy.MANIFEST).readBytes())
        File(root, "active").writeText(old.id)
        val delegate = TestChapterDurableFiles()
        var failActive = false
        val durable = object : ChapterDurableFiles by delegate {
            override fun write(file: File, bytes: ByteArray) {
                if (failActive && file.name == "active") throw IOException("interrupted active publication")
                delegate.write(file, bytes)
            }
        }
        val store = ChapterBackupStore(root, durable)
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest, false) }
        assertFails { store.reviewLegacyBaseline(old, old.game.copy(uid = 10076), bundle, manifest, true) }
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest + 0.toByte(), true) }
        assertEquals(old, store.load())
        failActive = true
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest, true) }
        assertEquals(old, store.load())
        val receipts = root.listFiles()!!.filter { it.name.startsWith("reviewed-legacy-") }
        assertEquals(1, receipts.size)
        val target = ChapterLegacyBaselineReview.receiptTarget(receipts.single().readBytes(), old)
        val foldersBeforeRetry = root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
        failActive = false
        val fresh = store.reviewLegacyBaseline(old, old.game, bundle, manifest, true)
        assertEquals(target, fresh.id)
        assertEquals(foldersBeforeRetry, root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet())
        assertEquals(fresh, store.load())
        assertEquals(ChapterInstallState.BACKED_UP, fresh.state)
        assertFalse(fresh.playbackConfirmed); assertFalse(fresh.unchangedVerified)
        assertNull(fresh.mode); assertNull(fresh.exportSha256)
        assertContentEquals(raw, File(folder, "record.json").readBytes())
        assertContentEquals(File(oldExport, ChapterInstallPolicy.MANIFEST).readBytes(), store.original(old, false))
        assertContentEquals(manifest, store.original(fresh, false))
        assertFailsWith<IllegalStateException> { com.fgogotran.chapter.packinstall.RestoredStoryNativeGate(store).verified() }
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest, true) }
    }

    @Test fun `R1 transition retains failed in-place journal and old originals without granting native gate`() {
        val directory = System.getProperty("chapter.localCapture", "").orEmpty()
        val currentDirectory = System.getenv("CHAPTER_COMPARE_CAPTURE").orEmpty()
        assumeTrue("Both independently captured baselines are opt-in", directory.isNotEmpty() && currentDirectory.isNotEmpty())
        val module = File(requireNotNull(System.getProperty("chapter.projectDir")))
        val originalCapture = LocalChapterPaths.capture(module, directory)
        val currentCapture = LocalChapterPaths.capture(module, currentDirectory)
        val old = reviewedR1Record()
        val raw = ChapterInstallRecordCodec.encode(old).toByteArray(Charsets.UTF_8)
        val bundle = File(currentCapture, ChapterInstallPolicy.BUNDLE).readBytes()
        val manifest = File(currentCapture, ChapterInstallPolicy.MANIFEST).readBytes()
        val root = temporary.newFolder()
        val folder = File(root, old.id).also { check(it.mkdir()) }
        File(folder, "record.json").writeBytes(raw)
        val oldBundle = File(originalCapture, ChapterInstallPolicy.BUNDLE).readBytes()
        val oldManifest = File(originalCapture, ChapterInstallPolicy.MANIFEST).readBytes()
        File(folder, "original-bundle").writeBytes(oldBundle)
        File(folder, "original-manifest").writeBytes(oldManifest)
        File(root, "active").writeText(old.id)
        val delegate = TestChapterDurableFiles()
        var interruptPublication = true
        val store = ChapterBackupStore(root, object : ChapterDurableFiles by delegate {
            override fun write(file: File, bytes: ByteArray) {
                if (interruptPublication && file.name == "active") throw IOException("interrupted R1 publication")
                delegate.write(file, bytes)
            }
        })
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest, false) }
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest, true) }
        assertEquals(old, store.load())
        val receipt = File(root, "reviewed-legacy-${old.id}.json").readBytes()
        val target = ChapterLegacyBaselineReview.receiptTarget(receipt, old)
        val folders = root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
        interruptPublication = false
        val fresh = store.reviewLegacyBaseline(old, old.game, bundle, manifest, true)
        assertEquals(target, fresh.id)
        assertEquals(folders, root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet())
        assertEquals(fresh, store.load())
        assertContentEquals(raw, File(folder, "record.json").readBytes())
        assertContentEquals(oldBundle, store.original(old, true))
        assertContentEquals(oldManifest, store.original(old, false))
        assertContentEquals(manifest, store.original(fresh, false))
        assertFalse(fresh.playbackConfirmed); assertFalse(fresh.unchangedVerified)
        assertNull(fresh.mode); assertNull(fresh.exportSha256)
        assertFails { com.fgogotran.chapter.packinstall.RestoredStoryNativeGate(store).verified() }
        assertFails { store.reviewLegacyBaseline(old, old.game, bundle, manifest, true) }
    }
}
