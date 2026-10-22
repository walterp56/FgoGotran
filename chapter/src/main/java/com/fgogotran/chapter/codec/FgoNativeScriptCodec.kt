package com.fgogotran.chapter.codec

import com.fgogotran.chapter.*
import java.nio.ByteBuffer
import java.util.Base64

/** Lossless observed script syntax. Unknown commands remain opaque and are never edited. */
class FgoNativeScriptCodec(private val envelope: RijndaelEnvelope) : NativeScriptCodec {
    override val format = NativeFormat("fgo-native-line-script", 1)
    private val choicePrefix = Regex("^？[1-6]：")
    private val commandOnly = Regex("(?:\\[[^\\[\\]]*\\])+")
    private val rubyTag = Regex("\\[#([^\\[\\]:]+):([^\\[\\]]+)\\]")
    private val placeholderTag = Regex("\\[(?:%[0-9]+|&[^\\[\\]]+)\\]")
    private val speakerAlias = Regex("^[A-Z]：")
    // The observed single-command, four-argument form only; other forms stay opaque.
    private val charaSetName = Regex("^\\[charaSet [A-Z] [0-9]{1,12} [0-9]{1,4} ([^\\[\\]\\s]+)\\]$")
    private data class Contents(val packed: ByteArray, val plain: ByteArray, val compression: TextCompression)
    internal data class PlainDocument(val fields: List<NativeTextField>, val branches: List<NativeBranchIdentity>)

    override fun decode(source: NativeScriptSource): NativeCodecResult<DecodedNativeScript> = nativeAttempt {
        parse(source, strictUtf8(contents(source).plain))
    }

    override fun encodeUnchanged(document: DecodedNativeScript): NativeCodecResult<AssetBytes> = nativeAttempt {
        require(document.format == format)
        val decoded = decode(document.source).valueOrThrow()
        require(signature(decoded) == signature(document)) { "Script document does not match original" }
        val packed = contents(document.source).packed
        require(envelope.encrypt(packed).contentEquals(decodeBase64Text(document.source.payload.toByteArray(), 4 * 1024 * 1024)))
        document.source.payload
    }

