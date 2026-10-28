package com.fgogotran.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.fgogotran.ocr.OcrEngineId
import com.fgogotran.overlay.ClassifiedRegion
import com.fgogotran.overlay.DialoguePlaybackFrame
import com.fgogotran.overlay.DialoguePlaybackCharacters
import com.fgogotran.overlay.DialoguePlaybackSettings
import com.fgogotran.overlay.DialoguePlaybackTimeline
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.overlay.OverlayRenderer
import com.fgogotran.overlay.RenderInstruction
import com.fgogotran.overlay.TextRegion

/** Owns preview-only buffers; never borrows the live service's renderer or bitmap. */
internal class DialoguePlaybackPreviewScene private constructor(
    private val withRuby: DialoguePlaybackFrame,
    private val withoutRuby: DialoguePlaybackFrame,
    val crop: Rect
) : AutoCloseable {
    private val bitmapBounds = Rect(0, 0, withRuby.bitmap.width, withRuby.bitmap.height)
    private val layerBounds = RectF(crop)
    private val characterEnds = DialoguePlaybackCharacters.ends(withRuby.timeline.text)

    fun frame(includeRuby: Boolean): DialoguePlaybackFrame = if (includeRuby) withRuby else withoutRuby

    /** Speed and punctuation change the clock, never the expensive font/bitmap layout. */
    fun timeline(settings: DialoguePlaybackSettings): DialoguePlaybackTimeline =
        DialoguePlaybackTimeline(withRuby.timeline.text, characterEnds, settings)

    fun draw(canvas: Canvas, includeRuby: Boolean, visibleEnd: Int) {
        val frame = frame(includeRuby)
        canvas.save()
        try {
            canvas.translate(-crop.left.toFloat(), -crop.top.toFloat())
            // Playback replaces panel patches with SRC. Isolate those replacements so their
            // antialiased edges cannot clear the settings card's opaque blue background.
            canvas.saveLayer(layerBounds, null)
            try {
                // An explicit pixel destination prevents device density from rescaling the frame.
                canvas.drawBitmap(frame.bitmap, null, bitmapBounds, null)
                frame.drawReveal(canvas, visibleEnd)
            } finally { canvas.restore() }
        } finally { canvas.restore() }
    }

    override fun close() {
        withRuby.close()
        withoutRuby.close()
    }

    companion object {
        private const val REFERENCE_WIDTH = 1920
        private const val REFERENCE_HEIGHT = 1080
        // Show a close-up, rather than shrinking a whole 16:9 game frame into a portrait card.
        // This viewport width is shared by every language; it never changes text layout metrics.
        private const val MINIMUM_CROP_WIDTH = 800

        fun create(
            context: Context,
            example: DialoguePlaybackExample,
            locale: String,
            bilingual: Boolean
        ): DialoguePlaybackPreviewScene {
            val source = Bitmap.createBitmap(REFERENCE_WIDTH, REFERENCE_HEIGHT, Bitmap.Config.ARGB_8888)
            val renderer = OverlayRenderer(context)
            val owned = mutableListOf<DialoguePlaybackFrame>()
            try {
                val region = ClassifiedRegion(TextRegion.DIALOGUE_BOX, emptyList(),
                    FgoViewportLayout.regionsForScreen(REFERENCE_WIDTH, REFERENCE_HEIGHT).dialogueRender,
                    OcrEngineId.ML_KIT)
                fun prepare(includeRuby: Boolean): DialoguePlaybackFrame = renderer.prepareDialoguePlayback(
                    source, listOf(RenderInstruction(region, example.renderText(includeRuby), example.originalText,
                        targetLocale = locale, showOriginalText = bilingual)), DialoguePlaybackSettings()
                ).also { owned += it }
                val withRuby = prepare(true)
                val withoutRuby = prepare(false)
                // Both switch states use the same crop, so the ruby headroom never collapses.
                val crop = Rect(withRuby.panels.single().bounds).apply {
                    union(withoutRuby.panels.single().bounds)
                    right = maxOf(right, left + MINIMUM_CROP_WIDTH).coerceAtMost(REFERENCE_WIDTH)
                }
                return DialoguePlaybackPreviewScene(withRuby, withoutRuby, crop)
            } catch (error: Throwable) {
                owned.forEach { it.close() }
                throw error
            } finally {
                source.recycle()
                renderer.releaseBuffers()
            }
        }
    }
}
