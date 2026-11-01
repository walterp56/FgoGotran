package com.fgogotran.chapter.compile

// Shared by desktop preparation and its regression tests, not the installed app.

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoNativeScriptCodec
import com.fgogotran.chapter.codec.UnsupportedNative
import com.fgogotran.chapter.packs.*

/** Typed package text -> exact original fields. No I/O, regional alignment or installation. */
class StoryTextCompiler(private val codec: FgoNativeScriptCodec, private val strict: Boolean = true ) {
    fun compile(source: NativeScriptSource, patch: StoryScriptPatch, decodedSha256: String, checkCancelled: () -> Unit = {}): NativeCodecResult<AssetBytes> {
        return codec.rewriteFields(source, Sha256Digest.parse(decodedSha256), checkCancelled) { document ->
            require(patch.scriptId == source.binding.scriptId.value)
            val fields = document.fields.associateBy { it.id.value }
            patch.changes.map { change ->
                checkCancelled()
                val field = requireNotNull(fields[change.fieldId]) { "Package field is missing" }
                require(field.kind.name.lowercase() == change.kind.wire)
                val prefix = speakerPrefix(field)
                val body = field.sourceText.removePrefix(prefix)
                val required = protectedTokens(body)
                if (field.kind == NativeTextKind.SPEAKER) {
                    require(change.tokens.all { it is StoryTextToken.Text }) { "Speaker display names support plain text only" }
                }
                if (strict) require(controls(change.tokens) == required) { "Player placeholders or conditional order changed" }
                val replacement = prefix + change.tokens.joinToString("") { render(it) }
                if (field.kind == NativeTextKind.SPEAKER) require(replacement.isNotBlank())
                if (field.kind == NativeTextKind.DIALOGUE) {
                    val leading = replacement.trimStart()
                    require(!leading.startsWith("＠") && !leading.startsWith("＄") && !leading.startsWith("//") &&
                        !Regex("^？(?:[1-6]：|！)").containsMatchIn(leading)) { "Dialogue cannot introduce native line markers" }
                }
                NativeFieldReplacement(field.id, field.kind, Sha256Digest.parse(change.expectedTextSha256), replacement)
            }
        }
    }

    private fun speakerPrefix(field: NativeTextField): String = if (field.kind == NativeTextKind.SPEAKER)
        Regex("^(?:＠)?[A-Z]：").find(field.sourceText)?.value.orEmpty() else ""

    private fun render(token: StoryTextToken): String = when (token) {
        is StoryTextToken.Text -> token.value
        is StoryTextToken.Ruby -> "[#${token.base}:${token.reading}]"
        is StoryTextToken.Placeholder -> {
            require(':' !in token.key) { "Use a typed conditional, not a placeholder alias" }
            "[${token.key}]"
        }
        is StoryTextToken.Conditional -> "[&${token.first}:${token.second}]"
        is StoryTextToken.ColorTag -> "[${token.value}]"
        is StoryTextToken.LineCommand -> "[line ${token.value}]"
        StoryTextToken.SoftBreak -> "[r]"
        StoryTextToken.LineBreak -> "[r]"
    }

    private fun controls(tokens: List<StoryTextToken>): List<String> = tokens.mapNotNull { when (it) {
        is StoryTextToken.Placeholder -> "placeholder:${it.key}"
        is StoryTextToken.Conditional -> "conditional"
        else -> null
    } }

    /** Unrecognised tags in an EDITED field are unsupported, not stripped or guessed. */
    private fun protectedTokens(text: String): List<String> {
        if (!strict) return emptyList()
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            if (text[offset] == ']') throw UnsupportedNative("Unmatched inline tag in edited field")
            if (text[offset] != '[') { offset++; continue }
            val end = text.indexOf(']', offset + 1)
            if (end < 0 || '[' in text.substring(offset + 1, end)) throw UnsupportedNative("Nested/unfinished inline tag in edited field")
            val value = text.substring(offset + 1, end)
            when {
                value == "r" -> Unit
                value.startsWith('#') -> {
                    // Native ruby/colour-ish tags are display-only; translated text may omit them.
                    val parts = value.substring(1).split(':')
                    if (parts.size != 2 || parts.any { it.isEmpty() }) Unit
                }
                value.startsWith('&') && ':' in value -> {
                    val parts = value.substring(1).split(':')
                    if (parts.size == 2 && parts.none { it.isEmpty() }) result += "conditional"
                }
                value.matches(Regex("%[0-9]{1,4}|&[A-Za-z0-9_.-]{1,64}")) -> result += "placeholder:$value"
                else -> Unit // Display-only native command; translated text may omit it.
            }
            offset = end + 1
        }
        return result
    }
}
