package com.fgogotran.ocr

import com.fgogotran.ocr.DialogueAnnotationCleaner.Component
import com.fgogotran.ocr.DialogueRubyLayout.Box
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plans supplementary ruby recognition from the cleaner's retained, full-resolution components. */
internal object DialogueRubyGroupRecovery {
    data class Group(
        val bounds: Box,
        val originalBoxIndices: List<Int>,
        val componentCount: Int,
        val expectedBand: Boolean
    )

    data class Reading(val text: String, val confidence: Float)

    /** A punctuation merge may have changed an original box. Do not append a duplicate reading. */
    fun hasUnownedOverlap(bounds: Box, owned: List<Box>, existing: List<Box>): Boolean = existing.any {
        it !in owned && it.height <= bounds.height * 1.8f && it.centerY <= bounds.bottom && intersects(bounds, it)
    }

    fun plan(components: List<Component>, boxes: List<Box>, width: Int, height: Int): List<Group> {
        if (width <= 0 || height <= 0 || components.isEmpty()) return emptyList()
        // Expanded detector boxes are useful anchors, not exact glyph baselines. Tall merged
        // ruby/main boxes are deliberately not used to manufacture a second reading.
        val mains = boxes.filter {
            it.height >= height * 0.16f && it.height <= height * 0.42f && it.width >= it.height * 0.8f
        }
        return planWithAnchors(components, boxes, width, height, mains) { inExpectedBand(it, height) }
    }

    /** Choice anchors are relative to each button's main ink, never the dialogue fixed-Y bands. */
    fun planChoices(
        analysis: List<ChoicePunctuationRecovery.ButtonAnalysis>, boxes: List<Box>, width: Int, height: Int
    ): List<Group> = analysis.flatMap { button ->
        val band = button.mainBand ?: return@flatMap emptyList()
        val indices = boxes.indices.filter { index ->
            val box = boxes[index]
            box.left >= button.interior.left && box.right <= button.interior.right &&
                box.top >= button.interior.top && box.bottom <= button.interior.bottom
        }
        val localBoxes = indices.map(boxes::get)
        val mains = localBoxes.filter {
            it.height >= band.height * 0.65f && it.height <= band.height * 1.45f &&
                abs(it.centerY - band.centerY) <= band.height * 0.35f && it.width >= it.height * 0.8f
        }
        val components = button.components.map {
            Component(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.pixelCount)
        }
        planWithAnchors(components, localBoxes, width, height, mains) { centerY ->
            centerY < band.top && band.top - centerY <= band.height
        }.map { group ->
            // A recognition inset touching the button guard must be clipped, not cause the
            // complete reading (especially its upper dakuten) to be dropped.
            group.copy(bounds = Box(
                group.bounds.left.coerceAtLeast(button.interior.left),
                group.bounds.top.coerceAtLeast(button.interior.top),
                group.bounds.right.coerceAtMost(button.interior.right),
                group.bounds.bottom.coerceAtMost(button.interior.bottom)
            ), originalBoxIndices = group.originalBoxIndices.map(indices::get))
        }
    }.distinctBy { it.bounds }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

