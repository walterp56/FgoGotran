package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The choice plate's own background, which the overlay has to put back after it clears the plate.
 *
 * FGO draws the plate as a dark diamond lattice that starts at the left end and fades out; the
 * facets are unevenly shaded and about 60 px wide, so it is copied from the game rather than
 * modelled: [R.drawable.choice_plate_field] is that field, taken from a story screenshot with the
 * Japanese line blanked out. Without it a cleared plate becomes a flat black bar that no longer
 * looks like the game's own plate.
 *
 * Readability comes first. The texture is the game's field clamped to [MAX_LEVEL] (about 10%
 * grey), which is exactly the contrast the game itself gives its white choice text, and the text
 * is always painted after this layer.
 */
object ChoicePlateBackground {
    /** The brightest a texture pixel may be; the shipped texture is clamped to this. */
    const val MAX_LEVEL = 26

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draws [field] over an already cleared plate, clipped to the plate outline. */
    fun draw(canvas: Canvas, path: Path, fill: RectF, field: Bitmap?) {
        if (field == null || field.isRecycled) return
        if (fill.width() <= 0f || fill.height() <= 0f) return
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(field, null, fill, bitmapPaint)
        canvas.restore()
    }
}
