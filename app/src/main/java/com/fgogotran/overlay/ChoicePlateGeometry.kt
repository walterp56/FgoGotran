package com.fgogotran.overlay

import android.graphics.Path
import android.graphics.RectF

/**
 * The opaque fill of one choice plate, which the overlay clears before drawing the translation.
 *
 * FGO centres the plate on the 1920x1080 story viewport and wraps it in a pale border, so the
 * fixed slot is wider than the plate. Measured on story screenshots: the fill runs 452–1889
 * inside the 430–1900 slot, and sits 8 px inside the slot's top and bottom edges. The fill is
 * not a rectangle: its top-left and bottom-right corners are cut at 45 degrees, so a rectangle
 * painted over the plate covers artwork outside those two corners and visibly blocks the
 * plate's shape. Clearing the measured hexagon keeps the border and the two chamfers intact.
 */
object ChoicePlateGeometry {
    /** Measured plate fill width in the 1920x1080 reference space. */
    private const val REFERENCE_WIDTH = 1438f

    /** Measured gap between the fixed slot and the plate fill at its top and bottom edge. */
    private const val VERTICAL_INSET = 8f

    /** Measured 45-degree cut on the plate's top-left and bottom-right corners. */
    private const val CORNER_CHAMFER = 32f

    /** The plate fill of [slot] in screen coordinates, as a bounding box. */
    fun fill(slot: RectF, screenWidth: Int, screenHeight: Int): RectF {
        val scale = scale(screenWidth, screenHeight)
        val halfWidth = REFERENCE_WIDTH * scale / 2f
        // The plate is centred on the game viewport, which is always horizontally centred on
        // the screen, so the slot's own centre (5 px left of it) is not the reference.
        val centerX = screenWidth.coerceAtLeast(0) / 2f
        return RectF(
            centerX - halfWidth,
            slot.top + VERTICAL_INSET * scale,
            centerX + halfWidth,
            slot.bottom - VERTICAL_INSET * scale
        )
    }

    /**
     * The plate outline as x,y pairs: a hexagon whose top-left and bottom-right corners are
     * cut at 45 degrees, matching the shape the game draws.
     */
    fun outlinePoints(slot: RectF, screenWidth: Int, screenHeight: Int): FloatArray {
        val fill = fill(slot, screenWidth, screenHeight)
        val chamfer = CORNER_CHAMFER * scale(screenWidth, screenHeight)
        return floatArrayOf(
            fill.left + chamfer, fill.top,
            fill.right, fill.top,
            fill.right, fill.bottom - chamfer,
            fill.right - chamfer, fill.bottom,
            fill.left, fill.bottom,
            fill.left, fill.top + chamfer
        )
    }

    /** [outlinePoints] as a closed path the renderer can fill and clip with. */
    fun fillPath(slot: RectF, screenWidth: Int, screenHeight: Int): Path {
        val points = outlinePoints(slot, screenWidth, screenHeight)
        return Path().apply {
            moveTo(points[0], points[1])
            var index = 2
            while (index < points.size) {
                lineTo(points[index], points[index + 1])
                index += 2
            }
            close()
        }
    }

    private fun scale(screenWidth: Int, screenHeight: Int): Float =
        FgoViewportLayout.viewportScaleForScreen(screenWidth, screenHeight)
}
