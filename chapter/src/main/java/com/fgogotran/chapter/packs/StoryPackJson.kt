package com.fgogotran.chapter.packs

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict, bounded metadata; unknown fields/features and duplicate JSON keys fail closed. */
object StoryPackJson {
    internal fun metadata(bytes: ByteArray, maximum: Int): JsonObject {
        require(bytes.size in 1..maximum)
        return parse(bytes)
    }
    private fun bytes(value: JsonElement) = value.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size <= StoryPackPolicy.MAX_JSON) { "Story metadata exceeds limit" }
    }
    private fun parse(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..StoryPackPolicy.MAX_JSON)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!text.startsWith('\uFEFF')) { "Story JSON must be UTF-8 without BOM" }
        UniqueJsonKeys(text).check()
        return Json.parseToJsonElement(text).jsonObject
    }
    private fun JsonObject.keysExactly(vararg names: String): JsonObject = also {
        require(keys == names.toSet()) { "Missing or unknown story metadata fields" }
    }
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonObject.number(name: String): Long = getValue(name).jsonPrimitive.let { require(!it.isString); it.long }
    private fun JsonObject.integer(name: String): Int = number(name).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
    private fun <T> enumWire(value: String, entries: List<T>, wire: (T) -> String): T =
        entries.singleOrNull { wire(it) == value } ?: throw IllegalArgumentException("Unsupported story metadata value: $value")

    fun encodeManifest(value: StoryPackManifest): ByteArray = bytes(buildJsonObject {
        put("schema_version", StoryPackPolicy.SCHEMA); put("type", "fgogotran-story-source-pack")
        put("pack_id", value.packId); put("revision", value.revision); put("chapter_id", value.chapterId)
        put("titles", buildJsonObject { value.titles.toSortedMap().forEach { (tag, title) -> put(tag, title) } })
        put("target_language", value.language.tag); put("coverage", value.coverage.wire)
        put("quest_ids", JsonArray(value.questIds.sorted().map(::JsonPrimitive)))
        put("game_package", StoryPackPolicy.GAME_PACKAGE); put("codec_profile", StoryPackPolicy.CODEC_PROFILE)
        put("game_versions", buildJsonArray { value.gameVersions.sortedBy { it.code }.forEach { version -> add(buildJsonObject {
            put("name", version.name); put("code", version.code)
        }) } })
        put("scripts", buildJsonArray { value.scripts.sortedBy { it.scriptId }.forEach { script -> add(buildJsonObject {
            put("script_id", script.scriptId); put("logical_bundle", script.logicalBundle); put("asset_filename", script.assetFilename)
            put("original_asset_sha256", script.originalAssetSha256); put("original_asset_size", script.originalAssetSize)
            put("original_payload_sha256", script.originalPayloadSha256); put("original_decoded_sha256", script.originalDecodedSha256)
            put("path", script.path); put("sha256", script.patchSha256); put("size", script.patchSize)
        }) } })
        put("sources", buildJsonArray { value.sources.sortedWith(compareBy({ it.scriptId }, { it.region })).forEach { source -> add(buildJsonObject {
            put("region", source.region); put("script_id", source.scriptId); put("sha256", source.sha256)
            put("size", source.size); put("url", source.url)
        }) } })
    })

    fun decodeManifest(bytes: ByteArray): StoryPackManifest {
        val obj = parse(bytes).keysExactly("schema_version", "type", "pack_id", "revision", "chapter_id", "titles",
            "target_language", "coverage", "quest_ids", "game_package", "codec_profile", "game_versions", "scripts", "sources")
        require(obj.integer("schema_version") == StoryPackPolicy.SCHEMA && obj.string("type") == "fgogotran-story-source-pack")
        require(obj.string("game_package") == StoryPackPolicy.GAME_PACKAGE && obj.string("codec_profile") == StoryPackPolicy.CODEC_PROFILE)
        val titles = obj.getValue("titles").jsonObject.mapValues { (_, item) -> item.jsonPrimitive.let { require(it.isString); it.content } }
        return StoryPackManifest(obj.string("pack_id"), obj.integer("revision"), obj.string("chapter_id"), titles,
            StoryLanguage.parse(obj.string("target_language")), enumWire(obj.string("coverage"), StoryCoverage.entries, StoryCoverage::wire),
            obj.array("quest_ids").map { it.jsonPrimitive.let { number -> require(!number.isString); number.long } },
            obj.array("game_versions").map { it.jsonObject.keysExactly("name", "code").let { version -> StoryGameVersion(version.string("name"), version.number("code")) } },
            obj.array("scripts").map { element ->
                val script = element.jsonObject.keysExactly("script_id", "logical_bundle", "asset_filename", "original_asset_sha256", "original_asset_size",
                    "original_payload_sha256", "original_decoded_sha256", "path", "sha256", "size")
                StoryScriptDescriptor(script.string("script_id"), script.string("logical_bundle"), script.string("asset_filename"),
                    script.string("original_asset_sha256"), script.integer("original_asset_size"), script.string("original_payload_sha256"),
                    script.string("original_decoded_sha256"), script.string("sha256"), script.integer("size")).also {
                    require(script.string("path") == it.path) { "Script entry path differs from its identity" }
                }
            }, obj.array("sources").map { it.jsonObject.keysExactly("region", "script_id", "sha256", "size", "url").let { source ->
                StorySource(source.string("region"), source.string("script_id"), source.string("sha256"), source.integer("size"), source.string("url"))
            } })
    }

    fun encodePatch(value: StoryScriptPatch): ByteArray = bytes(buildJsonObject {
        put("schema_version", StoryPackPolicy.SCHEMA); put("script_id", value.scriptId)
        put("changes", buildJsonArray { value.changes.sortedBy { it.fieldId }.forEach { change -> add(buildJsonObject {
            put("field_id", change.fieldId); put("kind", change.kind.wire); put("expected_text_sha256", change.expectedTextSha256)
            put("tokens", buildJsonArray { change.tokens.forEach { token -> add(buildJsonObject { when (token) {
                is StoryTextToken.Text -> { put("type", "text"); put("value", token.value) }
                is StoryTextToken.Ruby -> { put("type", "ruby"); put("base", token.base); put("reading", token.reading) }
                is StoryTextToken.Placeholder -> { put("type", "placeholder"); put("key", token.key) }
                is StoryTextToken.Conditional -> { put("type", "conditional"); put("first", token.first); put("second", token.second) }
                is StoryTextToken.ColorTag -> { put("type", "color"); put("value", token.value) }
                is StoryTextToken.LineCommand -> { put("type", "line"); put("value", token.value) }
                StoryTextToken.SoftBreak -> put("type", "soft_break")
                StoryTextToken.LineBreak -> put("type", "line_break")
            } }) } })
        }) } })
    })

    fun decodePatch(bytes: ByteArray): StoryScriptPatch {
        val obj = parse(bytes).keysExactly("schema_version", "script_id", "changes")
        require(obj.integer("schema_version") == StoryPackPolicy.SCHEMA)
        val changes = obj.array("changes").map { item ->
            val change = item.jsonObject.keysExactly("field_id", "kind", "expected_text_sha256", "tokens")
            StoryTextChange(change.string("field_id"), enumWire(change.string("kind"), StoryFieldKind.entries, StoryFieldKind::wire),
                change.string("expected_text_sha256"), change.array("tokens").map { value ->
                    val token = value.jsonObject
                    when (token.string("type")) {
                        "text" -> token.keysExactly("type", "value").let { StoryTextToken.Text(it.string("value")) }
                        "ruby" -> token.keysExactly("type", "base", "reading").let { StoryTextToken.Ruby(it.string("base"), it.string("reading")) }
                        "placeholder" -> token.keysExactly("type", "key").let { StoryTextToken.Placeholder(it.string("key")) }
                        "conditional" -> token.keysExactly("type", "first", "second").let { StoryTextToken.Conditional(it.string("first"), it.string("second")) }
                        "color" -> token.keysExactly("type", "value").let { StoryTextToken.ColorTag(it.string("value")) }
                        "line" -> token.keysExactly("type", "value").let { StoryTextToken.LineCommand(it.integer("value")) }
                        "soft_break" -> token.keysExactly("type").let { StoryTextToken.SoftBreak }
                        "line_break" -> token.keysExactly("type").let { StoryTextToken.LineBreak }
                        else -> error("Unsupported story text token")
                    }
                })
        }
        return StoryScriptPatch(obj.string("script_id"), changes)
    }

    fun encodeCatalogue(value: StoryCatalogue): ByteArray = bytes(buildJsonObject {
        put("schema_version", StoryPackPolicy.SCHEMA); put("type", "fgogotran-story-catalogue")
        put("packs", buildJsonArray { value.entries.sortedWith(compareBy({ it.packId }, { it.revision })).forEach { entry -> add(buildJsonObject {
            put("pack_id", entry.packId); put("revision", entry.revision); put("chapter_id", entry.chapterId)
            put("target_language", entry.language.tag); put("coverage", entry.coverage.wire)
            put("titles", buildJsonObject { entry.summary.titles.toSortedMap().forEach { (tag, title) -> put(tag, title) } })
            put("game_versions", buildJsonArray { entry.summary.gameVersions.sortedBy { it.code }.forEach { version -> add(buildJsonObject {
                put("name", version.name); put("code", version.code)
            }) } })
            put("script_count", entry.summary.scriptCount); put("change_count", entry.summary.changeCount)
            put("path", entry.path); put("sha256", entry.sha256); put("size", entry.size)
        }) } })
    })

    fun decodeCatalogue(bytes: ByteArray): StoryCatalogue {
        val obj = parse(bytes).keysExactly("schema_version", "type", "packs")
        require(obj.integer("schema_version") == StoryPackPolicy.SCHEMA && obj.string("type") == "fgogotran-story-catalogue")
        return StoryCatalogue(obj.array("packs").map { item ->
            val entry = item.jsonObject.keysExactly("pack_id", "revision", "chapter_id", "target_language", "coverage", "titles", "game_versions", "script_count", "change_count", "path", "sha256", "size")
            val summary = StoryCatalogueSummary(entry.getValue("titles").jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive.let { require(it.isString); it.content }
            }, entry.array("game_versions").map { it.jsonObject.keysExactly("name", "code").let { version ->
                StoryGameVersion(version.string("name"), version.number("code"))
            } }, entry.integer("script_count"), entry.integer("change_count"))
            StoryCatalogueEntry(entry.string("pack_id"), entry.integer("revision"), entry.string("chapter_id"), StoryLanguage.parse(entry.string("target_language")),
                enumWire(entry.string("coverage"), StoryCoverage.entries, StoryCoverage::wire), summary, entry.string("sha256"), entry.integer("size")).also {
                require(entry.string("path") == it.path) { "Unconfined catalogue archive path" }
            }
        })
    }
}

