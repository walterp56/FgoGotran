package com.fgogotran.overlay

/** Render-only dialogue geometry. Row positions never depend on whether ruby is present. */
internal object BilingualDialogueGeometry {
    const val TRANSLATION_TO_ORIGINAL_GAP = 4f
    const val BEFORE_RUBY_GAP = 2f
    const val RUBY_BASE_GAP = 3f

    // The legacy band is 830..1055 in the 1080 px story viewport. Stay below the
    // name band (ending at 828), above the blue panel's bottom edge, and left of its controls.
    fun renderPlane(legacy: DialogueRect, scale: Float): DialogueRect = legacy.copy(
        top = legacy.top - 2f * scale,
        bottom = legacy.bottom + 2f * scale
    )

    /** All values are relative to a row origin and measured once for its font/size. */
    data class TextMetrics(
        val baseline: Float,
        val inkTop: Float,
        val inkBottom: Float
    )

    data class Metrics(
        val translation: TextMetrics,
        val original: TextMetrics,
        val rubyHeight: Float,
        val rubyGap: Float,
        val translationToOriginalGap: Float,
        val beforeRubyGap: Float
    )

    data class Row(
        val original: Boolean,
        val lineIndex: Int,
        val baseline: Float,
        val inkTop: Float,
        val inkBottom: Float,
        val minimumRubyTop: Float
    )

    data class Layout(val rows: List<Row>, val bottom: Float)

    /**
     * Place rows by their visible ink gaps, reserving normal ruby above every translated row.
     * Use the same measured ink extents for fitting, background coverage and painting.
     * [minimumTop] is the render-plane top relative to the established first-row anchor.
     */
    fun layout(
        translationLineCount: Int,
        originalLineCount: Int,
        metrics: Metrics,
        minimumTop: Float
    ): Layout {
        val rows = mutableListOf<Row>()
        var origin = 0f
        var previousBottom = minimumTop
        repeat(maxOf(translationLineCount, originalLineCount).coerceAtLeast(0)) { index ->
            fun appendRow(original: Boolean, font: TextMetrics) {
                val minimumRubyTop = if (rows.isEmpty()) {
                    minimumTop
                } else {
                    previousBottom + if (original) metrics.translationToOriginalGap else metrics.beforeRubyGap
                }
                val headroom = if (original) 0f else metrics.rubyHeight + metrics.rubyGap
                val nextOrigin = minimumRubyTop + headroom - font.inkTop
                origin = if (rows.isEmpty()) maxOf(0f, nextOrigin) else nextOrigin
                rows += Row(original, index, origin + font.baseline,
                    origin + font.inkTop, origin + font.inkBottom, minimumRubyTop)
                previousBottom = origin + font.inkBottom
            }
            if (index < translationLineCount) appendRow(false, metrics.translation)
            if (index < originalLineCount) appendRow(true, metrics.original)
        }
        return Layout(rows, if (rows.isEmpty()) 0f else previousBottom)
    }
}
