package com.fgogotran.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One positioned ML Kit symbol, or one estimated character when symbol boxes are unavailable. */
internal data class MlKitTextFragment(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
) {
    val centerX: Float
        get() = (left + right) / 2f
}

/** Android-free representation of an ML Kit line so the recovery policy can be unit tested. */
internal data class MlKitDetectedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
    val fragments: List<MlKitTextFragment>
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
}

internal data class MlKitSolidMaskRecoveryChange(
    val before: String,
    val after: String,
    val associatedLineCount: Int,
    val recoveredMaskCount: Int,
    val recoveredSeparatorCount: Int
)

internal data class MlKitSolidMaskRecoveryResult(
    val lines: List<MlKitDetectedLine>,
    val changes: List<MlKitSolidMaskRecoveryChange>
)

/**
 * Rebuilds an ML Kit line around FGO's fixed filled-square geometry.
 *
 * ML Kit can classify a few squares while splitting and reordering the rest of the
 * visual line. Geometry supplies only the known ■/・ characters; recognized text
 * outside that span is preserved, so this does not guess missing Japanese text.
 */
internal object MlKitSolidMaskRecovery {
    private const val SOLID_MASK_TEXT = "■"
    private const val SEPARATOR_TEXT = "・"
    private const val MIN_VERTICAL_OVERLAP_RATIO = 0.45f
    private const val MAX_CENTER_DIFFERENCE_RATIO = 0.45f
    private const val MAX_HORIZONTAL_GAP_SIDE_RATIO = 2.5f
    private const val MAX_HORIZONTAL_GAP_HEIGHT_RATIO = 2f
    private const val MASK_SPAN_MARGIN_SIDE_RATIO = 0.20f

    fun recover(
        lines: List<MlKitDetectedLine>,
        maskRows: List<PaddleSolidMaskRow>
    ): MlKitSolidMaskRecoveryResult {
        if (lines.isEmpty() || maskRows.isEmpty()) {
            return MlKitSolidMaskRecoveryResult(lines = lines, changes = emptyList())
        }

        val consumedLineIndexes = mutableSetOf<Int>()
        val recoveredLines = mutableListOf<MlKitDetectedLine>()
        val changes = mutableListOf<MlKitSolidMaskRecoveryChange>()

        for (row in maskRows.sortedWith(compareBy(PaddleSolidMaskRow::top, PaddleSolidMaskRow::left))) {
            val associatedLines = lines.withIndex()
                .filter { (index, line) ->
                    index !in consumedLineIndexes && lineBelongsToMaskRow(line, row)
                }
            if (associatedLines.isEmpty()) continue

            val expectedGeometry = geometryEvents(row)
                .joinToString(separator = "", transform = PositionedText::text)
            if (associatedLines.size == 1 && expectedGeometry in associatedLines.single().value.text) {
                continue
            }

            val margin = max(1, (row.averageSide * MASK_SPAN_MARGIN_SIDE_RATIO).roundToInt())
            val exclusionLeft = row.left - margin
            val exclusionRight = row.right + margin
            val retainedFragments = associatedLines
                .flatMap { it.value.fragments }
                .filterNot { fragment ->
                    (fragment.text.isNotEmpty() && fragment.text.all { it == SOLID_MASK_TEXT.single() }) ||
                        fragment.centerX in exclusionLeft.toFloat()..exclusionRight.toFloat()
                }
                .distinctBy { fragment ->
                    FragmentIdentity(fragment.text, fragment.left, fragment.top, fragment.right, fragment.bottom)
                }

            val events = buildList {
                retainedFragments.forEach { fragment ->
                    add(PositionedText(fragment.centerX, fragment.text, fragment.confidence))
                }
                addAll(geometryEvents(row))
            }.sortedBy(PositionedText::centerX)
            val recoveredText = events.joinToString(separator = "", transform = PositionedText::text).trim()
            val previousText = associatedLines
                .map { it.value }
                .sortedBy(MlKitDetectedLine::left)
                .joinToString(separator = "", transform = MlKitDetectedLine::text)
            val previousMeaningfulChars = previousText.count(Char::isLetterOrDigit)
            val recoveredMeaningfulChars = recoveredText.count(Char::isLetterOrDigit)
            val keepsRecognizedText = previousMeaningfulChars == 0 ||
                recoveredMeaningfulChars >= max(1, previousMeaningfulChars / 2)
            val hasRecognizedContent = retainedFragments.any { fragment ->
                fragment.text.any { char ->
                    !char.isWhitespace() && char != SOLID_MASK_TEXT.single() && char != SEPARATOR_TEXT.single()
                }
            }
            if (recoveredText.isBlank() || expectedGeometry !in recoveredText ||
                !keepsRecognizedText || !hasRecognizedContent
            ) {
                continue
            }

            val bounds = unionBounds(row, associatedLines.map { it.value })
            val confidence = events.map(PositionedText::confidence)
                .average()
                .toFloat()
                .coerceIn(0f, 1f)
            associatedLines.forEach { consumedLineIndexes += it.index }
            recoveredLines += MlKitDetectedLine(
                text = recoveredText,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                confidence = confidence,
                fragments = retainedFragments
            )
            changes += MlKitSolidMaskRecoveryChange(
                before = previousText,
                after = recoveredText,
                associatedLineCount = associatedLines.size,
                recoveredMaskCount = row.masks.size,
                recoveredSeparatorCount = row.separators.size
            )
        }

        val finalLines = buildList {
            lines.forEachIndexed { index, line ->
                if (index !in consumedLineIndexes) add(line)
            }
            addAll(recoveredLines)
        }.sortedWith(compareBy(MlKitDetectedLine::top, MlKitDetectedLine::left))
        return MlKitSolidMaskRecoveryResult(lines = finalLines, changes = changes)
    }

