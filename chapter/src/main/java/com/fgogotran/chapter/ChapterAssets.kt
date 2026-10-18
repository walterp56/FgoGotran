package com.fgogotran.chapter

import java.security.MessageDigest
import java.util.Collections

/** A canonical path relative to a storage root that an adapter has independently verified. */
@JvmInline
value class GameAssetPath private constructor(val value: String) {
    companion object {
        fun parse(value: String): GameAssetPath {
            require(value.isNotEmpty() && value == value.trim()) { "Asset path must be nonempty and unpadded" }
            require(value.none { it.isISOControl() || it in "\\:%?#" }) { "Asset path must not be a URI or escaped path" }
            val segments = value.split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." && it == it.trim() }) {
                "Asset path must be relative and contain no empty or traversal segments"
            }
            return GameAssetPath(value)
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class Sha256Digest private constructor(val hex: String) {
    companion object {
        fun parse(hex: String): Sha256Digest {
            require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "SHA-256 must contain exactly 64 hexadecimal characters"
            }
            return Sha256Digest(hex.lowercase())
        }

        fun compute(bytes: ByteArray): Sha256Digest = Sha256Digest(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        )
    }

    override fun toString(): String = hex
}

/** Owns its bytes; neither callers nor codecs can mutate a captured original through this object. */
class AssetBytes private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size
    val sha256: Sha256Digest by lazy { Sha256Digest.compute(bytes) }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is AssetBytes && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "AssetBytes(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): AssetBytes = AssetBytes(bytes.copyOf())
    }
}

data class OriginalAssetFingerprint(
    val path: GameAssetPath,
    val sha256: Sha256Digest,
    val sizeBytes: Long
) {
    init {
        require(sizeBytes >= 0) { "Asset size must not be negative" }
    }
}

class OriginalAsset private constructor(val fingerprint: OriginalAssetFingerprint, val bytes: AssetBytes) {
    companion object {
        fun capture(path: GameAssetPath, bytes: ByteArray): OriginalAsset {
            val captured = AssetBytes.copyOf(bytes)
            return OriginalAsset(OriginalAssetFingerprint(path, captured.sha256, captured.size.toLong()), captured)
        }
    }
}

/** Exact originals for a plan, not an inferred game-data revision or account-progress claim. */
class OriginalAssetBaseline(assets: Collection<OriginalAssetFingerprint>) {
    val assets: List<OriginalAssetFingerprint> = immutableList(assets)
    private val byPath = this.assets.associateBy { it.path }

    init {
        require(this.assets.isNotEmpty()) { "An original baseline must contain assets" }
        require(byPath.size == this.assets.size) { "An original baseline must not repeat an asset path" }
    }

    fun fingerprintFor(path: GameAssetPath): OriginalAssetFingerprint? = byPath[path]
}

@JvmInline
value class ChapterId private constructor(val value: String) {
    companion object {
        fun parse(value: String): ChapterId = ChapterId(requireIdentifier(value))
    }
}

@JvmInline
value class ScriptId private constructor(val value: String) {
    companion object {
        fun parse(value: String): ScriptId = ScriptId(requireIdentifier(value))
    }
}

class ChapterDefinition(val id: ChapterId, scriptIds: Collection<ScriptId>) {
    val scriptIds: List<ScriptId> = immutableList(scriptIds)

    init {
        require(this.scriptIds.isNotEmpty()) { "A chapter must declare its scripts" }
        require(this.scriptIds.distinct().size == this.scriptIds.size) { "A chapter must not repeat script IDs" }
    }
}

/** Multiple scripts may share an asset; one script may have multiple explicit locations. */
data class ScriptAssetBinding(val scriptId: ScriptId, val assetPath: GameAssetPath, val locator: NativeLocator)

class ChapterAssetPlan(
    val chapter: ChapterDefinition,
    bindings: Collection<ScriptAssetBinding>,
    val baseline: OriginalAssetBaseline
) {
    val bindings: List<ScriptAssetBinding> = immutableList(bindings)

    init {
        require(this.bindings.distinct().size == this.bindings.size) { "A plan must not repeat exact bindings" }
        require(this.bindings.map { it.scriptId }.toSet() == chapter.scriptIds.toSet()) {
            "A plan must bind every declared script and no undeclared scripts"
        }
        require(this.bindings.map { it.assetPath }.toSet() == baseline.assets.map { it.path }.toSet()) {
            "A plan must capture exactly the assets referenced by its bindings"
        }
    }
}

internal fun requireIdentifier(value: String): String {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "An identifier must be nonblank, unpadded and contain no control characters"
    }
    return value
}

internal fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
