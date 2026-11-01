package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.*
import com.fgogotran.chapter.compile.StoryTextCompiler
import com.fgogotran.chapter.packs.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

internal enum class AlignmentReason {
    TRANSLATED, IDENTICAL_TEXT, REGIONAL_MISSING, JP_SOURCE_MISSING, NOT_IN_NATIVE_BASELINE, OUTSIDE_SNAPSHOT_SCOPE,
    JP_BASELINE_DIFFERS, QUEST_PHASE_DIFFERS, PROGRAM_DIFFERS, FIELD_STRUCTURE_DIFFERS, UNSUPPORTED_TEXT
}

/** A manual mapping is an explicit complete field bijection, bound to all three exact inputs. */
internal data class ReviewedAlignment(
    val scriptId: String, val nativeSha256: String, val jpSha256: String, val targetSha256: String,
    val fields: List<Pair<String, String>>, val sha256: String
) {
    companion object {
        fun read(bytes: ByteArray): ReviewedAlignment {
            val json = PreparationFiles.json(bytes).exact("type", "schema_version", "script_id", "native_decoded_sha256", "jp_source_sha256", "target_source_sha256", "fields")
            require(json.string("type") == "fgogotran-reviewed-complete-alignment" && json.int("schema_version") == 1)
            val id = StoryPackPolicy.scriptId(json.string("script_id"))
            val digests = listOf("native_decoded_sha256", "jp_source_sha256", "target_source_sha256").map { StoryPackPolicy.digest(json.string(it)) }
            val fields = json.getValue("fields").jsonArray.map { it.jsonObject.exact("jp_field", "target_field").let { row -> row.string("jp_field") to row.string("target_field") } }
            require(fields.size in 1..4096 && fields.map { it.first }.distinct().size == fields.size && fields.map { it.second }.distinct().size == fields.size)
            return ReviewedAlignment(id, digests[0], digests[1], digests[2], fields, PreparationFiles.hash(PreparationFiles.canonical(json)))
        }
    }
}

internal data class AlignedScript(val reason: AlignmentReason, val replacement: AssetBytes? = null, val fieldCount: Int = 0, val changedFields: Int = 0)

