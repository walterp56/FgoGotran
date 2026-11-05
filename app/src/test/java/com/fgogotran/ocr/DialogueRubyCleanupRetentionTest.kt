package com.fgogotran.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DialogueRubyCleanupRetentionTest {
    @Test fun bundledFontReadingsSurviveCleanup() {
        val directory = listOf(File("src/main/assets/fonts"), File("app/src/main/assets/fonts")).first { it.isDirectory }
        val fontType = Class.forName("java.awt.Font")
        val colorType = Class.forName("java.awt.Color")
        val graphicsType = Class.forName("java.awt.Graphics")
        val imageType = Class.forName("java.awt.image.BufferedImage")
        val fonts = listOf("FgoStory.ttf", "FgoStory_en.ttf", "FgoStory_td.otf").map {
            fontType.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
                .invoke(null, 0, File(directory, it))
        }
        val main = "これは私の原型と重なっている。"
        listOf("わたくし", "グガぎ", "ナウクラテー", "アロイハート・オリジナル").forEach { reading ->
            val font = fonts.first { fontType.getMethod("canDisplayUpTo", String::class.java)
                .invoke(it, main + reading) as Int == -1 }
            val image = imageType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).newInstance(1000, 219, 2)
            val graphics = imageType.getMethod("createGraphics").invoke(image)
            val setColor = graphicsType.getMethod("setColor", colorType)
            try {
                setColor.invoke(graphics, colorType.getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(0x1C3454))
                graphicsType.getMethod("fillRect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(graphics, 0, 0, 1000, 219)
                setColor.invoke(graphics, colorType.getField("WHITE").get(null))
                fun draw(text: String, size: Float, x: Int, y: Int) {
                    graphicsType.getMethod("setFont", fontType).invoke(graphics,
                        fontType.getMethod("deriveFont", Float::class.javaPrimitiveType).invoke(font, size))
                    graphicsType.getMethod("drawString", String::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).invoke(graphics, text, x, y)
                }
                draw(main, 52f, 20, 115)
                draw(reading, 18f, 130, 68)
            } finally { graphicsType.getMethod("dispose").invoke(graphics) }
            val pixels = imageType.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(image, 0, 0, 1000, 219, null, 0, 1000) as IntArray
            val cleanup = DialogueAnnotationCleaner.clean(pixels, 1000, 219)
            var checkedPixels = 0
            for (y in 40 until 70) for (x in 120 until 600) {
                if (pixels[y * 1000 + x] == -1) {
                    checkedPixels++
                    assertEquals("Ruby pixel must survive: $reading at $x,$y", pixels[y * 1000 + x], cleanup.pixels[y * 1000 + x])
                }
            }
            assertTrue("Fixture must contain ruby pixels: $reading", checkedPixels > 0)
        }
    }
}
