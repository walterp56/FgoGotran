package com.fgogotran.util

import android.graphics.Bitmap
import android.graphics.Rect

internal fun Bitmap.framePixels() = FramePixelReader(width, height) { pixels, bounds ->
    getPixels(pixels, 0, bounds.width, bounds.left, bounds.top, bounds.width, bounds.height)
}

internal fun Rect.pixelBounds() = FramePixelReader.Bounds(left, top, right, bottom)

internal fun FramePixelReader.read(bounds: Rect) = read(bounds.pixelBounds())
