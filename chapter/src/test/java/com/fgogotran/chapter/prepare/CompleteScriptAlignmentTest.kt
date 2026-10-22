package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.compile.SyntheticStoryAssets
import kotlinx.serialization.json.*
import kotlin.test.*

class CompleteScriptAlignmentTest {
    private val fixture = SyntheticStoryAssets()
    private val aligner = CompleteScriptAlignment(fixture.scripts)
    private val id = "0100000011"
    private fun reference(region: String) = AtlasReference(region, id, "01", listOf(AtlasLink(100, 1000000, 1, 0)))
    private fun source(text: String = preparationJp) = fixture.source(fixture.asset(mapOf(id to fixture.encoded(text))))

    @Test fun `same parser aligns complete scripts and preserves JP branch player gender and ruby syntax`() {
        val source = source()
        val result = aligner.align(source, reference("JP"), preparationJp.toByteArray(), reference("TW"), localized(preparationJp).toByteArray())
        assertEquals(AlignmentReason.TRANSLATED, result.reason)
        val decoded = source.let { com.fgogotran.chapter.NativeScriptSource(it.binding, it.originalAsset, requireNotNull(result.replacement)) }
        assertEquals(localized(preparationJp), fixture.plain(decoded))
        assertEquals(fixture.scripts.decode(source).valueOrThrow().fields.size, result.fieldCount)
        assertEquals(1, fixture.scripts.decode(decoded).valueOrThrow().branches.size) // one choice marker, no inferred CFG
        assertTrue(fixture.plain(decoded).contains("[%1][&he:she]"))
        assertTrue(fixture.plain(decoded).contains("[#Observatory:Chaldea]"))
    }

    @Test fun `line-ending normalization never retargets the native UTF16 field offsets`() {
        val text = preparationJp.replace("\n", "\r\n")
        val source = source(text)
        val result = aligner.align(source, reference("JP"), preparationJp.toByteArray(), reference("NA"), localized(preparationJp).toByteArray())
        assertEquals(AlignmentReason.TRANSLATED, result.reason)
        val updated = com.fgogotran.chapter.NativeScriptSource(source.binding, source.originalAsset, requireNotNull(result.replacement))
        assertEquals(localized(text), fixture.plain(updated))
    }

    @Test fun `unchanged blank narrator and opaque fields remain byte exact without blocking safe other edits`() {
        val text = preparationJp + "＠\n[opaque effect]Same unchanged field\n[k]\n"
        val source = source(text)
        val result = aligner.align(source, reference("JP"), text.toByteArray(), reference("TW"), localized(text).toByteArray())
        assertEquals(AlignmentReason.TRANSLATED, result.reason)
        val updated = com.fgogotran.chapter.NativeScriptSource(source.binding, source.originalAsset, requireNotNull(result.replacement))
        assertEquals(localized(text), fixture.plain(updated))
    }

    @Test fun `equal filenames and field counts cannot bypass program quest or JP baseline differences`() {
        val source = source()
        fun align(target: String, jp: String = preparationJp, targetReference: AtlasReference = reference("CN")) =
            aligner.align(source, reference("JP"), jp.toByteArray(), targetReference, target.toByteArray())
        assertEquals(AlignmentReason.PROGRAM_DIFFERS, align(localized(preparationJp).replace("[bgm 1]", "[bgm 2]")).reason)
        assertEquals(AlignmentReason.PROGRAM_DIFFERS, align(localized(preparationJp).replace("？1：", "？2：")).reason)
        assertEquals(AlignmentReason.QUEST_PHASE_DIFFERS, align(localized(preparationJp), targetReference = reference("CN").copy(links = listOf(AtlasLink(100, 1000000, 2, 0)))).reason)
        assertEquals(AlignmentReason.JP_BASELINE_DIFFERS, align(localized(preparationJp), jp = preparationJp.replace("台詞", "別")).reason)
        assertEquals(AlignmentReason.FIELD_STRUCTURE_DIFFERS, align(localized(preparationJp).replace("Reply。", "")).reason)
    }

    @Test fun `explicit review binds complete bijection and raw hashes without replacing regional commands`() {
        val source = source()
        val target = localized(preparationJp).replace("[bgm 1]", "[bgm 99]")
        val before = fixture.scripts.parsePlain(preparationJp); val after = fixture.scripts.parsePlain(target)
        val mapping = ReviewedAlignment.read(PreparationFiles.canonical(buildJsonObject {
            put("type", "fgogotran-reviewed-complete-alignment"); put("schema_version", 1); put("script_id", id)
            put("native_decoded_sha256", PreparationFiles.hash(preparationJp.toByteArray())); put("jp_source_sha256", PreparationFiles.hash(preparationJp.toByteArray()))
            put("target_source_sha256", PreparationFiles.hash(target.toByteArray()))
            put("fields", JsonArray(before.fields.zip(after.fields).map { (a, b) -> buildJsonObject { put("jp_field", a.id.value); put("target_field", b.id.value) } }))
        }))
        val result = aligner.align(source, reference("JP"), preparationJp.toByteArray(), reference("NA"), target.toByteArray(), mapping)
        assertEquals(AlignmentReason.TRANSLATED, result.reason)
        val decoded = com.fgogotran.chapter.NativeScriptSource(source.binding, source.originalAsset, requireNotNull(result.replacement))
        assertEquals(localized(preparationJp), fixture.plain(decoded)) // original [bgm 1], not regional [bgm 99]
        assertFails { aligner.align(source, reference("JP"), preparationJp.toByteArray(), reference("NA"), (target + "\n").toByteArray(), mapping) }
        assertFails { aligner.align(source, reference("JP"), preparationJp.toByteArray(), reference("NA"), target.toByteArray(), mapping.copy(fields = mapping.fields.drop(1))) }
    }
}
