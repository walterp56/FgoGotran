package com.fgogotran.overlay

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.fgogotran.data.SettingsRepository

/**
 * Baseline/height helpers shared by the canvas overlays.
 *
 * The Japanese/Chinese story font ships a hhea ascent that matches its CJK glyph top (0.88 em), so
 * the legacy `top - fontMetrics.ascent` formula places text correctly. The English font keeps the
 * same cap height but ships a much larger ascent (1.16 em) and descent (0.288 em), which drew every
 * English overlay text lower, and inflated the measured block height by about 0.45 em.
 *
 * For the English target these helpers use the painted ink box instead. Every other target keeps
 * the exact legacy expression, so Simplified/Traditional Chinese rendering cannot change.
 */
internal object VisualTextMetrics {
    private const val INK_PROBE = "Hg"

    fun isEnglishTarget(targetLanguage: String): Boolean =
        SettingsRepository.normalizeTargetLanguage(targetLanguage) ==
            SettingsRepository.TARGET_LANGUAGE_ENGLISH

    /**
     * Baseline that puts the painted ink box top at [top].
     *
     * [text] is only inspected for the English target; every other target keeps the metric formula.
     */
    fun baselineForTop(
        paint: Paint,
        top: Float,
        targetLanguage: String,
        text: String = INK_PROBE
    ): Float {
        if (!isEnglishTarget(targetLanguage)) return top - paint.fontMetrics.ascent
        val bounds = inkBounds(paint, text) ?: return top - paint.fontMetrics.ascent
        return top - bounds.top
    }

    /**
     * Speaker name plate: the English target centres the painted name inside the plate, while every
     * other target keeps the tuned `top - ascent + offset` formula from the previous release.
     */
    fun baselineForName(
        paint: Paint,
        text: String,
        area: RectF,
        legacyOffset: Float,
        targetLanguage: String
    ): Float {
        if (!isEnglishTarget(targetLanguage)) {
            return area.top - paint.fontMetrics.ascent + legacyOffset
        }
        val bounds = inkBounds(paint, text)
            ?: return area.top - paint.fontMetrics.ascent + legacyOffset
        return area.centerY() - (bounds.top + bounds.bottom) / 2f
    }

    /** Height of the painted text block; legacy metric height for every non-English target. */
    fun blockHeight(
        paint: Paint,
        lineHeight: Float,
        lineCount: Int,
        targetLanguage: String
    ): Float {
        if (lineCount <= 0) return 0f
        val metrics = paint.fontMetrics
        val legacyHeight = (metrics.descent - metrics.ascent).coerceAtLeast(0f)
        val glyphHeight = if (isEnglishTarget(targetLanguage)) {
            val bounds = inkBounds(paint, INK_PROBE)
            if (bounds != null) (bounds.bottom - bounds.top).toFloat() else legacyHeight
        } else {
            legacyHeight
        }
        return glyphHeight.coerceAtLeast(0f) + (lineCount - 1) * lineHeight
    }

    private fun inkBounds(paint: Paint, text: String): Rect? {
        if (text.isEmpty()) return null
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() == 0 && bounds.height() == 0) return null
        return bounds
    }
}
