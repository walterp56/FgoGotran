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
    private data class Contents(val packed: ByteArray, val plain: ByteArray, val compression: TextCompression)

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

    private fun contents(source: NativeScriptSource): Contents {
        val encoded = decodeBase64Text(source.payload.toByteArray(), 4 * 1024 * 1024)
        val packed = envelope.decrypt(encoded, 4 * 1024 * 1024)
        val (compression, plain) = unpackText(packed, 4 * 1024 * 1024)
        val text = strictUtf8(plain)
        val opening = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        // One captured script omits the optional title marker and starts directly with this command.
        if (!opening.startsWith("＄") && opening != "[soundStopAll]") throw UnsupportedNative("Unverified native script opening")
        require('\u0000' !in text) { "Unverified native script terminator" }
        return Contents(packed, plain, compression)
    }

    private fun parse(source: NativeScriptSource, text: String): DecodedNativeScript {
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
                line.isBlank() || line.startsWith("//") || commandOnly.matches(line) -> Unit
                inDialogue -> field(offset, end, NativeTextKind.DIALOGUE)
            }
            offset = next
        }
        return DecodedNativeScript(source, format, fields, branches)
    }

    private fun signature(document: DecodedNativeScript): List<Any> = listOf(
        document.fields.map { listOf(it.id, it.kind, it.locator, it.sourceText, it.ruby, it.placeholders) },
        document.branches.map { listOf(it.id, it.locator, it.targets) }
    )
}