    private fun planWithAnchors(
        components: List<Component>, boxes: List<Box>, width: Int, height: Int, mains: List<Box>,
        hinted: (Float) -> Boolean
    ): List<Group> {
        if (mains.isEmpty()) return emptyList()
        val upperByMain = components.mapNotNull { component ->
            val main = mains.filter { candidate ->
                component.height <= candidate.height * 0.55f && component.width <= candidate.height * 1.4f &&
                    component.centerX >= candidate.left - candidate.height * 0.5f &&
                    component.centerX <= candidate.right + candidate.height * 0.5f &&
                    mains.none { occupied -> component.centerY >= occupied.top + occupied.height * 0.1f &&
                        component.centerY <= occupied.bottom && component.centerX in occupied.left.toFloat()..occupied.right.toFloat() } &&
                    isAbove(component, candidate, hinted(component.centerY))
            }.minByOrNull { abs(it.top - component.bottom) } ?: return@mapNotNull null
            main to component
        }.groupBy({ it.first }, { it.second })
        val groups = mutableListOf<Group>()
        for (main in mains) {
            val reference = main.height
            val upper = upperByMain[main].orEmpty()
            val seeds = upper.filter { isReadableSeed(it, reference, hinted(it.centerY)) }
            // Pixel components are strokes, not OCR word boxes. One kana's strokes can have
            // very different centres; use the main-row scale to align this annotation band.
            val rows = mutableListOf<MutableList<Component>>()
            seeds.sortedBy { it.centerY }.forEach { seed ->
                val row = rows.lastOrNull()
                if (row != null && abs(seed.centerY - row.first().centerY) <= reference * 0.35f) row += seed
                else rows += mutableListOf(seed)
            }
            for (rowSeeds in rows) {
                val side = rowSeeds.map { maxOf(it.width, it.height) }.sorted().let { it[it.size / 2] }
                val top = rowSeeds.minOf { it.top }
                val bottom = rowSeeds.maxOf { it.bottom }
                // A thin long vowel is part of the reading, even across a large character gap.
                // Round dots are attached only locally below; they cannot bridge two readings.
                val strokes = upper.filter { component ->
                    component !in seeds && component.width >= side * 0.45f &&
                        component.height <= side * 0.35f &&
                        component.centerY in (top + side * 0.15f)..(bottom - side * 0.15f)
                }
                val row = (rowSeeds + strokes).distinct().sortedBy { it.left }
                val positiveGaps = row.zipWithNext().mapNotNull { (first, second) ->
                    val gap = second.left - first.right
                    val strokePair = first.width < first.height * 0.6f || second.width < second.height * 0.6f
                    // Tiny intra-kana stroke gaps must not make a genuine large character gap
                    // look like the boundary between two separate readings (e.g. spaced そ と).
                    gap.takeIf { it > 0 && !(strokePair && gap <= reference * 0.18f) }
                }.sorted()
                val typicalGap = positiveGaps.getOrNull((positiveGaps.size - 1).coerceAtLeast(0) / 2) ?: 0
                val maxGap = minOf(reference * 1.8f, maxOf(reference * 0.85f, side * 2.25f, typicalGap * 2.5f))
                val anchors = boxes.indices.filter { index ->
                    boxes[index].height <= reference * 0.72f &&
                        rowSeeds.count { contains(boxes[index], box(it), 1) } >= 2
                }
                val runs = mutableListOf<MutableList<Component>>()
                for (component in row) {
                    val previous = runs.lastOrNull()?.lastOrNull()
                    val firstAnchor = previous?.let { part -> anchors.firstOrNull { contains(boxes[it], box(part), 1) } }
                    val secondAnchor = anchors.firstOrNull { contains(boxes[it], box(component), 1) }
                    val separateAnchors = firstAnchor != null && secondAnchor != null && firstAnchor != secondAnchor
                    if (previous == null || component.left - previous.right > maxGap || separateAnchors) {
                        runs += mutableListOf(component)
                    } else {
                        runs.last() += component
                    }
                }
                for (run in runs) {
                    if (run.none { it in rowSeeds }) continue
                    if (run.all { isRoundDot(it, reference, 0.5f) }) continue
                    val ink = union(run.map(::box))
                    val marks = upper.filter { component ->
                        component !in row && component.top >= top - maxOf(side * 0.4f, reference * 0.08f) &&
                            component.bottom <= bottom + maxOf(side * 0.15f, reference * 0.06f) &&
                            horizontalGap(ink, box(component)) <= maxOf(side * 0.6f, reference * 0.15f)
                    }
                    val parts = run + marks
                    val inkBounds = union(parts.map(::box))
                    if (inkBounds.height > reference * 0.65f) continue
                    // A complete existing box needs no additional recognition, regardless of Y.
                    if (boxes.any { existing ->
                            existing.height <= reference * 0.72f && parts.all { contains(existing, box(it), 1) }
                        }) continue
                    val padding = maxOf(1, (reference * 0.035f).roundToInt())
                    val bounds = Box(
                        (inkBounds.left - padding).coerceAtLeast(0),
                        (inkBounds.top - padding).coerceAtLeast(0),
                        (inkBounds.right + padding).coerceAtMost(width),
                        (inkBounds.bottom + padding).coerceAtMost(height)
                    )
                    val originals = boxes.indices.filter { index ->
                        boxes[index].height <= reference * 0.72f && contains(bounds, boxes[index], padding) &&
                            intersects(inkBounds, boxes[index])
                    }
                    // Do not duplicate a readable detector box extending beyond this group.
                    val crossingBox = boxes.indices.any { index ->
                        index !in originals && boxes[index].height <= reference * 0.72f &&
                            intersects(inkBounds, boxes[index])
                    }
                    if (!crossingBox) groups += Group(
                        bounds, originals, parts.size, hinted(inkBounds.centerY)
                    )
                }
            }
        }
        return groups.distinctBy { it.bounds }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** An accepted group must retain every previously accepted fragment, in reading order. */
    fun rejectionReason(recovered: Reading?, originals: List<Reading>): String? {
        if (recovered == null) return "no_recognition"
        val text = recovered.text.filterNot(Char::isWhitespace)
        if (!recovered.confidence.isFinite() || recovered.confidence < 0.5f) return "low_confidence"
        if (text.length !in 1..28 || text.none {
                (it in '\u3040'..'\u30ff' && it.isLetter() && it != 'ー') || it in '\u4e00'..'\u9fff'
            }) {
            return "not_readable_ruby"
        }
        if (text.any { !it.isLetterOrDigit() && it !in "ー・･＝=-－" }) return "uncertain_marks"
        if (originals.any { recovered.confidence < it.confidence - 0.1f }) return "weaker_than_original"
        var cursor = 0
        for (original in originals) {
            for (char in original.text.filterNot(Char::isWhitespace)) {
                val index = text.indexOf(char, cursor)
                if (index < 0) return "original_fragment_not_retained"
                cursor = index + 1
            }
        }
        return null
    }

    private fun isAbove(component: Component, main: Box, hinted: Boolean): Boolean {
        val overlap = main.height * if (hinted) 0.28f else 0.18f
        val maxGap = main.height * if (hinted) 0.95f else 0.75f
        return component.centerY < main.top + main.height * 0.06f &&
            component.bottom <= main.top + overlap && main.top - component.bottom <= maxGap
    }

    private fun isReadableSeed(component: Component, reference: Int, hinted: Boolean): Boolean {
        val heightRatio = if (hinted) 0.055f else 0.12f
        if (component.height < maxOf(3f, reference * heightRatio)) return false
        return !isRoundDot(component, reference, 0.65f)
    }

    private fun isRoundDot(component: Component, reference: Int, minimumFill: Float): Boolean {
        val side = maxOf(component.width, component.height)
        return side <= reference * 0.24f && component.fillRatio >= minimumFill &&
            side <= minOf(component.width, component.height) * 1.6f
    }

    private fun inExpectedBand(centerY: Float, cropHeight: Int): Boolean {
        // Marked FGO screenshots: ruby near y=848..881 and 931..967 in the 1080p
        // reference layout; the exact dialogue crop starts at 833 and is 219px tall.
        // These are scaled search hints, never clipping boundaries or acceptance gates.
        val referenceY = centerY * 219f / cropHeight
        return referenceY in 10f..52f || referenceY in 92f..140f
    }

    private fun box(component: Component) = Box(component.left, component.top, component.right, component.bottom)
    private fun union(boxes: List<Box>) = Box(
        boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom }
    )
    private fun contains(outer: Box, inner: Box, tolerance: Int) =
        inner.left >= outer.left - tolerance && inner.right <= outer.right + tolerance &&
            inner.top >= outer.top - tolerance && inner.bottom <= outer.bottom + tolerance
    private fun intersects(first: Box, second: Box) =
        first.left < second.right && second.left < first.right &&
            first.top < second.bottom && second.top < first.bottom
    private fun horizontalGap(first: Box, second: Box) =
        maxOf(0, first.left - second.right, second.left - first.right)
}
