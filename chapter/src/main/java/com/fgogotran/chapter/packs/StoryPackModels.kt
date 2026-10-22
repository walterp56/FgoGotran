package com.fgogotran.chapter.packs

import com.fgogotran.chapter.Sha256Digest
import com.fgogotran.chapter.immutableList
import java.util.Collections

/** Data contracts only. Passing these checks never authorises an FGO write. */
object StoryPackPolicy {
    const val SCHEMA = 1
    const val GAME_PACKAGE = "com.aniplex.fategrandorder"
    const val CODEC_PROFILE = "fgo-jp-unityfs8-serialized22@1"
    const val MAX_ARCHIVE = 16 * 1024 * 1024
    const val MAX_EXPANDED = 32 * 1024 * 1024
    const val MAX_JSON = 1024 * 1024
    const val MAX_ENTRIES = 128
    const val MAX_SCRIPTS = 64
    const val MAX_CHANGES = 4096
    const val MAX_TOKENS = 128
    const val MAX_TEXT = 4096
    const val MAX_ASSET = 8 * 1024 * 1024

    fun id(value: String): String = value.also {
        require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid story package/chapter ID" }
    }
    fun scriptId(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Invalid script ID" }
    }
    fun digest(value: String): String = value.also {
        require(Sha256Digest.parse(it).hex == it) { "Use lowercase SHA-256" }
    }
    fun text(value: String, limit: Int = MAX_TEXT): String = value.also {
        require(it.isNotEmpty() && it.length <= limit && it.none(Char::isISOControl)) { "Invalid story text" }
        require(it.indices.none { index ->
            Character.isHighSurrogate(it[index]) && (index + 1 == it.length || !Character.isLowSurrogate(it[index + 1])) ||
                Character.isLowSurrogate(it[index]) && (index == 0 || !Character.isHighSurrogate(it[index - 1]))
        }) { "Invalid Unicode story text" }
    }
    fun entry(scriptId: String): String = "patches/${scriptId(scriptId)}.json"
    fun archivePath(packId: String, revision: Int): String {
        id(packId); require(revision > 0)
        return "packs/$packId/$revision.fgostory.zip"
    }
}

enum class StoryLanguage(val tag: String, val region: String) {
    SIMPLIFIED_CHINESE("zh-Hans", "CN"), TRADITIONAL_CHINESE("zh-Hant", "TW"), ENGLISH("en", "NA");
    companion object {
        fun parse(tag: String): StoryLanguage = entries.singleOrNull { it.tag == tag }
            ?: throw IllegalArgumentException("Unsupported story target language")
    }
}

enum class StoryCoverage(val wire: String) { SAMPLE("sample"), CHAPTER("chapter") }
enum class StoryFieldKind(val wire: String) { DIALOGUE("dialogue"), SPEAKER("speaker"), CHOICE("choice") }

