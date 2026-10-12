package com.fgogotran.ui.overlay

import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.math.roundToInt

/**
 * History is game content, not an Android menu. Preserve the existing 1080p/480dpi
 * phone's 18sp/14sp appearance (54px/42px), then scale with FGO's centered viewport.
 * Android density and font-size preferences must not scale that content a second time.
 */
internal data class HistoryContentSizing private constructor(val viewportScale: Float) {
    val translationTextSizePx: Float get() = 54f * viewportScale
    val originalTextSizePx: Float get() = 42f * viewportScale

    /** Existing spacing values were dp on the baseline phone, where 1dp equals 3px. */
    fun spacingPx(baselineDp: Int): Int = (baselineDp * 3f * viewportScale).roundToInt()

    companion object {
        fun forScreen(width: Int, height: Int): HistoryContentSizing =
            HistoryContentSizing(FgoViewportGeometry.referenceScale(width, height))
    }
}
