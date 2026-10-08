package com.fgogotran.util

import android.graphics.Rect
import android.view.View

/** Protect only the small edge handle from Back, even when a translated window covers it. */
internal fun View.setFloatingHandleGestureExclusion(screenBounds: Rect?) {
    val exclusions = if (screenBounds == null || width <= 0 || height <= 0) {
        emptyList()
    } else {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val localBounds = Rect(screenBounds).apply { offset(-location[0], -location[1]) }
        if (localBounds.intersect(0, 0, width, height)) listOf(localBounds) else emptyList()
    }
    systemGestureExclusionRects = exclusions
}