data class StoryGameVersion(val name: String, val code: Long) {
    init { require(name.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")) && code > 0) }
}

/** No raw commands: later compilation must bind each token to the exact original field. */
sealed interface StoryTextToken {
    data class Text(val value: String) : StoryTextToken {
        init { StoryPackPolicy.text(value); require(value.none { it in "[]" }) { "Use typed text tokens, not native commands" } }
    }
    data class Ruby(val base: String, val reading: String) : StoryTextToken {
        init {
            listOf(base, reading).forEach { StoryPackPolicy.text(it); require(it.none { char -> char in "[]:" }) }
        }
    }
    data class Placeholder(val key: String) : StoryTextToken {
        init { require(key.matches(Regex("%[0-9]{1,4}|&[A-Za-z0-9_.:-]{1,64}"))) }
    }
    /** Ordered native alternatives; the game chooses the branch, not the compiler. */
    data class Conditional(val first: String, val second: String) : StoryTextToken {
        init {
            listOf(first, second).forEach { StoryPackPolicy.text(it); require(it.none { char -> char in "[]:" }) }
        }
    }
    /** JP/NA colour span open (`[51d4ff]`) or close (`[-]`). */
    data class ColorTag(val value: String) : StoryTextToken {
        init { require(value == "-" || value.matches(Regex("[0-9A-Fa-f]{6,8}"))) }
    }
    /** Explicit page/line command such as `[line 2]`. */
    data class LineCommand(val value: Int) : StoryTextToken {
        init { require(value in 1..99) }
    }
    /** NA soft page break: shows this part, then the next part shortly after. */
    data object SoftBreak : StoryTextToken
    data object LineBreak : StoryTextToken
}

class StoryTextChange(
    val fieldId: String,
    val kind: StoryFieldKind,
    val expectedTextSha256: String,
    tokens: Collection<StoryTextToken>
) {
    val tokens = immutableList(tokens)
    init {
        require(fieldId.matches(Regex("${kind.wire}:[0-9]{1,9}"))) { "Field kind/identity mismatch" }
        StoryPackPolicy.digest(expectedTextSha256)
        require(this.tokens.size in 1..StoryPackPolicy.MAX_TOKENS)
        require(this.tokens.any { it is StoryTextToken.Text || it is StoryTextToken.Ruby || it is StoryTextToken.Placeholder || it is StoryTextToken.Conditional })
        require(this.tokens.sumOf { when (it) {
            is StoryTextToken.Text -> it.value.length
            is StoryTextToken.Ruby -> it.base.length + it.reading.length
            is StoryTextToken.Placeholder -> it.key.length
            is StoryTextToken.Conditional -> it.first.length + it.second.length
            is StoryTextToken.ColorTag -> 0
            is StoryTextToken.LineCommand -> 0
            StoryTextToken.SoftBreak -> 1
            StoryTextToken.LineBreak -> 1
        } } <= StoryPackPolicy.MAX_TEXT) { "Text change exceeds limit" }
    }
}

class StoryScriptPatch(val scriptId: String, changes: Collection<StoryTextChange>) {
    val changes = immutableList(changes)
    init {
        StoryPackPolicy.scriptId(scriptId)
        require(this.changes.size in 1..StoryPackPolicy.MAX_CHANGES)
        require(this.changes.map { it.fieldId }.distinct().size == this.changes.size) { "Duplicate field replacement" }
    }
}

data class StoryScriptDescriptor(
    val scriptId: String,
    val logicalBundle: String,
    val assetFilename: String,
    val originalAssetSha256: String,
    val originalAssetSize: Int,
    val originalPayloadSha256: String,
    val originalDecodedSha256: String,
    val patchSha256: String,
    val patchSize: Int
) {
    val path: String get() = StoryPackPolicy.entry(scriptId)
    init {
        StoryPackPolicy.scriptId(scriptId)
        require(logicalBundle.matches(Regex("ScriptActionEncrypt/[A-Za-z0-9_]{1,64}")) &&
            logicalBundle !in listOf("ScriptActionEncrypt/ScriptFileList", "ScriptActionEncrypt/Common"))
        require(assetFilename.matches(Regex("[0-9a-f]{40}\\.bin")))
        listOf(originalAssetSha256, originalPayloadSha256, originalDecodedSha256, patchSha256).forEach(StoryPackPolicy::digest)
        require(originalAssetSize in 1..StoryPackPolicy.MAX_ASSET && patchSize in 1..StoryPackPolicy.MAX_JSON)
    }
}

data class StorySource(val region: String, val scriptId: String, val sha256: String, val size: Int, val url: String) {
    init {
        require(region in listOf("JP", "CN", "TW", "NA")); StoryPackPolicy.scriptId(scriptId); StoryPackPolicy.digest(sha256)
        require(size in 1..4 * 1024 * 1024)
        require(url == "https://static.atlasacademy.io/$region/Script/01/$scriptId.txt") { "Unreviewed source URL" }
    }
}

class StoryPackManifest(
    val packId: String,
    val revision: Int,
    val chapterId: String,
    titles: Map<String, String>,
    val language: StoryLanguage,
    val coverage: StoryCoverage,
    questIds: Collection<Long>,
    gameVersions: Collection<StoryGameVersion>,
    scripts: Collection<StoryScriptDescriptor>,
    sources: Collection<StorySource>
) {
    val titles: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(titles))
    val questIds = immutableList(questIds)
    val gameVersions = immutableList(gameVersions)
    val scripts = immutableList(scripts)
    val sources = immutableList(sources)
    init {
        StoryPackPolicy.id(packId); StoryPackPolicy.id(chapterId); require(revision > 0)
        require(this.titles.isNotEmpty() && this.titles.keys.all { it in listOf("en", "zh-Hans", "zh-Hant") })
        this.titles.values.forEach { require(StoryPackPolicy.text(it, 160).isNotBlank()) }
        require(this.questIds.size in 1..128 && this.questIds.all { it > 0 } && this.questIds.distinct().size == this.questIds.size)
        require(this.gameVersions.size in 1..16 && this.gameVersions.distinct().size == this.gameVersions.size)
        require(this.scripts.size in 1..StoryPackPolicy.MAX_SCRIPTS && this.scripts.map { it.scriptId }.distinct().size == this.scripts.size)
        require(this.sources.size in 2..2 * StoryPackPolicy.MAX_SCRIPTS)
        require(this.sources.map { it.region to it.scriptId }.distinct().size == this.sources.size)
        require(this.sources.map { it.scriptId }.toSet() == this.scripts.map { it.scriptId }.toSet())
        this.scripts.forEach { script ->
            require(this.sources.filter { it.scriptId == script.scriptId }.map { it.region }.toSet() == setOf("JP", language.region)) {
                "Every script requires JP and matching target-region provenance"
            }
        }
        this.scripts.groupBy { it.logicalBundle }.values.forEach { group ->
            require(group.map { Triple(it.assetFilename, it.originalAssetSha256, it.originalAssetSize) }.distinct().size == 1)
        }
        require(this.scripts.groupBy { it.assetFilename }.values.all { group -> group.map { it.logicalBundle }.distinct().size == 1 })
    }
}

/** Verified source package, NOT a compiled/installed/playback-approved language pack. */
class ValidatedStoryPack internal constructor(
    val manifest: StoryPackManifest, patches: Collection<StoryScriptPatch>, val archiveSha256: String, val archiveSize: Int
) {
    val patches = immutableList(patches)
    init { StoryPackPolicy.digest(archiveSha256); require(archiveSize in 1..StoryPackPolicy.MAX_ARCHIVE) }
}

data class StoryCatalogueEntry(
    val packId: String, val revision: Int, val chapterId: String, val language: StoryLanguage,
    val coverage: StoryCoverage, val summary: StoryCatalogueSummary, val sha256: String, val size: Int
) {
    val path: String get() = StoryPackPolicy.archivePath(packId, revision)
    init {
        StoryPackPolicy.id(packId); StoryPackPolicy.id(chapterId); require(revision > 0)
        StoryPackPolicy.digest(sha256); require(size in 1..StoryPackPolicy.MAX_ARCHIVE)
    }
    fun matches(manifest: StoryPackManifest): Boolean = packId == manifest.packId && revision == manifest.revision &&
        chapterId == manifest.chapterId && language == manifest.language && coverage == manifest.coverage &&
        summary.titles == manifest.titles && summary.gameVersions.toSet() == manifest.gameVersions.toSet() && summary.scriptCount == manifest.scripts.size
}

/** Catalogue labels and compatibility hints can be displayed without downloading every pack. */
class StoryCatalogueSummary(titles: Map<String, String>, gameVersions: Collection<StoryGameVersion>, val scriptCount: Int, val changeCount: Int) {
    val titles: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(titles))
    val gameVersions = immutableList(gameVersions)
    init {
        require(this.titles.isNotEmpty() && this.titles.keys.all { it in listOf("en", "zh-Hans", "zh-Hant") })
        this.titles.values.forEach { require(StoryPackPolicy.text(it, 160).isNotBlank()) }
        require(this.gameVersions.size in 1..16 && this.gameVersions.distinct().size == this.gameVersions.size)
        require(scriptCount in 1..StoryPackPolicy.MAX_SCRIPTS && changeCount in scriptCount..scriptCount * StoryPackPolicy.MAX_CHANGES)
    }
    override fun equals(other: Any?): Boolean = other is StoryCatalogueSummary && titles == other.titles &&
        gameVersions.toSet() == other.gameVersions.toSet() && scriptCount == other.scriptCount && changeCount == other.changeCount
    override fun hashCode(): Int = listOf(titles, gameVersions.toSet(), scriptCount, changeCount).hashCode()
}

class StoryCatalogue(entries: Collection<StoryCatalogueEntry>) {
    val entries = immutableList(entries)
    init {
        require(this.entries.size <= 2048)
        require(this.entries.map { it.packId to it.revision }.distinct().size == this.entries.size)
        require(this.entries.map { Triple(it.chapterId, it.language, it.revision) }.distinct().size == this.entries.size)
        this.entries.groupBy { it.packId }.values.forEach { group ->
            require(group.map { it.chapterId to it.language }.distinct().size == 1) { "Package identity changed between revisions" }
        }
        require(this.entries.groupBy { it.chapterId to it.language }.values.all { group -> group.map { it.packId }.distinct().size == 1 })
    }
    fun latest(): List<StoryCatalogueEntry> = entries.groupBy { it.chapterId to it.language }.values
        .map { versions -> versions.maxBy { it.revision } }.sortedWith(compareBy({ it.chapterId }, { it.language.tag }))
}
