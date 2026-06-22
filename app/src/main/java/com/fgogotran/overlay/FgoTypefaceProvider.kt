package com.fgogotran.overlay

import android.content.Context
import android.graphics.Typeface
import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger

object FgoTypefaceProvider {
    private const val TAG = "FGO/Font"

    private const val STORY_FONT_SIMPLIFIED = "fonts/FgoStory.ttf"
    private const val STORY_FONT_TRADITIONAL = "fonts/FgoStory_td.otf"

    @Volatile
    private var cachedSimplifiedTypeface: Typeface? = null

    @Volatile
    private var cachedTraditionalTypeface: Typeface? = null

    fun storyTypeface(
        context: Context,
        targetLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    ): Typeface {
        val normalizedLocale = SettingsRepository.normalizeTargetChineseLocale(targetLocale)
        val cached = when (normalizedLocale) {
            SettingsRepository.TARGET_LOCALE_TRADITIONAL -> cachedTraditionalTypeface
            else -> cachedSimplifiedTypeface
        }
        cached?.let { return it }

        synchronized(this) {
            val lockedCached = when (normalizedLocale) {
                SettingsRepository.TARGET_LOCALE_TRADITIONAL -> cachedTraditionalTypeface
                else -> cachedSimplifiedTypeface
            }
            lockedCached?.let { return it }

            val fontAsset = when (normalizedLocale) {
                SettingsRepository.TARGET_LOCALE_TRADITIONAL -> STORY_FONT_TRADITIONAL
                else -> STORY_FONT_SIMPLIFIED
            }

            val loaded = runCatching {
                Typeface.createFromAsset(context.assets, fontAsset)
            }.getOrNull()
            if (loaded != null) {
                FgoLogger.info(TAG, "Loaded story font: $fontAsset")
                cacheTypeface(normalizedLocale, loaded)
                return loaded
            }

            FgoLogger.warn(TAG, "Story font asset missing: $fontAsset, using system default")
            return Typeface.DEFAULT.also { cacheTypeface(normalizedLocale, it) }
        }
    }

    private fun cacheTypeface(targetLocale: String, typeface: Typeface) {
        when (targetLocale) {
            SettingsRepository.TARGET_LOCALE_TRADITIONAL -> cachedTraditionalTypeface = typeface
            else -> cachedSimplifiedTypeface = typeface
        }
    }
}
