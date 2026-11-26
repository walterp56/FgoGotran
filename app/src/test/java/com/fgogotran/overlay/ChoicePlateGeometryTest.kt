package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The overlay clears the plate's own fill before it draws a translated choice.
 *
 * Measured on story screenshots (2340x1080): the plate fill runs 452–1889 horizontally and
 * 258–377 in the 250–385 slot, centred on the game viewport rather than on the wider slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChoicePlateGeometryTest {
    private val topSlot = RectF(430f, 250f, 1900f, 385f)

    @Test
    fun `the cover is the measured plate fill, not an inset rectangle`() {
        val fill = ChoicePlateGeometry.fill(topSlot, 2340, 1080)
        assertEquals(451.0, fill.left.toDouble(), 1.5)
        assertEquals(258.0, fill.top.toDouble(), 0.5)
        assertEquals(1889.0, fill.right.toDouble(), 1.5)
        assertEquals(377.0, fill.bottom.toDouble(), 0.5)
    }

    @Test
    fun `the plate outline cuts its top-left and bottom-right corners`() {
        val fill = ChoicePlateGeometry.fill(topSlot, 2340, 1080)
        val points = ChoicePlateGeometry.outlinePoints(topSlot, 2340, 1080)
        // 32 px chamfers at scale 1: the top edge starts 32 px in, the bottom edge ends 32 px
        // early, so the cover can never paint over the artwork beside those two corners.
        assertEquals(
            listOf(
                fill.left + 32f, fill.top,
                fill.right, fill.top,
                fill.right, fill.bottom - 32f,
                fill.right - 32f, fill.bottom,
                fill.left, fill.bottom,
                fill.left, fill.top + 32f
            ),
            points.toList()
        )
        val bounds = RectF()
        ChoicePlateGeometry.fillPath(topSlot, 2340, 1080).computeBounds(bounds, true)
        assertEquals(fill.left.toDouble(), bounds.left.toDouble(), 0.5)
        assertEquals(fill.top.toDouble(), bounds.top.toDouble(), 0.5)
        assertEquals(fill.right.toDouble(), bounds.right.toDouble(), 0.5)
        assertEquals(fill.bottom.toDouble(), bounds.bottom.toDouble(), 0.5)
    }

    @Test
    fun `the fill follows the viewport centre instead of the slot centre`() {
        // The slot grid sits 5 px left of the viewport centre, so a slot-centred cover would
        // leave a strip of the plate's patterned left end uncovered.
        val shiftedSlot = RectF(491f, 250f, 1961f, 385f)
        val fill = ChoicePlateGeometry.fill(topSlot, 2340, 1080)
        val shifted = ChoicePlateGeometry.fill(shiftedSlot, 2340, 1080)
        assertEquals(1170.0, fill.centerX().toDouble(), 0.5)
        assertEquals(fill.left.toDouble(), shifted.left.toDouble(), 0.1)
        assertEquals(fill.right.toDouble(), shifted.right.toDouble(), 0.1)
    }

    @Test
    fun `the fill scales with the screen`() {
        val slot = FgoViewportLayout.regionsForScreen(1280, 720).choiceSlotLayouts[1][0]
        val scale = 720f / 1080f
        val fill = ChoicePlateGeometry.fill(RectF(slot), 1280, 720)
        assertEquals(640.0, fill.centerX().toDouble(), 0.5)
        assertEquals(1438.0 * scale.toDouble(), fill.width().toDouble(), 1.0)
        assertEquals(slot.top + 8.0 * scale, fill.top.toDouble(), 0.5)
        assertEquals(slot.bottom - 8.0 * scale, fill.bottom.toDouble(), 0.5)
    }

    @Test
    fun `every choice slot shares one plate width`() {
        // The three-choice layout puts its middle plate at 343–478 instead of 250–385.
        val middleSlot = RectF(430f, 343f, 1900f, 478f)
        val top = ChoicePlateGeometry.fill(topSlot, 2340, 1080)
        val middle = ChoicePlateGeometry.fill(middleSlot, 2340, 1080)
        assertEquals(top.left.toDouble(), middle.left.toDouble(), 0.1)
        assertEquals(top.right.toDouble(), middle.right.toDouble(), 0.1)
        assertEquals(351.0, middle.top.toDouble(), 0.5)
        assertEquals(470.0, middle.bottom.toDouble(), 0.5)
    }

    @Test
    fun `the fill stays inside its slot so the plate border survives`() {
        val fill = ChoicePlateGeometry.fill(topSlot, 2340, 1080)
        assertTrue(fill.left > topSlot.left)
        assertTrue(fill.right < topSlot.right)
        assertTrue(fill.top > topSlot.top)
        assertTrue(fill.bottom < topSlot.bottom)
        assertTrue(fill.width() < topSlot.width())
    }

    @Test
    fun `filling the outline leaves the cut corners untouched`() {
        val bitmap = Bitmap.createBitmap(2340, 1080, Bitmap.Config.ARGB_8888)
        val fill = ChoicePlateGeometry.fill(topSlot, 2340, 1080)
        Canvas(bitmap).drawPath(
            ChoicePlateGeometry.fillPath(topSlot, 2340, 1080),
            Paint().apply { color = Color.BLACK }
        )
        // Artwork beside the two cut corners stays transparent, so nothing is blocked there.
        assertEquals(0, Color.alpha(bitmap.getPixel((fill.left + 4).toInt(), (fill.top + 4).toInt())))
        assertEquals(0, Color.alpha(bitmap.getPixel((fill.right - 4).toInt(), (fill.bottom - 4).toInt())))
        // The square corners and the middle are opaque.
        assertEquals(255, Color.alpha(bitmap.getPixel((fill.left + 4).toInt(), (fill.bottom - 4).toInt())))
        assertEquals(255, Color.alpha(bitmap.getPixel(fill.centerX().toInt(), fill.centerY().toInt())))
        bitmap.recycle()
    }

    @Test
    fun `both choice render paths clear the plate outline`() {
        val renderer = listOf(
            File("src/main/java/com/fgogotran/overlay/OverlayRenderer.kt"),
            File("app/src/main/java/com/fgogotran/overlay/OverlayRenderer.kt")
        ).first(File::isFile).readText().replace("\r\n", "\n")
        assertEquals(2, Regex("ChoicePlateGeometry\\.fillPath\\(").findAll(renderer).count())
        assertEquals(2, Regex("canvas\\.drawPath\\(clearPath, choiceClearPaint\\)").findAll(renderer).count())
        // The old fixed inset rectangle is what must never cover a choice plate again.
        assertFalse(renderer.contains("47f * scale"))
    }
}
