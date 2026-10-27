package com.fgogotran.overlay

/** Ruby layout uses the list's existing margins; the Canvas still clips to the list viewport. */
internal object HistoryRubyViewport {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun textBounds(
        viewportWidth: Int,
        viewportHeight: Int,
        textOriginX: Float,
        textOriginY: Float,
        rubyOverhang: Float
    ): Bounds = Bounds(
        left = -textOriginX,
        // Keep the font budget stable while scrolling. The viewport clips passing annotations
        // instead of progressively shrinking their font as the body approaches the top edge.
        top = -rubyOverhang,
        right = viewportWidth - textOriginX,
        bottom = viewportHeight - textOriginY
    )
}
