package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.packs.*
import java.util.Collections

/** Developer-reviewed originals, independent of a capture's self-reported hashes/index. */
internal class ReviewedNativeBaseline(val id: String, val version: StoryGameVersion, assets: Map<String, Asset>) {
    data class Asset(val logical: String, val sha256: String, val size: Int) {
        init {
            require(logical.matches(Regex("ScriptActionEncrypt/[A-Za-z0-9_]{1,64}")))
            require(logical !in setOf("ScriptActionEncrypt/Common", "ScriptActionEncrypt/ScriptFileList"))
            StoryPackPolicy.digest(sha256); require(size in 1..StoryPackPolicy.MAX_ASSET)
        }
    }
    val assets: Map<String, Asset> = Collections.unmodifiableMap(LinkedHashMap(assets))
    init {
        StoryPackPolicy.id(id)
        require(this.assets.size in 1..16 && this.assets.keys.all { it.matches(Regex("[0-9a-f]{40}\\.bin")) })
        require(this.assets.values.map { it.logical }.distinct().size == this.assets.size)
    }
    fun requireOriginals(gameVersion: StoryGameVersion, originals: List<OriginalAsset>) {
        require(gameVersion == version && originals.map { it.fingerprint.path.value }.toSet() == assets.keys) { "Unreviewed JP resource scope/version" }
        originals.forEach { original ->
            val expected = assets.getValue(original.fingerprint.path.value)
            require(original.fingerprint.sha256.hex == expected.sha256 && original.bytes.size == expected.size) {
                "JP baseline differs from reviewed originals; never stack onto translated/unknown assets"
            }
        }
    }
    companion object {
        fun observed() = ReviewedNativeBaseline("jp-2-139-5-group01-review-20261020", StoryGameVersion("2.139.5", 502), mapOf(
            "3b6e743e6f41fb01a1037cb15169096d6a63198c.bin" to Asset("ScriptActionEncrypt/01",
                "a0af45a5439b555dd08535f724b19f3d00f45d93df380d1518438ffcd0104229", 1772416)
        ))
    }
}
