package com.fgogotran.chapter.install

import com.fgogotran.chapter.Sha256Digest
import kotlin.test.*

internal fun reviewedLegacyRecord() = ChapterInstallRecord(
    ChapterLegacyBaselineReview.LEGACY_ID, ChapterGameIdentity("2.139.5", 502, 1792159313454, 10075),
    InstallFileHash(ChapterInstallPolicy.ORIGINAL_BUNDLE_SHA256, 1772416),
    InstallFileHash(ChapterInstallPolicy.UPDATED_MANIFEST_SHA256, 741423),
    ChapterInstallState.RECOVERY_REQUIRED, ChapterRebuildMode.UNCHANGED,
    InstallFileHash("c75d084bc70dc5b037ffd48c6962ec051051c54385f24c409e5174306d0b66f7", 2391904),
    InstallFileHash("4196ebe01d020bbcc79ddd9abe6fb2b771843201b43bd38e19ce4470fdd91b73", 746883),
    "58bcc5a2efba63a75346f06cbcc4c4f74b3ed9a2ce337d8661e9fc22d9ccd065", writeProtocol = ChapterWriteProtocol.LEGACY_RENAME
)

internal fun reviewedR1Record() = ChapterInstallRecord(
    ChapterLegacyBaselineReview.R1_ID, ChapterGameIdentity("2.139.5", 502, 1792159313454, 10075),
    InstallFileHash(ChapterInstallPolicy.ORIGINAL_BUNDLE_SHA256, 1772416),
    InstallFileHash(ChapterInstallPolicy.REVIEWED_MANIFEST_SHA256, 741423),
    ChapterInstallState.INSTALLED, ChapterRebuildMode.UNCHANGED,
    InstallFileHash("c75d084bc70dc5b037ffd48c6962ec051051c54385f24c409e5174306d0b66f7", 2391904),
    InstallFileHash("4196ebe01d020bbcc79ddd9abe6fb2b771843201b43bd38e19ce4470fdd91b73", 746883),
    "f0a1165d7fea51a9cf8bd9ac6591f417d9636b993890eef8bed8a1217bdc15ac"
)

class ChapterLegacyBaselineReviewTest {
    @Test fun `only exact reviewed legacy fingerprint and new baseline are accepted`() {
        val old = reviewedLegacyRecord()
        val raw = ChapterInstallRecordCodec.encode(old).toByteArray(Charsets.UTF_8)
        assertEquals(ChapterLegacyBaselineReview.LEGACY_RECORD_SHA256, Sha256Digest.compute(raw).hex)
        val fresh = ChapterInstallRecord("a".repeat(32), old.game, old.originalBundle,
            InstallFileHash(ChapterInstallPolicy.REVIEWED_MANIFEST_SHA256, 741423))
        ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh)
        ChapterInstallPolicy.requireOriginals(fresh)
        listOf(old.copy(id = "b".repeat(32)), old.copy(state = ChapterInstallState.INSTALLED),
            old.copy(exportSha256 = null), old.copy(playbackConfirmed = true)).forEach {
            assertFalse(ChapterLegacyBaselineReview.eligible(it))
        }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw + 10.toByte(), fresh) }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh.copy(game = old.game.copy(updatedAt = 1))) }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh.copy(originalManifest = old.originalManifest)) }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh.copy(exportSha256 = "a".repeat(64))) }
        val receipt = ChapterLegacyBaselineReview.receipt(old, fresh)
        assertEquals(fresh.id, ChapterLegacyBaselineReview.receiptTarget(receipt, old))
        assertTrue(receipt.toString(Charsets.UTF_8).contains("SUPERSEDED_AFTER_REVIEW"))
        assertFails { ChapterLegacyBaselineReview.receiptTarget(receipt.toString(Charsets.UTF_8).replace("SUPERSEDED_AFTER_REVIEW", "RESTORED").toByteArray(), old) }
    }

    @Test fun `R1 review is independently bound and cannot reuse legacy approval`() {
        val old = reviewedR1Record()
        val raw = ChapterInstallRecordCodec.encode(old).toByteArray(Charsets.UTF_8)
        assertEquals(ChapterLegacyBaselineReview.R1_RECORD_SHA256, Sha256Digest.compute(raw).hex)
        val fresh = ChapterInstallRecord("c".repeat(32), old.game, old.originalBundle,
            InstallFileHash(ChapterInstallPolicy.R1_MANIFEST_SHA256, 741423))
        ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh)
        ChapterInstallPolicy.requireOriginals(fresh)
        assertEquals(ChapterInstallState.BACKED_UP, fresh.state)
        assertFalse(fresh.unchangedVerified); assertFalse(fresh.playbackConfirmed)
        assertNull(fresh.mode); assertNull(fresh.exportSha256)
        listOf(old.copy(state = ChapterInstallState.RECOVERY_REQUIRED), old.copy(id = "d".repeat(32)),
            old.copy(writeProtocol = ChapterWriteProtocol.LEGACY_RENAME), old.copy(unchangedVerified = true)).forEach {
            assertFalse(ChapterLegacyBaselineReview.eligible(it))
        }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw + 10.toByte(), fresh) }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh.copy(originalManifest = old.originalManifest)) }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh.copy(playbackConfirmed = false, unchangedVerified = true)) }
        assertFails { ChapterLegacyBaselineReview.requireReviewed(old, raw, fresh.copy(game = old.game.copy(uid = 10076))) }
        val receipt = ChapterLegacyBaselineReview.receipt(old, fresh)
        assertEquals(fresh.id, ChapterLegacyBaselineReview.receiptTarget(receipt, old))
        assertFails { ChapterLegacyBaselineReview.receiptTarget(receipt, reviewedLegacyRecord()) }
        assertFails { ChapterLegacyBaselineReview.receiptTarget(ChapterLegacyBaselineReview.receipt(reviewedLegacyRecord(), fresh), old) }
    }
}
