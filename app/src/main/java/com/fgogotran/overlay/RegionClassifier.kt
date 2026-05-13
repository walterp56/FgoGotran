package com.fgogotran.overlay

import android.graphics.Rect
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.util.FgoLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three screen regions relevant to FGO dialogue overlays.
 */
enum class TextRegion {
    /** Bottom dialogue box where narrative/conversation text appears. */
    DIALOGUE_BOX,
    /** Character name label, positioned top-left just above the dialogue box. */
    NAME_LABEL,
    /** Player choice buttons, positioned in the middle area of the screen. */
    CHOICE_BUTTON
}

/**
 * A classified group of OCR text lines belonging to one screen region.
 * @property boundingBox the enclosing rectangle for all lines in this region (with padding)
 */
data class ClassifiedRegion(
    val region: TextRegion,
    val lines: List<OcrTextLine>,
    val boundingBox: Rect
)

/**
 * Classifies OCR text lines into [TextRegion]s based on screen position.
 *
 * ## FGO's screen layout
 * ```
 * ┌──────────────────┐  ← 0%
 * │  Character art    │
 * │  + background     │
 * │                   │    Top ~70% = art + UI chrome (HP bar, turn counter)
 * │  ┌──────────┐    │
 * │  │ CHOICE 1  │    │    Middle zone (20%–72%) = choice buttons during story branches
 * │  │ CHOICE 2  │    │
 * │  └──────────┘    │
 * │  [NAME LABEL]     │ ← Just above dialogue box, left side
 * │ ┌────────────────┐│ ← 72% (bottom 28%)
 * │ │  Dialogue text  ││    Bottom 28% = dialogue box
 * │ └────────────────┘│
 * └──────────────────┘  ← 100%
 * ```
 *
 * ## Choice button grouping
 * When there are multiple choices (e.g., 2–3 buttons stacked vertically),
 * [groupNearbyLines] groups lines within 60px vertical gap into the same button.
 * This prevents each line of a multi-line choice from being treated as separate.
 */
@Singleton
class RegionClassifier @Inject constructor() {

    companion object {
        /** Bottom portion of screen reserved for the dialogue box. */
        private const val DIALOGUE_ZONE_RATIO = 0.28f

        /**
         * Name labels appear up to 120px above the dialogue box top edge.
         * This value matches FGO's layout across common device resolutions (1080p+).
         */
        private const val NAME_LABEL_ABOVE_MARGIN = 120

        /** Name labels are in the left 40% of the screen width. */
        private const val NAME_LABEL_MAX_WIDTH_RATIO = 0.4f

        /** Name labels are short — longer text is almost certainly dialogue or UI. */
        private const val NAME_LABEL_MAX_CHARS = 10

        /** Top 20% is game art / HP bar area — any text here is UI, not story content. */
        private const val MIDDLE_ZONE_TOP_RATIO = 0.2f

        /** Top of dialogue zone marks the bottom of the middle zone. */
        private const val MIDDLE_ZONE_BOTTOM_RATIO = 0.72f

        /**
         * Maximum vertical gap (in pixels) between OCR lines to be considered part
         * of the same choice button group. FGO choice buttons are tightly stacked.
         */
        private const val CHOICE_GROUP_MAX_GAP = 60
    }

    private val tag = "RegionClassifier"

