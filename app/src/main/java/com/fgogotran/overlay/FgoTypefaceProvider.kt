package com.fgogotran.overlay

import android.content.Context
import android.graphics.Typeface
import com.fgogotran.util.FgoLogger

object FgoTypefaceProvider {
    private const val TAG = "FGO/Font"

    private const val STORY_FONT = "fonts/FgoStory.ttf"

    @Volatile
    private var cachedTypeface: Typeface? = null

    fun storyTypeface(context: Context): Typeface {
        cachedTypeface?.let { return it }

        synchronized(this) {
            cachedTypeface?.let { return it }

            val loaded = runCatching {
                Typeface.createFromAsset(context.assets, STORY_FONT)
            }.getOrNull()
            if (loaded != null) {
                FgoLogger.info(TAG, "Loaded story font: $STORY_FONT")
                cachedTypeface = loaded
                return loaded
            }

            FgoLogger.warn(TAG, "Story font asset missing: $STORY_FONT, using system default")
            return Typeface.DEFAULT.also { cachedTypeface = it }
        }
    }
}
