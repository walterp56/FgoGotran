package com.fgogotran.overlay

import com.fgogotran.data.SettingsRepository

/**
 * Pure geometry for the speaker-name plate.
 *
 * The name text size is fixed per target (CN 56, EN 64) and never shrinks. The plate keeps the
 * detected blue-plate width when that is wide enough, and expands to the right only when the
 * fixed-size name needs more room (option A).
 */
internal object NameRenderGeometry {
    const val TEXT_SIZE_CN = 56f
    const val TEXT_SIZE_EN = 64f

    fun textSize(targetLanguage: String): Float =
        if (SettingsRepository.normalizeTargetLanguage(targetLanguage) ==
            SettingsRepository.TARGET_LANGUAGE_ENGLISH
        ) {
            TEXT_SIZE_EN
        } else {
            TEXT_SIZE_CN
        }

    fun plateRight(
        basePlateRight: Float,
        textLeft: Float,
        textWidth: Float,
        rightInset: Float,
        maxRight: Float
    ): Float {
        val requiredRight = textLeft + textWidth + rightInset
        return minOf(maxOf(basePlateRight, requiredRight), maxRight)
    }
}
