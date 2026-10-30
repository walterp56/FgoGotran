package com.fgogotran.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import com.fgogotran.ocr.DialoguePunctuationRecovery.Bounds
import com.fgogotran.ocr.DialoguePunctuationRecovery.Line
import com.fgogotran.ocr.DialoguePunctuationRecovery.Token

/** Rare, repeated ※ glyphs only. Reuses the existing component scan; never requests OCR retries. */
internal object DialogueReferenceMarkRecovery {
    data class Run(val glyphs: List<Bounds>) {
        val bounds: Bounds = glyphs.reduce(Bounds::union)
    }

    data class Plan(val runs: List<Run> = emptyList()) {
        /** The four dots belong to ※, not to the ellipsis/period recovery layer. */
        fun punctuationComponents(components: List<DialogueAnnotationCleaner.Component>) =
            if (runs.isEmpty()) components else components.filterNot { component ->
                runs.any { run -> run.glyphs.any { glyph ->
                    component.left >= glyph.left && component.right <= glyph.right &&
                        component.top >= glyph.top && component.bottom <= glyph.bottom
                } }
            }
    }

    data class Result(val lines: List<Line>, val recoveredSymbols: Int)

    fun plan(
        pixels: IntArray,
        width: Int,
        height: Int,
        components: List<DialogueAnnotationCleaner.Component>,
        lines: List<Line>
    ): Plan {
        if (width <= 0 || height <= 0 || pixels.size.toLong() < width.toLong() * height || lines.isEmpty()) return Plan()
        val heights = lines.map { it.bounds.height }.filter { it > 0 }.sorted()
        val referenceHeight = heights.getOrNull(heights.size / 2) ?: return Plan()
        // A detached X-shaped component is sparse and square. Ordinary dots, ruby and filled
        // masks fail this cheap gate before any local pixel inspection takes place.
        val anchors = components.filter { component ->
            component.width >= max(8, (referenceHeight * 0.55f).roundToInt()) &&
                component.height in (referenceHeight * 0.55f).roundToInt()..(referenceHeight * 1.25f).roundToInt() &&
                component.width.toFloat() / component.height in 0.8f..1.25f &&
                component.fillRatio in 0.09f..0.34f &&
                lines.any { line -> line.text.any(Char::isLetterOrDigit) &&
                    line.bounds.height >= referenceHeight * 0.65f &&
                    abs(line.bounds.centerY - component.centerY) <= referenceHeight * 0.4f }
        }.sortedBy { it.left }
        if (anchors.size < 2) return Plan()
        // An isolated footnote ※ is deliberately left to ordinary recognition.
        val candidates = anchors.filter { anchor -> anchors.any { other ->
            other !== anchor && adjacent(anchor, other)
        } }.mapNotNull { cross -> verifyGlyph(pixels, width, height, cross) }
        if (candidates.size < 2) return Plan()
        val rows = mutableListOf<MutableList<Bounds>>()
        candidates.sortedWith(compareBy({ it.centerY }, { it.left })).forEach { glyph ->
            val row = rows.firstOrNull { abs(it.first().centerY - glyph.centerY) <= glyph.height * 0.12f }
            if (row == null) rows += mutableListOf(glyph) else row += glyph
        }
        val runs = mutableListOf<Run>()
        rows.forEach { row ->
            var current = mutableListOf<Bounds>()
            row.sortedBy { it.left }.forEach { glyph ->
                val previous = current.lastOrNull()
                if (previous != null && !adjacent(previous, glyph)) {
                    if (current.size >= 2) runs += Run(current.toList())
                    current = mutableListOf()
                }
                current += glyph
            }
            if (current.size >= 2) runs += Run(current.toList())
        }
        return Plan(runs)
    }

