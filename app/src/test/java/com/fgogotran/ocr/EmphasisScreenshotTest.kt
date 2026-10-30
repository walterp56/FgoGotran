package com.fgogotran.ocr

import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real 1080p FGO crops supplied with the missing-dot report; no API calls. */
class EmphasisScreenshotTest {
    @Test fun `real single and double row dot evidence survives annotation cleaning`() {
        val root=generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it,"settings.gradle.kts").isFile }
        val out=File(root,"app/build/reports/emphasis-ocr").apply { mkdirs() }
        val recognized=checkNotNull(javaClass.getResourceAsStream("/ocr/jp-emphasis-positions.json")).use {
            Json.parseToJsonElement(it.bufferedReader(Charsets.UTF_8).readText()).jsonObject
        }
        for (name in listOf("worlds","memory")) {
            val image=checkNotNull(javaClass.getResourceAsStream("/ocr/jp-emphasis-$name.png")).use {
                Class.forName("javax.imageio.ImageIO").getMethod("read",InputStream::class.java).invoke(null,it)
            }
            val type=image.javaClass
            val width=type.getMethod("getWidth").invoke(image) as Int
            val height=type.getMethod("getHeight").invoke(image) as Int
            val pixels=type.getMethod("getRGB",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE,
                IntArray::class.java,Integer.TYPE,Integer.TYPE).invoke(image,0,0,width,height,null,0,width) as IntArray
            val cleanup=DialogueAnnotationCleaner.clean(pixels,width,height,true)
            assertEquals(if(name=="worlds")12 else 22,cleanup.emphasisDots.size,name)
            val lines=recognized.getValue(name).jsonArray.map { value ->
                val row=value.jsonObject
                val text=row.getValue("text").jsonPrimitive.content
                val positions=row.getValue("tokens").jsonArray.map { element -> element.jsonObject.let {
                    OcrCharacterPosition(it.getValue("text").jsonPrimitive.content,it.getValue("centerX").jsonPrimitive.float)
                } }
                val ranges=EmphasisRangeMapper.map(text,positions,cleanup.emphasisDots,
                    row.getValue("top").jsonPrimitive.int,row.getValue("bottom").jsonPrimitive.int)
                text to ranges.map { text.substring(it.start,it.endExclusive) }
            }
            if(name=="worlds") assertEquals(listOf("そのような世界が他に六う"),lines.single().second)
            else {
                assertTrue(lines.first().second.isEmpty(),"Unmarked first row acquired dots")
                assertEquals(listOf("オレの記憶した5分間は","何があろうと残り続ける"),lines.last().second)
            }
            File(out,"$name.json").writeText(buildJsonObject {
                put("width",width);put("height",height)
                put("dots",buildJsonArray { cleanup.emphasisDots.forEach { dot -> add(buildJsonObject {
                    put("x",dot.centerX);put("mainTop",dot.mainTop);put("mainBottom",dot.mainBottom)
                }) } })
            }.toString())
            // Portable raster for the separate offline ONNX probe; not needed by app runtime.
            File(out,"$name.ppm").outputStream().buffered().use { stream ->
                stream.write("P6\n$width $height\n255\n".toByteArray())
                cleanup.pixels.forEach { pixel -> stream.write(pixel ushr 16 and 255);stream.write(pixel ushr 8 and 255);stream.write(pixel and 255) }
            }
        }
    }
}