    /** One exact plain field only. Ruby/inline commands/placeholders/choices are not patchable here. */
    fun patchPlainDialogue(source: NativeScriptSource, patch: NativeTextPatch): NativeCodecResult<AssetBytes> = nativeAttempt {
        val content = contents(source)
        val text = strictUtf8(content.plain)
        val document = parse(source, text)
        val field = document.fields.single { it.id == patch.fieldId }
        require(field.kind == NativeTextKind.DIALOGUE && field.ruby.isEmpty() && field.placeholders.isEmpty())
        require(field.sourceText.none { it in "[]＠＄？" } && field.sourceText.isNotBlank()) { "Only plain dialogue is supported by the proof patcher" }
        require(Sha256Digest.compute(field.sourceText.toByteArray(Charsets.UTF_8)) == patch.expectedTextSha256) { "Field baseline mismatch" }
        val locator = ByteBuffer.wrap(field.locator.payload.toByteArray())
        val start = locator.int
        val end = locator.int
        val updated = text.substring(0, start) + patch.replacement + text.substring(end)
        require(updated.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
        val packed = packText(updated.toByteArray(Charsets.UTF_8), content.compression)
        val originalText = strictUtf8(source.payload.toByteArray())
        val bom = if (originalText.startsWith("\uFEFF")) "\uFEFF" else ""
        AssetBytes.copyOf((bom + Base64.getEncoder().encodeToString(envelope.encrypt(packed))).toByteArray(Charsets.UTF_8))
    }

    /** Exact decoded bytes for local provenance and before/after structural verification. */
    fun decodedBytes(source: NativeScriptSource): NativeCodecResult<AssetBytes> = nativeAttempt { AssetBytes.copyOf(contents(source).plain) }

    /** One decode/edit/encode pass. All positions are resolved against the original, then reparsed. */
    internal fun rewriteFields(
        source: NativeScriptSource,
        expectedDecodedSha256: Sha256Digest,
        checkCancelled: () -> Unit,
        editsFor: (DecodedNativeScript) -> List<NativeFieldReplacement>
    ): NativeCodecResult<AssetBytes> = nativeAttempt {
        checkCancelled()
        val content = contents(source)
        require(Sha256Digest.compute(content.plain) == expectedDecodedSha256) { "Decoded script baseline mismatch" }
        val text = strictUtf8(content.plain)
        val document = parse(source, text)
        val edits = editsFor(document)
        require(edits.isNotEmpty() && edits.size <= 4096 && edits.map { it.fieldId }.distinct().size == edits.size)
        val fields = document.fields.withIndex().associateBy { it.value.id }
        val expectedFields = document.fields.map { it.kind to it.sourceText }.toMutableList()
        data class Edit(val start: Int, val end: Int, val replacement: String)
        val ranges = edits.map { edit ->
            checkCancelled()
            val indexed = requireNotNull(fields[edit.fieldId]) { "Native field is missing" }
            val field = indexed.value
            require(field.kind == edit.kind && Sha256Digest.compute(field.sourceText.toByteArray(Charsets.UTF_8)) == edit.expectedTextSha256) { "Native field baseline mismatch" }
            require(edit.replacement.length <= 8192 && edit.replacement.none { it == '\r' || it == '\n' || it == '\u0000' })
            val locator = ByteBuffer.wrap(field.locator.payload.toByteArray())
            require(locator.remaining() == 8)
            val start = locator.int; val end = locator.int
            require(start in 0..end && end <= text.length && text.substring(start, end) == field.sourceText)
            if (field.kind == NativeTextKind.SPEAKER) {
                if (start > 0 && text[start - 1] == '＠') {
                    require(speakerAlias.find(field.sourceText)?.value == speakerAlias.find(edit.replacement)?.value) {
                        "A speaker slot prefix cannot be introduced or changed"
                    }
                } else require(edit.replacement.none(Char::isWhitespace) ||
                    (edit.replacement.startsWith("\"") && edit.replacement.endsWith("\"") &&
                        edit.replacement.count { it == '"' } == 2 && edit.replacement.none { it == '\r' || it == '\n' })) { "A charaSet display name must remain one argument" }
            }
            expectedFields[indexed.index] = field.kind to edit.replacement
            Edit(start, end, edit.replacement)
        }.sortedBy { it.start }
        require(text.length.toLong() + ranges.sumOf { it.replacement.length.toLong() - (it.end - it.start) } <= 4 * 1024 * 1024)
        var cursor = 0
        val output = StringBuilder(text.length)
        ranges.forEach { edit ->
            require(edit.start >= cursor) { "Overlapping native field replacements" }
            output.append(text, cursor, edit.start).append(edit.replacement); cursor = edit.end
        }
        output.append(text, cursor, text.length)
        val updated = output.toString()
        require(updated.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
        checkCancelled()
        // Changed field lengths may shift IDs/locators, but not field order/kinds or branch syntax.
        val after = parse(source, updated)
        require(after.fields.map { it.kind to it.sourceText } == expectedFields) { "Replacement changed native field structure" }
        fun branchMarkers(doc: DecodedNativeScript, raw: String) = doc.branches.map {
            val range = ByteBuffer.wrap(it.locator.payload.toByteArray()); val start = range.int; val end = range.int
            raw.substring(start, end)
        }
        require(branchMarkers(document, text) == branchMarkers(after, updated)) { "Replacement changed native choice markers" }
        if (updated == text) return@nativeAttempt source.payload
        checkCancelled()
        val packed = packText(updated.toByteArray(Charsets.UTF_8), content.compression)
        val bom = if (strictUtf8(source.payload.toByteArray()).startsWith('\uFEFF')) "\uFEFF" else ""
        val result = AssetBytes.copyOf((bom + Base64.getEncoder().encodeToString(envelope.encrypt(packed))).toByteArray(Charsets.UTF_8))
        require(result.size <= 4 * 1024 * 1024)
        checkCancelled()
        result
    }

    private fun contents(source: NativeScriptSource): Contents {
        val encoded = decodeBase64Text(source.payload.toByteArray(), 4 * 1024 * 1024)
        val packed = envelope.decrypt(encoded, 4 * 1024 * 1024)
        val (compression, plain) = unpackText(packed, 4 * 1024 * 1024)
        val text = strictUtf8(plain)
        validatePlain(text)
        return Contents(packed, plain, compression)
    }

    private fun validatePlain(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024)
        val opening = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        // One captured script omits the optional title marker and starts directly with this command.
        if (!opening.startsWith("＄") && opening != "[soundStopAll]") throw UnsupportedNative("Unverified native script opening")
        require('\u0000' !in text) { "Unverified native script terminator" }
    }

    private fun parse(source: NativeScriptSource, text: String): DecodedNativeScript = parsePlain(text).let {
        DecodedNativeScript(source, format, it.fields, it.branches)
    }

    /** Same grammar for developer-side Atlas alignment, without inventing an encrypted source. */
    internal fun parsePlain(text: String): PlainDocument {
        validatePlain(text)
        val fields = mutableListOf<NativeTextField>()
        val branches = mutableListOf<NativeBranchIdentity>()
        var inDialogue = false
        fun locator(start: Int, end: Int): NativeLocator = NativeLocator(format, AssetBytes.copyOf(ByteBuffer.allocate(8).putInt(start).putInt(end).array()))
        fun field(start: Int, end: Int, kind: NativeTextKind) {
            val value = text.substring(start, end)
            val ruby = rubyTag.findAll(value).mapIndexed { index, match ->
                val baseStart = match.range.first + 2
                NativeRubySpan(NativeElementId.parse("ruby:$start:$index"), Utf16TextRange(baseStart, baseStart + match.groupValues[1].length),
                    match.groupValues[2], locator(start + match.range.first, start + match.range.last + 1))
            }.toList()
            val placeholders = placeholderTag.findAll(value).mapIndexed { index, match ->
                NativePlaceholderSpan(NativeElementId.parse("placeholder:$start:$index"), Utf16TextRange(match.range.first, match.range.last + 1),
                    locator(start + match.range.first, start + match.range.last + 1))
            }.toList()
            fields += NativeTextField(NativeElementId.parse("${kind.name.lowercase()}:$start"), kind, locator(start, end), value, ruby, placeholders)
        }
        var offset = 0
        while (offset < text.length) {
            var end = offset
            while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
            var next = end
            if (next < text.length && text[next] == '\r') next++
            if (next < text.length && text[next] == '\n') next++
            val line = text.substring(offset, end)
            val choice = choicePrefix.find(line)
            val characterName = charaSetName.matchEntire(line)?.groups?.get(1)
            when {
                line.startsWith("＠") -> { field(offset + 1, end, NativeTextKind.SPEAKER); inDialogue = true }
                choice != null -> {
                    field(offset + choice.value.length, end, NativeTextKind.CHOICE)
                    // Native branch body begins after this choice marker line. No CFG inference.
                    branches += NativeBranchIdentity(NativeElementId.parse("choice:$offset"), locator(offset, offset + choice.value.length), listOf(locator(next, next)))
                    inDialogue = false
                }
                line == "？！" || line.startsWith("＄") -> inDialogue = false
                line == "[k]" || line == "[messageOff]" || line == "[end]" -> inDialogue = false
                characterName != null -> field(offset + characterName.range.first, offset + characterName.range.last + 1, NativeTextKind.SPEAKER)
                line.isBlank() || line.startsWith("//") -> Unit
                commandOnly.matches(line) && !rubyTag.containsMatchIn(line) && !placeholderTag.containsMatchIn(line) -> Unit
                inDialogue -> field(offset, end, NativeTextKind.DIALOGUE)
            }
            offset = next
        }
        return PlainDocument(immutableList(fields), immutableList(branches))
    }

    private fun signature(document: DecodedNativeScript): List<Any> = listOf(
        document.fields.map { listOf(it.id, it.kind, it.locator, it.sourceText, it.ruby, it.placeholders) },
        document.branches.map { listOf(it.id, it.locator, it.targets) }
    )
}
