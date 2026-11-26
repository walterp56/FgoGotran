package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The cleared choice plate must look like the game's own plate again.
 *
 * FGO draws the plate as a dark diamond lattice that fades out to the right. The overlay clears
 * the plate to black to hide the Japanese line and then draws the shipped field texture back,
 * so the translated plate keeps the game's look — but the field must never become a background
 * the translated text has to fight with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChoicePlateBackgroundTest {
    private val slot = RectF(430f, 250f, 1900f, 385f)

    private fun assetFile(): File = listOf(
        File("src/main/res/drawable-nodpi/choice_plate_field.png"),
        File("app/src/main/res/drawable-nodpi/choice_plate_field.png")
    ).first(File::isFile)

    private fun field(): Bitmap {
        val decoded = BitmapFactory.decodeFile(assetFile().path)
        assertNotNull("choice_plate_field.png is missing", decoded)
        return requireNotNull(decoded)
    }

    private fun draw(field: Bitmap): Pair<Bitmap, RectF> {
        val bitmap = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        val fill = ChoicePlateGeometry.fill(slot, 2340, 1080)
        val path = ChoicePlateGeometry.fillPath(slot, 2340, 1080)
        val canvas = Canvas(bitmap)
        canvas.drawPath(path, Paint().apply { color = Color.BLACK })
        ChoicePlateBackground.draw(canvas, path, fill, field)
        return bitmap to fill
    }

    @Test
    fun `the shipped field is a texture, never something to read over`() {
        val field = field()
        var brightest = 0
        var textured = 0
        for (y in 0 until field.height) {
            for (x in 0 until field.width) {
                val level = maxOf(
                    Color.red(field.getPixel(x, y)),
                    Color.green(field.getPixel(x, y)),
                    Color.blue(field.getPixel(x, y))
                )
                brightest = maxOf(brightest, level)
                if (level >= 4) textured++
            }
        }
        assertTrue("the field texture is empty", textured > field.width)
        assertTrue(
            "the field reached $brightest, above the ${ChoicePlateBackground.MAX_LEVEL} readability cap",
            brightest <= ChoicePlateBackground.MAX_LEVEL
        )
        field.recycle()
    }

    @Test
    fun `the field keeps the game's fade towards the right end`() {
        val field = field()
        fun meanLevel(fromRatio: Float, toRatio: Float): Double {
            val from = (field.width * fromRatio).toInt()
            val to = (field.width * toRatio).toInt()
            val top = (field.height * 0.2f).toInt()
            val bottom = (field.height * 0.8f).toInt()
            var sum = 0.0
            var count = 0
            for (y in top until bottom) {
                for (x in from until to) {
                    val pixel = field.getPixel(x, y)
                    sum += maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    count++
                }
            }
            return sum / count
        }
        val left = meanLevel(0f, 0.15f)
        val right = meanLevel(0.75f, 1f)
        assertTrue("the field is not brightest at the left end", left > right * 2)
        assertTrue("the right end is not plain black ($right)", right <= 3.0)
        field.recycle()
    }

    @Test
    fun `drawing the field keeps the plate outline`() {
        val field = field()
        val (bitmap, fill) = draw(field)
        // Both cut corners stay untouched, so the texture never leaks onto the artwork.
        assertEquals(0, Color.alpha(bitmap.getPixel((fill.left + 4).toInt(), (fill.top + 4).toInt())))
        assertEquals(0, Color.alpha(bitmap.getPixel((fill.right - 4).toInt(), (fill.bottom - 4).toInt())))
        assertEquals(255, Color.alpha(bitmap.getPixel(fill.centerX().toInt(), fill.centerY().toInt())))
        // The covered plate keeps the game's field: the left end carries the texture.
        var brightest = 0
        for (y in (fill.centerY() - 20).toInt() until (fill.centerY() + 20).toInt()) {
            for (x in (fill.left + 20).toInt() until (fill.left + 160).toInt()) {
                val pixel = bitmap.getPixel(x, y)
                brightest = maxOf(brightest, Color.red(pixel))
            }
        }
        assertTrue("the left end lost the plate field", brightest >= 4)
        bitmap.recycle()
        field.recycle()
    }

    @Test
    fun `both choice render paths restore the plate field`() {
        val renderer = listOf(
            File("src/main/java/com/fgogotran/overlay/OverlayRenderer.kt"),
            File("app/src/main/java/com/fgogotran/overlay/OverlayRenderer.kt")
        ).first(File::isFile).readText().replace("\r\n", "\n")
        assertEquals(
            2,
            Regex("ChoicePlateBackground\\.draw\\(canvas, clearPath, clearBox, choicePlateField\\)")
                .findAll(renderer)
                .count()
        )
        assertTrue(renderer.contains("R.drawable.choice_plate_field"))
    }
}
