package com.fgogotran.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlKitDialogueInputTest {
    @Test fun `real dialogue noise is removed at 1x and 2x without changing main rows`() {
        for (name in listOf("worlds", "memory")) for (scale in 1..2) {
            fixture("jp-emphasis-$name", scale).useBitmap { source ->
                val before = source.pixels()
                val started = System.nanoTime()
                MlKitDialogueInput.prepare(source, OcrContentKind.FIXED_JP_DIALOGUE).use { prepared ->
                    assertNotSame(source, prepared.bitmap)
                    assertEquals(source.width, prepared.bitmap.width)
                    assertEquals(source.height, prepared.bitmap.height)
                    assertEquals(if (name == "worlds") 12 else 22, prepared.removedDots)
                    val after = prepared.bitmap.pixels()
                    assertFalse(before.contentEquals(after))
                    val mainStart = (if (name == "worlds") 43 else 130) * scale
                    assertContentEquals(before.copyOfRange(mainStart * source.width, before.size),
                        after.copyOfRange(mainStart * source.width, after.size), "Main row changed: $name/$scale")
                    if (name == "memory") {
                        assertContentEquals(before.copyOfRange(0, 100 * scale * source.width),
                            after.copyOfRange(0, 100 * scale * source.width), "Unmarked first row changed")
                    }
                    assertContentEquals(before, source.pixels(), "Caller-owned bitmap was modified")
                    println("ML Kit noise fixture=$name scale=$scale removedDots=${prepared.removedDots} " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                }
                assertFalse(source.isRecycled)
            }
        }
    }

    @Test fun `real readable ruby and body remain pixel identical at 1x and 2x`() {
        for (scale in 1..2) fixture("jp-ruby-reference", scale).useBitmap { source ->
            val before = source.pixels()
            MlKitDialogueInput.prepare(source, OcrContentKind.FIXED_JP_DIALOGUE).use { prepared ->
                assertSame(source, prepared.bitmap)
                assertEquals(0, prepared.removedDots)
                assertContentEquals(before, prepared.bitmap.pixels())
            }
            assertFalse(source.isRecycled)
        }
    }

    @Test fun `ML Kit aligned short kana cleanup does not widen default Paddle masking`() {
        fixture("jp-emphasis-worlds", 1).useBitmap { source ->
            val default = DialogueAnnotationCleaner.clean(source.pixels(), source.width, source.height)
            assertEquals(11, default.maskedComponents)
            MlKitDialogueInput.prepare(source, OcrContentKind.DIALOGUE).use { prepared ->
                assertEquals(12, prepared.removedDots)
                val after = prepared.bitmap.pixels()
                // Main text and punctuation remain untouched even beside the recovered short kana.
                val mainStart = 43 * source.width
                assertContentEquals(source.pixels().copyOfRange(mainStart, after.size),
                    after.copyOfRange(mainStart, after.size))
            }
        }
    }

    @Test fun `a dot run beside ruby retains reading strokes main ink and punctuation`() {
        for (sample in FgoStoryTextPalette.samples) {
            val source = Bitmap.createBitmap(320, 96, Bitmap.Config.ARGB_8888)
            source.useBitmap {
                source.eraseColor(0xff1b304e.toInt())
                val canvas = Canvas(source)
                val paint = Paint().apply { color = sample.renderColor }
                for (center in listOf(40, 90, 140, 190)) {
                    canvas.drawRect(center - 4f, 10f, center + 4f, 18f, paint)
                    canvas.drawRect(center - 16f, 28f, center + 16f, 70f, paint)
                }
                // A round ruby part beside a taller stroke is not a removable emphasis dot.
                canvas.drawRect(48f, 6f, 51f, 22f, paint)
                // Main-band punctuation must also survive.
                canvas.drawRect(230f, 59f, 235f, 64f, paint)
                val before = source.pixels()
                MlKitDialogueInput.prepare(source, OcrContentKind.DIALOGUE).use { prepared ->
                    assertEquals(3, prepared.removedDots, sample.family.toString())
                    val after = prepared.bitmap.pixels()
                    for (y in 0 until source.height) for (x in 0 until source.width) {
                        if (x < 80 || x > 210 || y >= 25) {
                            assertEquals(before[y * source.width + x], after[y * source.width + x],
                                "Protected ruby/body/punctuation changed at $x,$y")
                        }
                    }
                }
            }
        }
    }

    @Test fun `general and already prepared choice input bypass dialogue cleanup`() {
        fixture("jp-emphasis-worlds", 2).useBitmap { source ->
            for (kind in listOf(OcrContentKind.GENERAL, OcrContentKind.CHOICE)) {
                MlKitDialogueInput.prepare(source, kind).use {
                    assertSame(source, it.bitmap)
                    assertEquals(0, it.removedDots)
                }
                assertFalse(source.isRecycled)
            }
        }
    }

    @Test fun `owned cleanup image is released once without recycling caller image`() {
        fixture("jp-emphasis-worlds", 1).useBitmap { source ->
            val prepared = MlKitDialogueInput.prepare(source, OcrContentKind.DIALOGUE)
            assertFalse(prepared.bitmap.isRecycled)
            prepared.close()
            prepared.close()
            assertTrue(prepared.bitmap.isRecycled)
            assertFalse(source.isRecycled)
        }
    }

    private fun fixture(name: String, scale: Int): Bitmap {
        val bitmap = checkNotNull(javaClass.getResourceAsStream("/ocr/$name.png")).use {
            checkNotNull(BitmapFactory.decodeStream(it))
        }
        if (scale == 1) return bitmap
        return Bitmap.createScaledBitmap(bitmap, bitmap.width * scale, bitmap.height * scale, true)
            .also { bitmap.recycle() }
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) = try { block(this) } finally { recycle() }
}
