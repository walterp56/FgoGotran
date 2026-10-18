package com.fgogotran.chapter.install

import kotlin.test.*

class ChapterInstallModelsTest {
    @Test fun `each supported version requires its own exact original manifest`() {
        val old = ChapterInstallRecord("a".repeat(32), InstallSamples.game,
            InstallFileHash(ChapterInstallPolicy.ORIGINAL_BUNDLE_SHA256, 1772416),
            InstallFileHash(ChapterInstallPolicy.ORIGINAL_MANIFEST_SHA256, 741211))
        ChapterInstallPolicy.requireOriginals(old)
        val updated = old.copy(game = ChapterGameIdentity("2.139.5", 502, 223456789, 10075),
            originalManifest = InstallFileHash(ChapterInstallPolicy.UPDATED_MANIFEST_SHA256, 741423))
        ChapterInstallPolicy.requireOriginals(updated)
        assertFailsWith<IllegalArgumentException> { ChapterInstallPolicy.requireOriginals(old.copy(game = updated.game)) }
        assertFailsWith<IllegalArgumentException> { ChapterInstallPolicy.requireOriginals(updated.copy(game = old.game)) }
        assertEquals(old, ChapterInstallRecordCodec.decode(ChapterInstallRecordCodec.encode(old)))
        assertEquals(updated, ChapterInstallRecordCodec.decode(ChapterInstallRecordCodec.encode(updated)))
    }
    @Test fun `versioned record round trips without losing baseline or intent`() {
        val record = InstallSamples.record().copy(state = ChapterInstallState.COMMITTING)
        assertEquals(record, ChapterInstallRecordCodec.decode(ChapterInstallRecordCodec.encode(record)))
    }
    @Test fun `record rejects a different root package filename or schema`() {
        val json = ChapterInstallRecordCodec.encode(InstallSamples.record())
        listOf(json.replace("\"schema\":1", "\"schema\":2"), json.replace(ChapterInstallPolicy.ROOT_ID, "other-root"),
            json.replace(ChapterInstallPolicy.BUNDLE, "other.bin"), json.replace("com.aniplex.fategrandorder", "other.game")).forEach {
            assertFailsWith<IllegalArgumentException> { ChapterInstallRecordCodec.decode(it) }
        }
    }
    @Test fun `record rejects traversal oversized metadata and unsupported game versions`() {
        assertFailsWith<IllegalArgumentException> { InstallSamples.record().copy(id = "../bad") }
        assertFailsWith<IllegalArgumentException> { ChapterInstallRecordCodec.decode("x".repeat(ChapterInstallPolicy.MAX_RECORD + 1)) }
        assertFailsWith<IllegalArgumentException> { InstallSamples.game.copy(versionCode = 502) }
        assertFailsWith<IllegalArgumentException> { InstallSamples.game.copy(uid = 110075) }
    }
    @Test fun `write policy is much narrower than read-only hashed file policy`() {
        listOf("a".repeat(40) + ".bin", "../" + ChapterInstallPolicy.BUNDLE, "/sdcard/file", "test.sh", "ScriptActionEncrypt/Common").forEach {
            assertFailsWith<IllegalArgumentException> { ChapterInstallPolicy.filename(it) }
        }
        assertEquals(ChapterInstallPolicy.BUNDLE, ChapterInstallPolicy.filename(ChapterInstallPolicy.BUNDLE))
        val stage = ChapterInstallPolicy.stageName("a".repeat(32), "manifest")
        assertEquals(stage, ChapterInstallPolicy.filename(stage))
    }
    @Test fun `file hashes have bounded size and canonical digest`() {
        assertFailsWith<IllegalArgumentException> { InstallFileHash("a".repeat(64), 0) }
        assertFailsWith<IllegalArgumentException> { InstallFileHash("a".repeat(64), ChapterInstallPolicy.MAX_FILE + 1) }
        assertFailsWith<IllegalArgumentException> { InstallFileHash("A".repeat(64), 1) }
    }
    @Test fun `unknown and mixed pairs cannot be mistaken for complete installation`() {
        val record = InstallSamples.record()
        assertEquals(ChapterPairState.ORIGINAL, record.classify(record.originalBundle, record.originalManifest))
        assertEquals(ChapterPairState.REPLACEMENT, record.classify(record.replacementBundle, record.replacementManifest))
        assertEquals(ChapterPairState.MIXED, record.classify(record.replacementBundle, record.originalManifest))
        assertEquals(ChapterPairState.UNKNOWN, record.classify(null, record.originalManifest))
    }
}
