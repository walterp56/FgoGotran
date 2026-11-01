package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.GameAssetPath
import com.fgogotran.chapter.codec.*
import java.io.File
import org.junit.Test

class ExtraKeyDebugTest {
    private fun show(file: String, hex: String) {
        val bytes = hex.toByteArray(Charsets.US_ASCII)
        val key = ByteArray(32); key[0] = bytes[0]
        val iv = ByteArray(32); bytes.copyInto(iv, 1, 1, bytes.size)
        val data = File("../story_assets/jp-originals/full-20261022/$file").readBytes()
        val plain = FgoJpFormatProfile.containerCodec(hex).decode(OriginalAsset.capture(GameAssetPath.parse(file), data)).valueOrThrow()
        println("$file extra ${plain.scripts.size}")
    }

    @Test fun default9404() {
        val file = "e0f834a37b523d47b24f8c515e9ddceeff4f54aa.bin"
        val data = File("../story_assets/jp-originals/full-20261022/$file").readBytes()
        val plain = FgoJpFormatProfile.containerCodec().decode(OriginalAsset.capture(GameAssetPath.parse(file), data)).valueOrThrow()
        println("$file default ${plain.scripts.size}")
    }

    @Test fun extra9408() {
        show("cee0cada6bf71ab7467f2da06385540a7ce6b69f.bin", "0LXxsF1jEe3wa28cXi5MDfELRqMT8kZR")
    }
}