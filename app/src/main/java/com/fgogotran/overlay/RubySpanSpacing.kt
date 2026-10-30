package com.fgogotran.overlay

import kotlin.math.ceil

/** Render-only spacing for readings that would otherwise overlap the preceding annotation. */
internal object RubySpanSpacing {
    fun padLine(
        value: String,
        minimumLeft: Float,
        clearance: Float,
        spaceAdvance: Float,
        baseEndpoint: (String, Int, Int) -> Float,
        readingWidth: (String) -> Float
    ): String {
        val ruby = RubyRenderText.parse(value)
        if (ruby.annotations.isEmpty() || spaceAdvance <= 0f) return value
        val body = StringBuilder(ruby.body)
        var inserted = 0
        var previousRight = minimumLeft
        ruby.annotations.filterNot { it.emphasis }.forEach { annotation ->
            val start = annotation.start + inserted
            val end = annotation.end + inserted
            var endpoint = baseEndpoint(body.toString(), start, end)
            val missing = previousRight - (endpoint - readingWidth(annotation.reading))
            if (missing > 0f) {
                val spaces = ceil(missing / spaceAdvance).toInt().coerceIn(1, 4096)
                body.insert(start, " ".repeat(spaces))
                inserted += spaces
                endpoint = baseEndpoint(body.toString(), start + spaces, end + spaces)
            }
            previousRight = endpoint + clearance
        }
        return ruby.annotate(body.toString())
    }
}