/** Scan before the JSON DOM is built, bounding nesting and rejecting escaped-key collisions. */
private class UniqueJsonKeys(private val text: String) {
    private var offset = 0
    fun check() { value(0); whitespace(); require(offset == text.length) { "Trailing story JSON" } }
    private fun whitespace() { while (offset < text.length && text[offset] in " \r\n\t") offset++ }
    private fun take(char: Char) { whitespace(); require(offset < text.length && text[offset++] == char) { "Invalid story JSON" } }
    private fun string(): String {
        whitespace(); val start = offset; take('"')
        while (offset < text.length) {
            when (text[offset++]) {
                '\\' -> { require(offset < text.length); offset++ }
                '"' -> return Json.parseToJsonElement(text.substring(start, offset)).jsonPrimitive.content
            }
        }
        error("Unterminated story JSON string")
    }
    private fun value(depth: Int) {
        require(depth <= 24) { "Story JSON nesting exceeds limit" }; whitespace(); require(offset < text.length)
        when (text[offset]) {
            '{' -> {
                offset++; whitespace(); val keys = mutableSetOf<String>()
                if (offset < text.length && text[offset] == '}') { offset++; return }
                while (true) {
                    require(keys.add(string())) { "Duplicate story JSON key" }; take(':'); value(depth + 1); whitespace()
                    require(offset < text.length)
                    if (text[offset] == '}') { offset++; return }; take(',')
                }
            }
            '[' -> {
                offset++; whitespace()
                if (offset < text.length && text[offset] == ']') { offset++; return }
                while (true) {
                    value(depth + 1); whitespace(); require(offset < text.length)
                    if (text[offset] == ']') { offset++; return }; take(',')
                }
            }
            '"' -> { string() }
            else -> {
                val start = offset
                while (offset < text.length && text[offset] !in ",]} \r\n\t") offset++
                require(offset > start)
            }
        }
    }
}
