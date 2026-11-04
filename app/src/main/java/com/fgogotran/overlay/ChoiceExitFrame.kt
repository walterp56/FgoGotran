package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Hit geometry comes from the source panel; paint geometry comes from the renderer's fixed slot. */
internal data class ChoiceRenderRegion(
    val hitBounds: Rect,
    val paintBounds: Rect,
    val selectionVisual: ChoiceSelectionVisualPlan? = null
)

internal data class ChoiceRenderResult(val bitmap: Bitmap, val choices: List<ChoiceRenderRegion>)

/** Only these owned patches survive the renderer reusing its full-screen bitmap. */
internal class ChoiceExitFrame private constructor(
    val width: Int,
    val height: Int,
    val bounds: List<Rect>,
    private val layers: List<Layer>,
    private val selectedIndex: Int,
    private var selectionPlan: ChoiceSelectionVisualPlan?
) : AutoCloseable {
    private class Layer(val bitmap: Bitmap, val destination: RectF, var plan: ChoiceSelectionVisualPlan?) {
        var background: Bitmap? = null
        var backgroundAttempted = false

        fun releaseBackground() {
            background?.recycle()
            background = null
            plan = null
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var closed = false
    private var selectionLayers: ChoiceSelectionLayers? = null
    val hasUnselected: Boolean get() = layers.size > 1
    val hasSelectedEffect: Boolean get() = selectionPlan != null

    fun draw(canvas: Canvas, elapsedMs: Long) {
        if (closed) return
        if (elapsedMs >= ChoiceExitController.SELECTION_EFFECT_MS) releaseSelection()
        val echoAlpha = ChoiceExitController.selectionAlpha(elapsedMs)
        if (echoAlpha > 0 && selectionPlan != null && selectionLayers == null) {
            try {
                selectionLayers = selectionPlan!!.capture(bounds[selectedIndex])
            } catch (_: Exception) {
                // Optional decoration failure must never cancel the already successful game tap.
                releaseSelection()
            }
        }
        layers.forEachIndexed { index, layer ->
            val selected = index == selectedIndex
            val textAlpha = ChoiceExitController.alpha(selected, elapsedMs)
            if (textAlpha == 0) {
                layer.releaseBackground()
                return@forEachIndexed
            }
            val coverAlpha = ChoiceExitController.additionalBackgroundAlpha(selected, elapsedMs)
            if (coverAlpha > 0 && !layer.backgroundAttempted && layer.plan != null) {
                layer.backgroundAttempted = true
                try {
                    layer.background = layer.plan!!.captureBackground(bounds[index])
                } catch (_: Exception) {
                    // Keep the original fade if an optional cover cannot be allocated.
                    layer.plan = null
                }
            }
            if (coverAlpha > 0) layer.background?.let { background ->
                paint.alpha = coverAlpha
                canvas.drawBitmap(background, null, layer.destination, paint)
            }
            paint.alpha = textAlpha
            val echo = selectionLayers.takeIf { index == selectedIndex && echoAlpha > 0 }
            if (echo != null) echo.draw(canvas, ChoiceExitController.selectionScale(elapsedMs), echoAlpha)
            else if (paint.alpha > 0) canvas.drawBitmap(layer.bitmap, null, layer.destination, paint)
        }
    }

    private fun releaseSelection() {
        selectionLayers?.close()
        selectionLayers = null
        selectionPlan = null
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseSelection()
        layers.forEach { it.releaseBackground(); it.bitmap.recycle() }
    }

    companion object {
        fun capture(bitmap: Bitmap, regions: List<ChoiceRenderRegion>, selectedIndex: Int): ChoiceExitFrame {
            require(selectedIndex in regions.indices)
            val layers = ArrayList<Layer>(regions.size)
            val bounds = ArrayList<Rect>(regions.size)
            try {
                for (region in regions) {
                    val box = Rect(region.paintBounds)
                    require(box.intersect(0, 0, bitmap.width, bitmap.height) && !box.isEmpty)
                    val patch = Bitmap.createBitmap(box.width(), box.height(), Bitmap.Config.ARGB_8888)
                    layers += Layer(patch, RectF(box), region.selectionVisual)
                    bounds += box
                    Canvas(patch).drawBitmap(bitmap, box, Rect(0, 0, patch.width, patch.height), null)
                }
                return ChoiceExitFrame(bitmap.width, bitmap.height, bounds, layers, selectedIndex,
                    regions[selectedIndex].selectionVisual)
            } catch (error: Throwable) {
                layers.forEach { it.bitmap.recycle() }
                throw error
            }
        }
    }
}