    /**
     * Classifies OCR lines into dialogue, name label, and choice button regions.
     *
     * @param lines OCR text lines from the full screenshot
     * @param screenWidth screen width in pixels
     * @param screenHeight screen height in pixels
     * @return list of classified regions (may be empty if no relevant text found)
     */
    fun classify(
        lines: List<OcrTextLine>,
        screenWidth: Int,
        screenHeight: Int
    ): List<ClassifiedRegion> {
        if (lines.isEmpty()) return emptyList()

        FgoLogger.debug(tag, "Classifying ${lines.size} lines on ${screenWidth}x${screenHeight}")

        // Zone boundaries
        val dialogueTop = (screenHeight * (1f - DIALOGUE_ZONE_RATIO)).toInt()
        val middleTop = (screenHeight * MIDDLE_ZONE_TOP_RATIO).toInt()
        val middleBottom = dialogueTop

        val dialogueLines = mutableListOf<OcrTextLine>()
        val nameLines = mutableListOf<OcrTextLine>()
        val choiceLines = mutableListOf<OcrTextLine>()

        for (line in lines) {
            val cy = line.boundingBox.centerY()
            val cx = line.boundingBox.centerX()

            when {
                // Region 1: Dialogue box — any text in the bottom 28% of screen
                cy >= dialogueTop -> dialogueLines.add(line)

                // Region 2: Name label — just above dialogue box, left side, short text
                // The character-name plate in FGO is a small box above-left of the dialogue area
                cy in (dialogueTop - NAME_LABEL_ABOVE_MARGIN)..dialogueTop &&
                cx < screenWidth * NAME_LABEL_MAX_WIDTH_RATIO &&
                line.text.length <= NAME_LABEL_MAX_CHARS -> nameLines.add(line)

                // Region 3: Choice buttons — middle zone text
                // During story branches, FGO shows 2-3 choice buttons in the middle of the screen
                cy in middleTop..middleBottom -> choiceLines.add(line)

                // Everything else: game UI (HP bar, turn counter, skill names) — skip
                else -> { /* skip */ }
            }
        }

        val regions = mutableListOf<ClassifiedRegion>()

        // Build dialogue box region (with padding for clean background clearing)
        if (dialogueLines.isNotEmpty()) {
            regions.add(ClassifiedRegion(
                region = TextRegion.DIALOGUE_BOX,
                lines = dialogueLines,
                boundingBox = computeEnclosingRect(dialogueLines).let { rect ->
                    Rect(
                        rect.left - 8,
                        rect.top - 8,
                        (rect.right + 8).coerceAtMost(screenWidth),
                        (rect.bottom + 8).coerceAtMost(screenHeight)
                    )
                }
            ))
        }

        // Build name label region
        if (nameLines.isNotEmpty()) {
            regions.add(ClassifiedRegion(
                region = TextRegion.NAME_LABEL,
                lines = nameLines,
                boundingBox = computeEnclosingRect(nameLines)
            ))
        }

        // Build choice button regions (one per button group)
        if (choiceLines.isNotEmpty()) {
            val choiceGroups = groupNearbyLines(choiceLines, CHOICE_GROUP_MAX_GAP)
            for (group in choiceGroups) {
                regions.add(ClassifiedRegion(
                    region = TextRegion.CHOICE_BUTTON,
                    lines = group,
                    boundingBox = computeEnclosingRect(group)
                ))
            }
        }

        FgoLogger.info(tag, "Classified ${regions.size} regions: " +
            "dialog=${dialogueLines.size}, name=${nameLines.size}, choice=${regions.count { it.region == TextRegion.CHOICE_BUTTON }}")
        return regions
    }

    /**
     * Computes the smallest rectangle that encloses all lines' bounding boxes.
     */
    private fun computeEnclosingRect(lines: List<OcrTextLine>): Rect {
        if (lines.isEmpty()) return Rect()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (line in lines) {
            val b = line.boundingBox
            if (b.left < left) left = b.left
            if (b.top < top) top = b.top
            if (b.right > right) right = b.right
            if (b.bottom > bottom) bottom = b.bottom
        }
        return Rect(left, top, right, bottom)
    }

    /**
     * Groups vertically adjacent text lines into clusters.
     *
     * Lines are sorted by Y position, then grouped greedily:
     * if the gap between consecutive lines is ≤ [maxGap] pixels,
     * they belong to the same group (i.e., the same choice button).
     */
    private fun groupNearbyLines(
        lines: List<OcrTextLine>,
        maxGap: Int
    ): List<List<OcrTextLine>> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.boundingBox.centerY() }
        val groups = mutableListOf<MutableList<OcrTextLine>>()
        groups.add(mutableListOf(sorted[0]))

        for (i in 1 until sorted.size) {
            val prev = groups.last().last()
            val curr = sorted[i]
            val gap = curr.boundingBox.top - prev.boundingBox.bottom
            if (gap <= maxGap) {
                // Close enough — same button
                groups.last().add(curr)
            } else {
                // Large gap — new button
                groups.add(mutableListOf(curr))
            }
        }
        return groups
    }
}
