package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.Sha256Digest
import com.fgogotran.chapter.install.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackPurposeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `old write bindings remain byte-for-byte frozen for both directions and protocols`() {
        fun frozen(record: ChapterInstallRecord, role: String, restore: Boolean): String {
            fun InstallFileHash?.binding() = this?.let { "${it.sha256}:${it.size}" } ?: "NONE"
            return Sha256Digest.compute(buildJsonObject {
                put("binding_schema", 1); put("job", record.id); put("protocol", record.writeProtocol.name)
                put("root", ChapterInstallPolicy.ROOT_ID); put("role", role); put("restore", restore)
                put("version", record.game.versionName); put("code", record.game.versionCode)
                put("updated", record.game.updatedAt); put("uid", record.game.uid); put("mode", record.mode?.name ?: "ORIGINAL")
                put("original_bundle", record.originalBundle.binding()); put("original_manifest", record.originalManifest.binding())
                put("replacement_bundle", record.replacementBundle.binding()); put("replacement_manifest", record.replacementManifest.binding())
            }.toString().toByteArray()).hex
        }
        for (protocol in ChapterWriteProtocol.entries) for (restore in listOf(false, true)) for (role in listOf("bundle", "manifest")) {
            val record = InstallSamples.record().copy(writeProtocol = protocol)
            assertEquals(frozen(record, role, restore), writePlan(record, role, restore))
        }
    }

    @Test fun `pack-purpose schema cannot masquerade as a legacy asset-test record`(): Unit = runBlocking {
        val f = PackInstallFixture(temporary.newFolder()); f.desired(f.import()); f.session().prepare(f.reader)
        val record = f.store.load()!!.transport
        val text = ChapterInstallRecordCodec.encode(record)
        assertEquals(record, ChapterInstallRecordCodec.decode(text))
        assertTrue(text.contains("\"schema\":3")); assertTrue(text.contains("\"script\":null"))
        for (invalid in listOf(text.replace("\"schema\":3", "\"schema\":2"), text.replace("story-language-pack", "asset-test"),
            text.replace("\"script\":null", "\"script\":\"0100000011\""), text.replace("\"schema\":3", "\"schema\":3,\"schema\":3"))) {
            assertFails { ChapterInstallRecordCodec.decode(invalid) }
        }
        assertNotEquals(writePlan(record, "bundle", false), writePlan(record.copy(packCompositionSha256 = "f".repeat(64)), "bundle", false))
        assertFails { record.copy(writeProtocol = ChapterWriteProtocol.LEGACY_RENAME) }
    }

    @Test fun `native gate rejects missing unverified legacy or unreviewed test receipts`() {
        val root = temporary.newFolder(); val store = ChapterBackupStore(root, TestChapterDurableFiles())
        val source = RestoredStoryNativeGate(store)
        assertFails { source.verified() }
        val backedUp = store.create(InstallSamples.game, InstallSamples.bundle, InstallSamples.manifest)
        var record = store.savePrepared(backedUp, ChapterRebuildMode.UNCHANGED, InstallSamples.replacementBundle, InstallSamples.replacementManifest)
        assertFails { source.verified() }
        record = store.replace(record, record.copy(state = ChapterInstallState.RESTORED, playbackConfirmed = true, unchangedVerified = true, exportSha256 = "e".repeat(64)))
        assertFails { source.verified() } // Synthetic bytes are not one of the reviewed physical snapshots.
        val encoded = ChapterInstallRecordCodec.encode(record).replace("\"schema\":2", "\"schema\":1").replace(",\"writeProtocol\":\"IN_PLACE_V1\"", "")
        File(root, "${record.id}/record.json").writeText(encoded)
        assertFails { source.verified() }
        assertEquals(ChapterWriteProtocol.LEGACY_RENAME, store.load()!!.writeProtocol)
    }
}