/** Fail closed per WHOLE script. Equal filenames, offsets or field counts alone never authorise edits. */
internal class CompleteScriptAlignment(private val codec: FgoNativeScriptCodec, private val strict: Boolean = true) {
    fun align(source: NativeScriptSource, jp: AtlasReference, jpBytes: ByteArray, target: AtlasReference, targetBytes: ByteArray,
        reviewed: ReviewedAlignment? = null): AlignedScript {
        require(source.binding.scriptId.value == jp.id && jp.id == target.id && jp.region == "JP" && target.region in listOf("CN", "TW", "NA"))
        val nativeBytes = codec.decodedBytes(source).valueOrThrow().toByteArray()
        if (reviewed != null) require(reviewed.scriptId == jp.id && reviewed.nativeSha256 == PreparationFiles.hash(nativeBytes) &&
            reviewed.jpSha256 == PreparationFiles.hash(jpBytes) && reviewed.targetSha256 == PreparationFiles.hash(targetBytes)) { "Reviewed mapping inputs changed" }
        if (jp.links != target.links) {
            require(reviewed == null) { "Reviewed quest/phase identity changed" }
            return AlignedScript(AlignmentReason.QUEST_PHASE_DIFFERS)
        }
        val nativeText = strictUtf8(nativeBytes)
        if (PreparationFiles.text(nativeBytes) != PreparationFiles.text(jpBytes)) {
            require(reviewed == null) { "Reviewed JP source no longer matches the native baseline" }
            return AlignedScript(AlignmentReason.JP_BASELINE_DIFFERS)
        }
        return try {
            // Atlas regional scripts may use CRLF (notably NA); the JP-derived parser
            // and compiler operate on LF-only text. Normalise the target only, so JP
            // field offsets still map to the captured native payload.
            val targetText = strictUtf8(targetBytes).removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            val before = codec.parsePlain(nativeText)
            val after = codec.parsePlain(targetText)
            if (before.fields.isEmpty()) {
                require(reviewed == null) { "Reviewed complete-script field structure differs" }
                return AlignedScript(AlignmentReason.FIELD_STRUCTURE_DIFFERS)
            }
            val mapping = if (reviewed == null) {
                if (strict) {
                    if (before.fields.size == after.fields.size) {
                        if (program(nativeText, before) != program(targetText, after)) return AlignedScript(AlignmentReason.PROGRAM_DIFFERS)
                        before.fields.zip(after.fields)
                    } else {
                        // Regional scripts may add/omit speaker-name slots ([charaSet]/＠ lines,
                        // notably NA). Speaker slots are JP control identity, so align the
                        // translatable DIALOGUE/CHOICE fields by order and keep speakers native.
                        val left = before.fields.filter { it.kind != NativeTextKind.SPEAKER }
                        val right = after.fields.filter { it.kind != NativeTextKind.SPEAKER }
                        if (left.isEmpty() || left.size != right.size || left.map { it.kind } != right.map { it.kind }) {
                            return AlignedScript(AlignmentReason.FIELD_STRUCTURE_DIFFERS, fieldCount = before.fields.size)
                        }
                        if (programWithoutSpeakers(nativeText) != programWithoutSpeakers(targetText)) return AlignedScript(AlignmentReason.PROGRAM_DIFFERS)
                        left.zip(right)
                    }
                } else {
                    // Catalogue mode: match translatable fields by kind/order even when the
                    // regional script added or removed lines; unmatched fields stay native.
                    if (before.fields.size == after.fields.size) {
                        before.fields.zip(after.fields)
                    } else {
                        relaxedMatch(
                            before.fields.filter { it.kind != NativeTextKind.SPEAKER },
                            after.fields.filter { it.kind != NativeTextKind.SPEAKER },
                        )
                    }
                }
            } else {
                require(reviewed.fields.map { it.first }.toSet() == before.fields.map { it.id.value }.toSet() &&
                    reviewed.fields.map { it.second }.toSet() == after.fields.map { it.id.value }.toSet()) { "Reviewed mapping must cover both complete scripts" }
                before.fields.map { field -> field to after.fields.single { it.id.value == reviewed.fields.single { pair -> pair.first == field.id.value }.second } }
            }
            if (mapping.any { it.first.kind != it.second.kind } && (strict || reviewed != null)) {
                require(reviewed == null) { "Reviewed field kinds differ" }
                return AlignedScript(AlignmentReason.FIELD_STRUCTURE_DIFFERS)
            }

            val usable = if (strict) mapping else mapping.filter { (field, translated) ->
                val raw = translated.sourceText.trimStart()
                !raw.startsWith("＠") && !raw.startsWith("//") && !raw.contains("[charaSet") &&
                    !(field.kind == NativeTextKind.SPEAKER && raw.any { it == '"' })
            }
            val nameMap = speakerNames(nativeText, targetText)
            fun buildChange(field: NativeTextField, translated: NativeTextField): StoryTextChange? {
                // Exact unchanged fields (including blank narrator names and opaque
                // inline content) need no lowering or edit. Keep their native bytes.
                if (translated.sourceText == field.sourceText) return null
                fun alias(value: String) = Regex("^[A-Z]：").find(value)?.value.orEmpty()
                val prefix = if (field.kind == NativeTextKind.SPEAKER) alias(field.sourceText) else ""
                if (field.kind == NativeTextKind.SPEAKER) require(alias(translated.sourceText) == prefix)
                val raw = translated.sourceText.removePrefix(prefix)
                val cleaned = stripRegionalDisplayTags(raw)
                if (cleaned.isBlank()) return null
                var tokens = runCatching { tokens(raw) }.getOrElse { tokens(cleaned) }
                // Regional clients may drop or alter JP furigana; translated text omits ruby.
                if (tokens.count { it is StoryTextToken.Ruby } != field.ruby.size) {
                    tokens = tokens.filterNot { it is StoryTextToken.Ruby }
                }
                if (tokens.isEmpty()) return null
                return StoryTextChange(field.id.value,
                    StoryFieldKind.entries.single { it.wire == field.kind.name.lowercase() },
                    PreparationFiles.hash(field.sourceText.toByteArray(Charsets.UTF_8)), tokens)
            }
            // One bad field must not discard the whole script: isolate each field.
            val changes = usable.mapNotNull { (field, translated) ->
                if (strict) buildChange(field, translated)
                else runCatching { buildChange(field, translated) }.getOrNull()
            }
            // Names live in charaSet commands, not dialogue fields. Translate the visible
            // speaker fields by matching the JP name to the NA charaSet name.
            val speakerChanges = before.fields.filter { it.kind == NativeTextKind.SPEAKER }.mapNotNull { field ->
                val source = field.sourceText
                val prefix = if (source.startsWith("＠")) "＠" else ""
                val alias = Regex("^[A-Z]：").find(source.removePrefix(prefix))?.value.orEmpty()
                val name = source.removePrefix(prefix).removePrefix(alias)
                    .replace(Regex("\\[[^\\]]*\\]"), "").trim()
                val start = java.nio.ByteBuffer.wrap(field.locator.payload.toByteArray()).int
                val display = start in 1..nativeText.length && nativeText[start - 1] == '＠'
                val english = nameMap[name] ?: return@mapNotNull null
                val text = if (!display && alias.isEmpty()) {
                    val compact = english.replace(Regex("\\s+"), "_")
                    if (compact == source) return@mapNotNull null
                    compact
                } else if (alias.isEmpty()) prefix + english else english
                if (text == source) return@mapNotNull null
                StoryTextChange(field.id.value,
                    StoryFieldKind.entries.single { it.wire == field.kind.name.lowercase() },
                    PreparationFiles.hash(source.toByteArray(Charsets.UTF_8)),
                    listOf(StoryTextToken.Text(text)))
            }
            val allChanges = (changes + speakerChanges).distinctBy { it.fieldId }
            if (allChanges.isEmpty()) return AlignedScript(AlignmentReason.IDENTICAL_TEXT, source.payload, before.fields.size, 0)
            // Existing typed compiler preserves original commands and ordered player/gender controls.
            val replacement = StoryTextCompiler(codec, strict = false).compile(source, StoryScriptPatch(jp.id, allChanges), PreparationFiles.hash(nativeBytes)).valueOrThrow()
            AlignedScript(AlignmentReason.TRANSLATED, replacement, before.fields.size, allChanges.size)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A supplied explicit review that no longer binds its inputs is a build error,
            // not silent fallback to a guessed/automatic mapping.
            if (reviewed != null) throw failure
            println("ALIGN_ERR ${jp.id} ${failure.message}")
            AlignedScript(AlignmentReason.UNSUPPORTED_TEXT)
        }
    }

