package com.fgogotran.chapter.packs

import com.fgogotran.chapter.Sha256Digest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object StoryPackTestData {
    const val SCRIPT = "0100000011"
    val digest = "0".repeat(64)
    fun patch(text: String = "Synthetic translated text") = StoryScriptPatch(SCRIPT, listOf(
        StoryTextChange("dialogue:10", StoryFieldKind.DIALOGUE, digest, listOf(StoryTextToken.Text(text)))
    ))
    fun manifest(patch: StoryScriptPatch = patch(), revision: Int = 1, language: StoryLanguage = StoryLanguage.TRADITIONAL_CHINESE,
        packId: String = "intro-sample-zh-hant", chapterId: String = "intro-sample"): StoryPackManifest {
        val bytes = StoryPackJson.encodePatch(patch)
        return StoryPackManifest(packId, revision, chapterId, mapOf("en" to "Synthetic opening sample"), language, StoryCoverage.SAMPLE,
            listOf(1000000), listOf(StoryGameVersion("2.139.5", 502)), listOf(
                StoryScriptDescriptor(SCRIPT, "ScriptActionEncrypt/01", "a".repeat(40) + ".bin", digest, 100,
                    digest, digest, Sha256Digest.compute(bytes).hex, bytes.size)
            ), listOf("JP", language.region).map { StorySource(it, SCRIPT, digest, 100, "https://static.atlasacademy.io/$it/Script/01/$SCRIPT.txt") })
    }
    fun members(manifest: StoryPackManifest = manifest(), patch: StoryScriptPatch = patch()) = linkedMapOf(
        "manifest.json" to StoryPackJson.encodeManifest(manifest), StoryPackPolicy.entry(patch.scriptId) to StoryPackJson.encodePatch(patch)
    )
    fun archive(file: File, members: Map<String, ByteArray> = members()): File {
        ZipOutputStream(file.outputStream()).use { zip -> members.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 315532800000L }); zip.write(bytes); zip.closeEntry()
        } }
        return file
    }
    fun draft(repository: File, name: String = "draft", manifest: StoryPackManifest = manifest(), patch: StoryScriptPatch = patch()): File {
        File(repository, "chapter").mkdirs()
        return File(repository, "story_assets/mappings/$name").apply {
            mkdirs(); members(manifest, patch).forEach { (name, bytes) -> File(this, name).apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) } }
        }
    }
    fun catalogue(file: File, manifest: StoryPackManifest = manifest()): StoryCatalogueEntry {
        val (hash, size) = StoryPackArchive.fingerprint(file)
        return StoryCatalogueEntry(manifest.packId, manifest.revision, manifest.chapterId, manifest.language, manifest.coverage,
            StoryCatalogueSummary(manifest.titles, manifest.gameVersions, manifest.scripts.size, 1), hash, size)
    }
}
