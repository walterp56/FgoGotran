package com.fgogotran.chapter

/** Identifies a codec's own locator schema; it is not a guessed game format/version. */
data class NativeFormat(val id: String, val locatorSchemaVersion: Int) {
    init {
        requireIdentifier(id)
        require(locatorSchemaVersion > 0) { "A locator schema version must be positive" }
    }
}

/** Only the matching codec interprets this payload. Empty payloads may denote its root. */
data class NativeLocator(val format: NativeFormat, val payload: AssetBytes)

@JvmInline
value class NativeElementId private constructor(val value: String) {
    companion object {
        fun parse(value: String): NativeElementId = NativeElementId(requireIdentifier(value))
    }
}

/** Offsets refer to UTF-16 code units in the decoded field, never byte offsets in the asset. */
data class Utf16TextRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0 && endExclusive >= start) { "Invalid UTF-16 range" }
    }

    internal fun validateFor(text: String) {
        require(endExclusive <= text.length) { "Range extends beyond its decoded text field" }
        for (offset in listOf(start, endExclusive)) {
            require(offset == 0 || offset == text.length ||
                !Character.isSurrogatePair(text[offset - 1], text[offset])) {
                "Range must not split a UTF-16 surrogate pair"
            }
        }
    }
}

enum class NativeTextKind { SPEAKER, DIALOGUE, CHOICE }

data class NativeRubySpan(
    val id: NativeElementId,
    val baseRange: Utf16TextRange,
    val reading: String,
    val locator: NativeLocator
) {
    init {
        require(baseRange.endExclusive > baseRange.start) { "Ruby must identify a nonempty base" }
    }
}

data class NativePlaceholderSpan(val id: NativeElementId, val range: Utf16TextRange, val locator: NativeLocator)

/** A codec supplies these relationships from verified parsing, not by scanning visible punctuation. */
class NativeTextField(
    val id: NativeElementId,
    val kind: NativeTextKind,
    val locator: NativeLocator,
    val sourceText: String,
    ruby: Collection<NativeRubySpan> = emptyList(),
    placeholders: Collection<NativePlaceholderSpan> = emptyList()
) {
    val ruby: List<NativeRubySpan> = immutableList(ruby)
    val placeholders: List<NativePlaceholderSpan> = immutableList(placeholders)

    init {
        this.ruby.forEach { it.baseRange.validateFor(sourceText) }
        this.placeholders.forEach { it.range.validateFor(sourceText) }
        require((this.ruby.map { it.id } + this.placeholders.map { it.id }).let { it.distinct().size == it.size }) {
            "A field must not repeat annotation IDs"
        }
        require((this.ruby.map { it.locator } + this.placeholders.map { it.locator }).all { it.format == locator.format }) {
            "Annotations must use their field's locator schema"
        }
    }
}

/** Opaque branch endpoints retain native identity without assuming labels or command syntax. */
class NativeBranchIdentity(val id: NativeElementId, val locator: NativeLocator, targets: Collection<NativeLocator>) {
    val targets: List<NativeLocator> = immutableList(targets)

    init {
        require(this.targets.all { it.format == locator.format }) { "Branch endpoints must use one locator schema" }
    }
}

class NativeScriptSource(
    val binding: ScriptAssetBinding,
    val originalAsset: OriginalAssetFingerprint,
    val payload: AssetBytes
) {
    init {
        require(binding.assetPath == originalAsset.path) { "Script source must identify its original asset" }
    }
}

class DecodedNativeScript(
    val source: NativeScriptSource,
    val format: NativeFormat,
    fields: Collection<NativeTextField>,
    branches: Collection<NativeBranchIdentity> = emptyList()
) {
    val fields: List<NativeTextField> = immutableList(fields)
    val branches: List<NativeBranchIdentity> = immutableList(branches)

    init {
        require(this.fields.map { it.id }.distinct().size == this.fields.size) { "Script field IDs must be unique" }
        require(this.branches.map { it.id }.distinct().size == this.branches.size) { "Branch IDs must be unique" }
        require(this.fields.all { it.locator.format == format } && this.branches.all { it.locator.format == format }) {
            "Decoded script locators must use its codec schema"
        }
    }
}

class DecodedNativeContainer(val original: OriginalAsset, val format: NativeFormat, scripts: Collection<NativeScriptSource>) {
    val scripts: List<NativeScriptSource> = immutableList(scripts)

    init {
        require(this.scripts.all { it.originalAsset == original.fingerprint && it.binding.locator.format == format }) {
            "Container scripts must refer to this exact original and locator schema"
        }
        require(this.scripts.map { it.binding }.distinct().size == this.scripts.size) { "Container bindings must be unique" }
    }
}

data class NativeManifestEntry(val id: NativeElementId, val assetPath: GameAssetPath, val locator: NativeLocator)

class DecodedNativeManifest(val original: OriginalAsset, val format: NativeFormat, entries: Collection<NativeManifestEntry>) {
    val entries: List<NativeManifestEntry> = immutableList(entries)

    init {
        require(this.entries.map { it.id }.distinct().size == this.entries.size) { "Manifest entry IDs must be unique" }
        require(this.entries.all { it.locator.format == format }) { "Manifest entries must use its codec schema" }
    }
}

sealed interface NativeCodecResult<out T> {
    data class Success<T>(val value: T) : NativeCodecResult<T>
    data class Unsupported(val reason: String) : NativeCodecResult<Nothing> {
        init { require(reason.isNotBlank()) }
    }
    data class Invalid(val reason: String) : NativeCodecResult<Nothing> {
        init { require(reason.isNotBlank()) }
    }
}

/** Contracts only. Unknown formats must not be reported as decoded or rebuilt successfully. */
interface NativeScriptCodec {
    val format: NativeFormat
    fun decode(source: NativeScriptSource): NativeCodecResult<DecodedNativeScript>
    fun encodeUnchanged(document: DecodedNativeScript): NativeCodecResult<AssetBytes>
}

interface NativeContainerCodec {
    val format: NativeFormat
    fun decode(original: OriginalAsset): NativeCodecResult<DecodedNativeContainer>
    fun encodeUnchanged(document: DecodedNativeContainer): NativeCodecResult<AssetBytes>
}

interface NativeManifestCodec {
    val format: NativeFormat
    fun decode(original: OriginalAsset): NativeCodecResult<DecodedNativeManifest>
    fun encodeUnchanged(document: DecodedNativeManifest): NativeCodecResult<AssetBytes>
}
