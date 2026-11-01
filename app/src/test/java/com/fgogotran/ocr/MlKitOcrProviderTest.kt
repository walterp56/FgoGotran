package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.internal.VisionCommonRegistrar
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Execute provider wiring with a recording SDK boundary; this does not evaluate the ML model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlKitOcrProviderTest {
    @Before fun initializeImageSdk() {
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication(),
            listOf(CommonComponentRegistrar(), VisionCommonRegistrar()))
    }

    @Test fun `all ML Kit scripts suppress noise before one model call and export no dot ranges`() {
        for (script in MlKitOcrScript.entries) fixture("jp-emphasis-worlds").useBitmap { source ->
            Harness(listOf("そのような世界が他に六つ。" to Rect(20, 43, 720, 93))).use { harness ->
                val provider = MlKitOcrProvider(script, harness.recognizer)
                val result = recognize { provider.recognize(source, OcrContentKind.FIXED_JP_DIALOGUE) }
                assertEquals(1, harness.calls)
                assertNotSame(source, harness.received)
                assertTrue(checkNotNull(harness.received).isRecycled, "Temporary input leaked")
                assertFalse(source.pixels().contentEquals(checkNotNull(harness.seen).pixels()))
                assertEquals(script.engineId, result.engine)
                assertEquals("そのような世界が他に六つ。", result.fullText)
                assertTrue(result.lines.all { it.emphasisRanges.isEmpty() })
                assertFalse(source.isRecycled)
                provider.close()
            }
        }
    }

    @Test fun `readable ruby lines and their geometry remain available to the existing formatter`() {
        fixture("jp-ruby-reference").useBitmap { source ->
            val rows = listOf("私の原型とすら呼べそうで、" to Rect(20, 130, 800, 180),
                "アロイハート・オリジナル" to Rect(0, 100, 280, 120))
            Harness(rows).use { harness ->
                val result = recognize {
                    MlKitOcrProvider(MlKitOcrScript.JAPANESE, harness.recognizer)
                        .recognize(source, OcrContentKind.FIXED_JP_DIALOGUE)
                }
                assertEquals(1, harness.calls)
                assertSame(source, harness.received)
                assertContentEquals(source.pixels(), checkNotNull(harness.seen).pixels())
                assertEquals(rows.map { it.first }, result.lines.map { it.text })
                assertEquals(rows.map { it.second }, result.lines.map { it.boundingBox })
                assertTrue(result.lines.all { it.emphasisRanges.isEmpty() })
            }
        }
    }

    @Test fun `choice dots stay noise without becoming display metadata`() {
        val source = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        source.useBitmap {
            source.eraseColor(0xff000000.toInt())
            val canvas = Canvas(source)
            val paint = Paint().apply { color = 0xffffffff.toInt() }
            canvas.drawRect(50f, 45f, 250f, 85f, paint)
            for (x in listOf(70, 110, 150, 190)) canvas.drawRect(x.toFloat(), 33f, x + 4f, 37f, paint)
            Harness(listOf("進む" to Rect(50, 45, 250, 85))).use { harness ->
                val result = recognize {
                    MlKitOcrProvider(MlKitOcrScript.JAPANESE, harness.recognizer)
                        .recognizeChoices(source, listOf(Rect(0, 0, 400, 100)))
                }
                assertEquals(1, harness.calls)
                assertEquals(0xff000000.toInt(), checkNotNull(harness.seen).getPixel(71, 34))
                assertEquals(0xffffffff.toInt(), harness.seen!!.getPixel(100, 65))
                assertEquals("進む", result.lines.single().text)
                assertTrue(result.lines.single().emphasisRanges.isEmpty())
                assertTrue(harness.received!!.isRecycled)
            }
        }
    }

    @Test fun `general OCR retains original input and text without introducing annotations`() {
        fixture("jp-emphasis-worlds").useBitmap { source ->
            Harness(listOf("普通の文字。" to Rect(20, 43, 720, 93))).use { harness ->
                val result = recognize { MlKitOcrProvider(recognizer = harness.recognizer).recognize(source) }
                assertSame(source, harness.received)
                assertEquals("普通の文字。", result.fullText)
                assertTrue(result.lines.single().emphasisRanges.isEmpty())
                assertFalse(source.isRecycled)
            }
        }
    }

    @Test fun `failed ML Kit task releases owned cleanup bitmap and retains caller input`() {
        fixture("jp-emphasis-worlds").useBitmap { source ->
            Harness(emptyList(), IllegalStateException("synthetic recognizer failure")).use { harness ->
                val error = assertFailsWith<IllegalStateException> {
                    recognize { MlKitOcrProvider(recognizer = harness.recognizer)
                        .recognize(source, OcrContentKind.DIALOGUE) }
                }
                assertEquals("synthetic recognizer failure", error.message)
                assertEquals(1, harness.calls)
                assertTrue(harness.received!!.isRecycled)
                assertFalse(source.isRecycled)
            }
        }
    }

    private class Harness(rows: List<Pair<String, Rect>>, error: Exception? = null) : AutoCloseable {
        var calls = 0
        var seen: Bitmap? = null
        var received: Bitmap? = null
        private val response = Text(rows.joinToString("\n") { it.first }, rows.map { (text, box) ->
            val corners = listOf(Point(box.left, box.top), Point(box.right, box.top),
                Point(box.right, box.bottom), Point(box.left, box.bottom))
            val line = Text.Line(text, box, corners, "ja", Matrix(), emptyList<Text.Element>(), .98f, 0f)
            Text.TextBlock(text, box, corners, "ja", Matrix(), listOf(line))
        })
        val recognizer = Proxy.newProxyInstance(TextRecognizer::class.java.classLoader,
            arrayOf(TextRecognizer::class.java)) { _, method, args ->
            when (method.name) {
                "process" -> {
                    calls++
                    received = checkNotNull((args!![0] as InputImage).bitmapInternal)
                    seen = received!!.copy(Bitmap.Config.ARGB_8888, false)
                    if (error == null) Tasks.forResult(response) else Tasks.forException<Text>(error)
                }
                "close" -> null
                "toString" -> "RecordingTextRecognizer"
                else -> error("Unexpected recognizer method: ${method.name}")
            }
        } as TextRecognizer
        override fun close() { seen?.recycle() }
    }

    private fun recognize(block: suspend () -> OcrResult): OcrResult = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { block() } }
        shadowOf(Looper.getMainLooper()).idle()
        pending.await().getOrThrow()
    }

    private fun fixture(name: String): Bitmap = checkNotNull(javaClass.getResourceAsStream("/ocr/$name.png")).use {
        checkNotNull(BitmapFactory.decodeStream(it))
    }
    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) = try { block(this) } finally { recycle() }
}