    /** Uses real CTC positions or whole, nonoverlapping OCR boxes on either side of a run. */
    fun recover(lines: List<Line>, plan: Plan): Result {
        if (plan.runs.isEmpty()) return Result(lines, 0)
        val output = lines.toMutableList()
        var recovered = 0
        for (run in plan.runs) {
            val glyphWidth = run.glyphs.map { it.width }.average().toFloat()
            val indexes = output.indices.filter { index ->
                val line = output[index]
                line.bounds.height >= run.bounds.height * 0.65f &&
                    abs(line.bounds.centerY - run.bounds.centerY) <= run.bounds.height * 0.4f &&
                    horizontalGap(line.bounds, run.bounds) <= glyphWidth * 0.8f
            }.sortedBy { output[it].bounds.left }
            if (indexes.isEmpty()) continue
            val selected = indexes.map(output::get)
            // Overlapping boxes have no unambiguous text ownership.
            if (selected.zipWithNext().any { (left, right) -> left.bounds.right > right.bounds.left }) continue
            val tokens = mutableListOf<Token>()
            var reliable = true
            for (line in selected) {
                val positioned = line.tokens.takeIf { source ->
                    source.isNotEmpty() && source.joinToString("") { it.text } == line.text &&
                        source.all { it.centerX.isFinite() && it.centerX >= line.bounds.left && it.centerX <= line.bounds.right } &&
                        source.zipWithNext().all { (a, b) -> a.centerX <= b.centerX }
                }
                if (positioned != null) {
                    tokens += positioned
                } else if (line.bounds.right <= run.bounds.left || line.bounds.left >= run.bounds.right) {
                    // This entire detected box is on one side; no equal-character-width guessing.
                    tokens += Token(line.text, line.bounds.centerX)
                } else {
                    reliable = false
                    break
                }
            }
            if (!reliable) continue
            val inside = tokens.filter { it.centerX >= run.bounds.left && it.centerX <= run.bounds.right }
            // Only a geometrically verified symbol region may replace OCR's symbol-like errors.
            if (inside.any { token -> token.text.any { it !in "※xX×✕*＊・･…⋯.．·• " } }) continue
            val merged = (tokens.filterNot { it in inside } + run.glyphs.map { Token("※", it.centerX) })
                .sortedBy(Token::centerX)
            val text = merged.joinToString("") { it.text }
            if (text == selected.joinToString("") { it.text }) continue
            val bounds = selected.fold(run.bounds) { accumulated, line -> accumulated.union(line.bounds) }
            val confidence = minOf(0.9f, selected.minOf { it.confidence })
            indexes.sortedDescending().forEach { output.removeAt(it) }
            output += Line(text, bounds, confidence, merged)
            recovered += run.glyphs.size
        }
        return Result(output.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })), recovered)
    }

    private fun verifyGlyph(
        pixels: IntArray, width: Int, height: Int, cross: DialogueAnnotationCleaner.Component
    ): Bounds? {
        val w = cross.width.toFloat()
        val h = cross.height.toFloat()
        val radius = max(1, (minOf(w, h) * 0.035f).roundToInt())
        // Both diagonal strokes must actually exist. A plus/米/asterisk is not accepted merely
        // because its outer bounds resemble a reference mark.
        val steps = listOf(0.15f, 0.30f, 0.5f, 0.70f, 0.85f)
        for (reverse in listOf(false, true)) {
            if (steps.count { fraction ->
                hasInkNear(pixels, width, height,
                    cross.left + w * fraction, cross.top + h * (if (reverse) 1f - fraction else fraction), radius)
            } < 4) return null
        }
        val dotHalfWidth = max(2, (w * 0.14f).roundToInt())
        val dotHalfHeight = max(2, (h * 0.14f).roundToInt())
        val dots = listOf(0f to -0.42f, -0.42f to 0f, 0.42f to 0f, 0f to 0.42f).map { (dx, dy) ->
            Bounds((cross.centerX + dx * w).roundToInt() - dotHalfWidth,
                (cross.centerY + dy * h).roundToInt() - dotHalfHeight,
                (cross.centerX + dx * w).roundToInt() + dotHalfWidth,
                (cross.centerY + dy * h).roundToInt() + dotHalfHeight)
        }
        if (dots.any { dot -> dot.left < 0 || dot.top < 0 || dot.right > width || dot.bottom > height ||
                inkFraction(pixels, width, dot) < 0.45f }) return null
        // Include full dot extents so punctuation recovery cannot claim their small components.
        val margin = max(1, (minOf(w, h) * 0.04f).roundToInt())
        return dots.fold(Bounds(cross.left, cross.top, cross.right, cross.bottom), Bounds::union).let {
            Bounds((it.left - margin).coerceAtLeast(0), (it.top - margin).coerceAtLeast(0),
                (it.right + margin).coerceAtMost(width), (it.bottom + margin).coerceAtMost(height))
        }
    }

    private fun adjacent(a: DialogueAnnotationCleaner.Component, b: DialogueAnnotationCleaner.Component): Boolean =
        adjacent(Bounds(a.left, a.top, a.right, a.bottom), Bounds(b.left, b.top, b.right, b.bottom))

    private fun adjacent(a: Bounds, b: Bounds): Boolean {
        val averageWidth = (a.width + b.width) / 2f
        return abs(a.centerY - b.centerY) <= max(a.height, b.height) * 0.12f &&
            a.width.toFloat() / b.width in 0.8f..1.25f && a.height.toFloat() / b.height in 0.8f..1.25f &&
            abs(a.centerX - b.centerX) in averageWidth * 1.05f..averageWidth * 1.85f
    }

    private fun hasInkNear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float, radius: Int): Boolean {
        for (yy in max(0, y.roundToInt() - radius)..minOf(height - 1, y.roundToInt() + radius)) {
            for (xx in max(0, x.roundToInt() - radius)..minOf(width - 1, x.roundToInt() + radius)) {
                if (pixels[yy * width + xx].isInk()) return true
            }
        }
        return false
    }

    private fun inkFraction(pixels: IntArray, width: Int, bounds: Bounds): Float {
        var count = 0
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            if (pixels[y * width + x].isInk()) count++
        }
        return count.toFloat() / (bounds.width * bounds.height)
    }

    private fun Int.isInk() = FgoStoryTextPalette.isDialogueInk(this shr 16 and 255, this shr 8 and 255, this and 255)
    private fun horizontalGap(a: Bounds, b: Bounds) = maxOf(0, a.left - b.right, b.left - a.right)
}
