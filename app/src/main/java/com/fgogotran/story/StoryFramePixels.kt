package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.util.FramePixelReader

/** Per-capture pixels and compact choice observation. Never shared with a retry/freshness frame. */
internal class StoryFramePixels(val pixels: FramePixelReader) {
    private var choiceBounds: List<FgoReferenceRect>? = null
    private var choiceFrame: StoryTapHandoff.ChoiceFrame? = null

    fun choices(buttons: List<FgoReferenceRect>): StoryTapHandoff.ChoiceFrame {
        val bounds = buttons.sortedBy { it.top }
        if (bounds == choiceBounds) choiceFrame?.let { return it }
        val region = if (bounds.isNotEmpty()) pixels.read(FramePixelReader.Bounds(
            bounds.minOf { it.left }, bounds.minOf { it.top },
            bounds.maxOf { it.right }, bounds.maxOf { it.bottom }
        )) else null
        val frame = StoryTapHandoff.sampleChoices(pixels.width, pixels.height, bounds) { x, y ->
            checkNotNull(region).getPixel(x, y)
        }
        choiceBounds = bounds
        choiceFrame = frame
        return frame
    }

    fun clear() {
        choiceBounds = null
        choiceFrame = null
        pixels.clear()
    }
}
