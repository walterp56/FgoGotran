package com.fgogotran.overlay

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
 * Chinese keeps the exact v3.1.2 formulas. English keeps the later top nudge and the fixed
 * clear-box anchor, so an English-only adjustment can no longer move the Chinese layout.
 */
internal object DialogueRenderGeometry {
    const val TEXT_TOP_INSET = 48f
    const val TEXT_TOP_NUDGE_PX = 10f
    const val TEXT_BOTTOM_INSET = 12f

    const val DYNAMIC_DIALOGUE_HORIZONTAL_PADDING = 34f
    const val DYNAMIC_DIALOGUE_LEFT_PADDING = 30f
    const val DYNAMIC_DIALOGUE_VERTICAL_PADDING = 18f
    const val DYNAMIC_DIALOGUE_TEXT_HORIZONTAL_INSET = 24f
    const val DYNAMIC_DIALOGUE_TEXT_LEFT_INSET = 14f
    const val DYNAMIC_DIALOGUE_TEXT_VERTICAL_INSET = 4f

    fun textArea(
        panel: DialogueRect,
        scale: Float,
        englishTarget: Boolean
    ): DialogueRect {
        val topNudge = if (englishTarget) TEXT_TOP_NUDGE_PX else 0f
        return DialogueRect(
            left = panel.left + DialogueReferenceGeometry.TEXT_LEFT_INSET * scale,
            top = panel.top + TEXT_TOP_INSET * scale - topNudge,
            right = panel.right - DialogueReferenceGeometry.TEXT_RIGHT_INSET * scale,
            bottom = panel.bottom - TEXT_BOTTOM_INSET * scale
        )
    }

    fun clearBox(
        panel: DialogueRect,
        textArea: DialogueRect,
        originalBounds: DialogueRect?,
        textWidth: Float,
        textBlockHeight: Float,
        hasRiskyTrailingText: Boolean,
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
            top = anchorTop
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
        val clearRightForRiskyTail = if (hasRiskyTrailingText) {
            textArea.right
        } else {
            clearRightFallback
        }

        return boundedRect(
            left = left,
            top = top,
            right = maxOf(sourceRight, textArea.left + textWidth + clearInsetX, clearRightForRiskyTail),
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
