package com.fgogotran.overlay

import com.fgogotran.data.SettingsRepository

/** Plain float rectangle so the dialogue geometry stays free of Android framework classes. */
internal data class DialogueRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Single-language story dialogue geometry.
 *
 * Both targets share the v3.1.2 text top; English keeps a slightly larger row pitch (1.46) and
 * the fixed clear-box anchor, while Chinese keeps the exact v3.1.2 formulas.
 */
internal object DialogueRenderGeometry {
    // Keep the existing countdown safeguard. A dash alone no longer forces a full-row clear:
    // recovered punctuation is already included in originalBounds below.
    private val COUNTDOWN_CLEAR_RISK = Regex("""(?:[0-9\uFF10-\uFF19][ \t\u3000]+){2,}[0-9\uFF10-\uFF19]\s*[-ー‐‑‒–—―−─━－一]*\s*$""")

    fun isCountdownTail(sourceTail: String): Boolean = COUNTDOWN_CLEAR_RISK.containsMatchIn(sourceTail)

    const val TEXT_TOP_INSET = 48f
    const val TEXT_BOTTOM_INSET = 12f

    const val LINE_HEIGHT_MULTIPLIER_CN = 1.57f
    const val LINE_HEIGHT_MULTIPLIER_EN = 1.46f

    /** English clear box extends upward so the first-line ruby above the text is covered. */
    const val RUBY_COVER_TOP_PX = 10f

    const val DYNAMIC_DIALOGUE_HORIZONTAL_PADDING = 34f
    const val DYNAMIC_DIALOGUE_LEFT_PADDING = 30f
    const val DYNAMIC_DIALOGUE_VERTICAL_PADDING = 18f
    const val DYNAMIC_DIALOGUE_TEXT_HORIZONTAL_INSET = 24f
    const val DYNAMIC_DIALOGUE_TEXT_LEFT_INSET = 14f
    const val DYNAMIC_DIALOGUE_TEXT_VERTICAL_INSET = 4f

    fun textArea(
        panel: DialogueRect,
        scale: Float
    ): DialogueRect = DialogueRect(
        left = panel.left + DialogueReferenceGeometry.TEXT_LEFT_INSET * scale,
        top = panel.top + TEXT_TOP_INSET * scale,
        right = panel.right - DialogueReferenceGeometry.TEXT_RIGHT_INSET * scale,
        bottom = panel.bottom - TEXT_BOTTOM_INSET * scale
    )

    fun lineHeightMultiplier(targetLanguage: String): Float =
        if (SettingsRepository.normalizeTargetLanguage(targetLanguage) ==
            SettingsRepository.TARGET_LANGUAGE_ENGLISH
        ) {
            LINE_HEIGHT_MULTIPLIER_EN
        } else {
            LINE_HEIGHT_MULTIPLIER_CN
        }

    fun clearBox(
        panel: DialogueRect,
        textArea: DialogueRect,
        originalBounds: DialogueRect?,
        textWidth: Float,
        textBlockHeight: Float,
        hasCountdownTail: Boolean,
        scale: Float,
        englishTarget: Boolean
    ): DialogueRect {
        val clearInsetX = DYNAMIC_DIALOGUE_TEXT_HORIZONTAL_INSET * scale
        val clearLeftInsetX = DYNAMIC_DIALOGUE_TEXT_LEFT_INSET * scale
        val clearInsetY = DYNAMIC_DIALOGUE_TEXT_VERTICAL_INSET * scale
        val sourcePaddingX = DYNAMIC_DIALOGUE_HORIZONTAL_PADDING * scale
        val sourceLeftPaddingX = DYNAMIC_DIALOGUE_LEFT_PADDING * scale
        val sourcePaddingY = DYNAMIC_DIALOGUE_VERTICAL_PADDING * scale

        val textBottom = textArea.top + textBlockHeight
        val anchorLeft = textArea.left - clearLeftInsetX
        val anchorTop = textArea.top - clearInsetY - 12f * scale

        val left: Float
        val top: Float
        val clearRightFallback: Float
        val sourceRight: Float
        val sourceBottom: Float
        if (englishTarget) {
            left = anchorLeft
            top = anchorTop - RUBY_COVER_TOP_PX * scale
            clearRightFallback = anchorLeft
            sourceRight = originalBounds?.right?.plus(sourcePaddingX) ?: anchorLeft
            sourceBottom = originalBounds?.bottom?.plus(sourcePaddingY) ?: anchorTop
        } else {
            val sourceLeft = originalBounds?.left?.minus(sourceLeftPaddingX) ?: textArea.left
            val sourceTop = originalBounds?.top?.minus(sourcePaddingY) ?: textArea.top
            left = minOf(sourceLeft, textArea.left - clearLeftInsetX)
            top = minOf(sourceTop, textArea.top - clearInsetY) - 12f * scale
            clearRightFallback = textArea.left
            sourceRight = originalBounds?.right?.plus(sourcePaddingX) ?: textArea.left
            sourceBottom = originalBounds?.bottom?.plus(sourcePaddingY) ?: textArea.top
        }
        val clearRightForCountdown = if (hasCountdownTail) {
            textArea.right
        } else {
            clearRightFallback
        }

        return boundedRect(
            left = left,
            top = top,
            right = maxOf(sourceRight, textArea.left + textWidth + clearInsetX, clearRightForCountdown),
            bottom = maxOf(sourceBottom, textBottom + clearInsetY),
            bounds = panel
        )
    }

    private fun boundedRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bounds: DialogueRect
    ): DialogueRect {
        val safeLeft = left.coerceIn(bounds.left, (bounds.right - 1f).coerceAtLeast(bounds.left))
        val safeTop = top.coerceIn(bounds.top, (bounds.bottom - 1f).coerceAtLeast(bounds.top))
        val safeRight = right.coerceAtMost(bounds.right).coerceAtLeast(safeLeft + 1f)
        val safeBottom = bottom.coerceAtMost(bounds.bottom).coerceAtLeast(safeTop + 1f)
        return DialogueRect(safeLeft, safeTop, safeRight, safeBottom)
    }
}