    private fun program(text: String, document: FgoNativeScriptCodec.PlainDocument): String {
        val output = StringBuilder()
        var cursor = 0
        document.fields.forEachIndexed { index, field ->
            val range = ByteBuffer.wrap(field.locator.payload.toByteArray())
            val start = range.int; val end = range.int
            require(start >= cursor && text.substring(start, end) == field.sourceText)
            output.append(text, cursor, start).append("<FIELD:$index:${field.kind}>")
            cursor = end
        }
        output.append(text, cursor, text.length)
        // Comments/blank lines do not execute. Every other command, branch and order must match.
        return output.toString().lineSequence().filterNot { it.isBlank() || it.startsWith("//") }.joinToString("\n")
    }

    /** Structural comparison that ignores speaker-name lines entirely (their counts differ by region). */
    private fun relaxedMatch(
        left: List<NativeTextField>,
        right: List<NativeTextField>,
    ): List<Pair<NativeTextField, NativeTextField>> {
        val out = mutableListOf<Pair<NativeTextField, NativeTextField>>()
        var index = 0
        for (field in left) {
            if (index < right.size) {
                out += field to right[index]
                index++
            }
        }
        return out
    }
    /** True when the field is a visible ＠name line (not a [charaSet] control name). */
    private fun isDisplaySpeaker(field: NativeTextField, text: String): Boolean {
        if (field.kind != NativeTextKind.SPEAKER) return false
        val buffer = java.nio.ByteBuffer.wrap(field.locator.payload.toByteArray())
        val start = buffer.int
        return start in 1..text.length && text[start - 1] == '＠'
    }
    private fun programWithoutSpeakers(text: String): String {
        val output = mutableListOf<String>()
        var inDialogue = false
        val choice = Regex("^？[1-6]：")
        val commandOnly = Regex("(?:\\[[^\\[\\]]*\\])+")
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        for (line in normalized.split("\n")) {
            when {
                line.startsWith("＠") -> inDialogue = true
                line.startsWith("[charaSet ") -> Unit
                choice.containsMatchIn(line) -> { output += "<CHOICE>"; inDialogue = false }
                line == "？！" || line.startsWith("＄") -> { inDialogue = false; output += line }
                line == "[k]" || line == "[messageOff]" || line == "[end]" -> { inDialogue = false; output += line }
                line.isBlank() || line.startsWith("//") -> output += line
                commandOnly.matches(line) -> output += line
                inDialogue -> output += "<TEXT>"
                else -> output += line
            }
        }
        return output.joinToString("\n")
    }
    /** JP-inherited display tags that the typed grammar cannot represent (colour spans, spacing). */
    private fun speakerNames(jp: String, na: String): Map<String, String> {
        data class Entry(val slot: String, val character: String, val name: String)
        fun entries(text: String): List<Entry> = Regex("\\[charaSet\\s+(\\w+)\\s+(\\d+)\\s+\\d+\\s+([^\\]]+)\\]")
            .findAll(text)
            .map { match ->
                Entry(match.groupValues[1], match.groupValues[2],
                    match.groupValues[3].trim().removeSurrounding("\""))
            }
            .toList()
        val jpEntries = entries(jp)
        val naBySlot = entries(na).associateBy { it.slot }
        val naByCharacter = entries(na).associateBy { it.character }
        val result = mutableMapOf<String, String>()
        for (entry in jpEntries) {
            if (entry.name.isBlank() || entry.name == "？？？" || entry.name == "???") continue
            val english = naBySlot[entry.slot]?.name ?: naByCharacter[entry.character]?.name ?: continue
            if (english.isNotBlank() && english != entry.name) result[entry.name] = english
        }
        return result
    }

