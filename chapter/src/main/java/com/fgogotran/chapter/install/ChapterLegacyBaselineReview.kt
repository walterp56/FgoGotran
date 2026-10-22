package com.fgogotran.chapter.install

import com.fgogotran.chapter.Sha256Digest
import kotlinx.serialization.json.*

/** Explicitly fingerprinted disposable-device reviews. Not a generic reset/recovery bypass. */
object ChapterLegacyBaselineReview {
    const val LEGACY_ID = "d54057b99524465a97e75b1ca93a869d"
    const val LEGACY_RECORD_SHA256 = "250875919ce3bafc5fc4a7f697bb17e840508fde041cce0e87d028e960c1f408"
    const val R1_ID = "34e931208a0743d58226415913b9e15d"
    const val R1_RECORD_SHA256 = "a9bb762a61f5f9487a34567869573102aaa0711f81e492e66ec73d6b11732495"
    private val game = ChapterGameIdentity("2.139.5", 502, 1792159313454, 10075)
    private data class Review(val id: String, val recordSha256: String, val manifestSha256: String)
    // Separate human-reviewed transitions: neither changes the old journal or asserts restoration.
    private val reviews = listOf(
        Review(LEGACY_ID, LEGACY_RECORD_SHA256, ChapterInstallPolicy.REVIEWED_MANIFEST_SHA256),
        Review(R1_ID, R1_RECORD_SHA256, ChapterInstallPolicy.R1_MANIFEST_SHA256)
    )
    private fun review(record: ChapterInstallRecord): Review? = reviews.singleOrNull {
        record.id == it.id && Sha256Digest.compute(ChapterInstallRecordCodec.encode(record).toByteArray(Charsets.UTF_8)).hex == it.recordSha256
    }

    fun eligible(record: ChapterInstallRecord): Boolean = review(record) != null

    internal fun requireReviewed(old: ChapterInstallRecord, raw: ByteArray, fresh: ChapterInstallRecord) {
        val reviewed = requireNotNull(review(old)) { "This failed test record has not been reviewed" }
        require(Sha256Digest.compute(raw).hex == reviewed.recordSha256) { "Reviewed raw journal changed" }
        require(old.game == game && fresh.game == game) { "Reviewed game identity changed" }
        require(fresh.originalBundle == InstallFileHash(ChapterInstallPolicy.ORIGINAL_BUNDLE_SHA256, 1772416) &&
            fresh.originalManifest == InstallFileHash(reviewed.manifestSha256, 741423)) { "Reviewed live baseline changed" }
        require(fresh == ChapterInstallRecord(fresh.id, game, fresh.originalBundle, fresh.originalManifest)) { "A reviewed baseline must start unverified" }
    }

    // A separate receipt says SUPERSEDED_AFTER_REVIEW, never RESTORED. Bind the full
    // new record as well as the old raw fingerprint so interrupted publication is resumable.
    internal fun receipt(old: ChapterInstallRecord, fresh: ChapterInstallRecord): ByteArray = buildJsonObject {
        val reviewed = requireNotNull(review(old))
        put("schema", 1); put("state", "SUPERSEDED_AFTER_REVIEW")
        put("oldId", reviewed.id); put("oldRecordSha256", reviewed.recordSha256)
        put("newId", fresh.id)
        put("newRecordSha256", Sha256Digest.compute(ChapterInstallRecordCodec.encode(fresh).toByteArray(Charsets.UTF_8)).hex)
    }.toString().toByteArray(Charsets.UTF_8)

    internal fun receiptTarget(bytes: ByteArray, old: ChapterInstallRecord): String {
        val reviewed = requireNotNull(review(old))
        require(bytes.size <= 2048)
        val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(obj.keys == setOf("schema", "state", "oldId", "oldRecordSha256", "newId", "newRecordSha256"))
        require(obj.getValue("schema").jsonPrimitive.int == 1 && obj.getValue("state").jsonPrimitive.content == "SUPERSEDED_AFTER_REVIEW")
        require(obj.getValue("oldId").jsonPrimitive.content == reviewed.id && obj.getValue("oldRecordSha256").jsonPrimitive.content == reviewed.recordSha256)
        return ChapterInstallPolicy.transactionId(obj.getValue("newId").jsonPrimitive.content)
    }
}