    private fun geometryEvents(row: PaddleSolidMaskRow): List<PositionedText> {
        return buildList {
            row.masks.forEach { mask ->
                add(PositionedText(mask.centerX, SOLID_MASK_TEXT, mask.confidence))
            }
            row.separators.forEach { separator ->
                add(PositionedText(separator.centerX, SEPARATOR_TEXT, separator.confidence))
            }
        }.sortedBy(PositionedText::centerX)
    }

    private fun lineBelongsToMaskRow(
        line: MlKitDetectedLine,
        row: PaddleSolidMaskRow
    ): Boolean {
        if (line.width <= 0 || line.height <= 0) return false
        val verticalOverlap = min(line.bottom, row.bottom) - max(line.top, row.top)
        val minimumHeight = min(line.height, row.height).coerceAtLeast(1)
        if (verticalOverlap < minimumHeight * MIN_VERTICAL_OVERLAP_RATIO) return false

        val lineCenterY = (line.top + line.bottom) / 2f
        val maximumCenterDifference = max(line.height, row.height) * MAX_CENTER_DIFFERENCE_RATIO
        if (abs(lineCenterY - row.centerY) > maximumCenterDifference) return false

        val horizontalGap = when {
            line.right < row.left -> row.left - line.right
            line.left > row.right -> line.left - row.right
            else -> 0
        }
        val maximumGap = max(
            row.averageSide * MAX_HORIZONTAL_GAP_SIDE_RATIO,
            line.height * MAX_HORIZONTAL_GAP_HEIGHT_RATIO
        )
        return horizontalGap <= maximumGap
    }

    private fun unionBounds(
        row: PaddleSolidMaskRow,
        lines: List<MlKitDetectedLine>
    ): IntBounds {
        return IntBounds(
            left = min(row.left, lines.minOf(MlKitDetectedLine::left)),
            top = min(row.top, lines.minOf(MlKitDetectedLine::top)),
            right = max(row.right, lines.maxOf(MlKitDetectedLine::right)),
            bottom = max(row.bottom, lines.maxOf(MlKitDetectedLine::bottom))
        )
    }

    private data class PositionedText(
        val centerX: Float,
        val text: String,
        val confidence: Float
    )

    private data class FragmentIdentity(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class IntBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