    private fun stripRegionalDisplayTags(value: String): String {
        // Keep the tags that control layout/colour, drop only unknown display-only tags.
        var s = value
            .replace("[r]", "\u0001")
            .replace("[sr]", "\u0002")
            .replace("[-]", "\u0003")
        s = Regex("\\[line [0-9]{1,2}\\]").replace(s) { "\u0004" + it.value }
        s = Regex("\\[[0-9A-Fa-f]{6,8}\\]").replace(s) { "\u0005" + it.value }
        s = s.replace(Regex("\\[(?![%&#])[^\\]\\[]{0,40}\\]"), "")
        return s.replace("\u0001", "[r]").replace("\u0002", "[sr]")
            .replace("\u0003", "[-]").replace("\u0004", "").replace("\u0005", "")
    }

    internal fun tokens(value: String): List<StoryTextToken> {
        val result = mutableListOf<StoryTextToken>()
        var offset = 0
        while (offset < value.length) {
            val start = value.indexOf('[', offset).let { if (it < 0) value.length else it }
            if (start > offset) result += StoryTextToken.Text(value.substring(offset, start))
            if (start == value.length) break
            val end = value.indexOf(']', start + 1)
            require(end > start && '[' !in value.substring(start + 1, end))
            val token = value.substring(start + 1, end)
            result += when {
                token == "r" -> StoryTextToken.LineBreak
                token == "sr" -> StoryTextToken.SoftBreak
                token.matches(Regex("line [0-9]{1,2}")) -> StoryTextToken.LineCommand(token.substringAfter(' ').toInt())
                token == "-" -> StoryTextToken.ColorTag("-")
                token.matches(Regex("[0-9A-Fa-f]{6,8}")) -> StoryTextToken.ColorTag(token)
                token.startsWith('#') -> token.substring(1).split(':').let { require(it.size == 2); StoryTextToken.Ruby(it[0], it[1]) }
                token.startsWith('&') && ':' in token -> token.substring(1).split(':').let { require(it.size == 2); StoryTextToken.Conditional(it[0], it[1]) }
                token.matches(Regex("%[0-9]{1,4}|&[A-Za-z0-9_.-]{1,64}")) -> StoryTextToken.Placeholder(token)
                else -> throw UnsupportedNative("Unreviewed regional inline command")
            }
            offset = end + 1
        }
        require(result.isNotEmpty())
        return result
    }
}
