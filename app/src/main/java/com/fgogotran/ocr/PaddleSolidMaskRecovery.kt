package com.fgogotran.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A filled-square mask recovered directly from a normalized PaddleOCR line crop.
 * Coordinates use the same pixel space as the recognition tensor.
 */
internal data class PaddleSolidMask(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
    val centerX: Float
        get() = (left + right) / 2f
    val centerY: Float
        get() = (top + bottom) / 2f

    fun scaled(scaleX: Float, scaleY: Float): PaddleSolidMask {
        val scaledLeft = (left * scaleX).roundToInt()
        val scaledTop = (top * scaleY).roundToInt()
        val scaledRight = (right * scaleX).roundToInt().coerceAtLeast(scaledLeft + 1)
        val scaledBottom = (bottom * scaleY).roundToInt().coerceAtLeast(scaledTop + 1)
        return PaddleSolidMask(
            left = scaledLeft,
            top = scaledTop,
            right = scaledRight,
            bottom = scaledBottom,
            confidence = confidence
        )
    }
}

internal data class PaddleSolidMaskSeparator(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
) {
    val centerX: Float
        get() = (left + right) / 2f

    fun scaled(scaleX: Float, scaleY: Float): PaddleSolidMaskSeparator {
        val scaledLeft = (left * scaleX).roundToInt()
        val scaledTop = (top * scaleY).roundToInt()
        val scaledRight = (right * scaleX).roundToInt().coerceAtLeast(scaledLeft + 1)
        val scaledBottom = (bottom * scaleY).roundToInt().coerceAtLeast(scaledTop + 1)
        return PaddleSolidMaskSeparator(
            left = scaledLeft,
            top = scaledTop,
            right = scaledRight,
            bottom = scaledBottom,
            confidence = confidence
        )
    }
}

/**
 * A trusted horizontal row of FGO's fixed filled-square masks.
 *
 * Unlike [PaddleSolidMaskDetector.detect], these coordinates may cover the complete
 * Paddle detection input rather than one recognition crop. A row is emitted only
 * after two adjacent, geometrically matching squares establish the mask style.
 */
internal data class PaddleSolidMaskRow(
    val masks: List<PaddleSolidMask>,
    val separators: List<PaddleSolidMaskSeparator> = emptyList()
) {
    init {
        require(masks.isNotEmpty()) { "A solid-mask row must contain at least one mask" }
    }

    val left: Int
        get() = masks.minOf(PaddleSolidMask::left)
    val top: Int
        get() = masks.minOf(PaddleSolidMask::top)
    val right: Int
        get() = masks.maxOf(PaddleSolidMask::right)
    val bottom: Int
        get() = masks.maxOf(PaddleSolidMask::bottom)
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
    val centerY: Float
        get() = (top + bottom) / 2f
    val averageSide: Float
        get() = masks.map { (it.width + it.height) / 2f }.average().toFloat()

    fun scaled(scaleX: Float, scaleY: Float): PaddleSolidMaskRow {
        return PaddleSolidMaskRow(
            masks = masks.map { it.scaled(scaleX, scaleY) },
            separators = separators.map { it.scaled(scaleX, scaleY) }
        )
    }
}

/**
 * One CTC token with its approximate horizontal position in the recognition crop.
 */
internal data class PaddlePositionedToken(
    val text: String,
    val centerX: Float,
    val confidence: Float
)

internal data class PaddleMaskMergeResult(
    val text: String,
    val confidence: Float,
    val recoveredMaskCount: Int
)

/**
 * Detects FGO's fixed filled-square masks without asking the OCR model to classify them.
 *
 * Detection is deliberately conservative: at least two adjacent, geometrically matching
 * filled squares must establish the mask style for the line. Once established, isolated
 * squares with the same size and baseline (for example the middle square in ■■■・■・■■)
 * are accepted as part of that line.
 */
internal object PaddleSolidMaskDetector {
    private const val MIN_BRIGHT_LUMA = 170
    private const val MIN_SIDE_HEIGHT_RATIO = 0.45f
    private const val MAX_SIDE_HEIGHT_RATIO = 1.05f
    private const val MIN_ASPECT_RATIO = 0.78f
    private const val MAX_ASPECT_RATIO = 1.22f
    private const val MIN_FILL_RATIO = 0.78f
    private const val MIN_INNER_FILL_RATIO = 0.88f
    private const val MIN_CORNER_FILL_RATIO = 0.58f
    private const val MAX_SIZE_DIFFERENCE_RATIO = 0.20f
    private const val MAX_BASELINE_DIFFERENCE_RATIO = 0.16f
    private const val MAX_ADJACENT_GAP_RATIO = 0.55f
    private const val MAX_ROW_GAP_RATIO = 2.2f
    private const val GLOBAL_MIN_SIDE_PIXELS = 5f
    private const val GLOBAL_MIN_SIDE_IMAGE_RATIO = 0.02f
    private const val GLOBAL_MAX_SIDE_IMAGE_RATIO = 0.65f
    private const val MIN_SEPARATOR_SIDE_RATIO = 0.08f
    private const val MAX_SEPARATOR_SIDE_RATIO = 0.42f
    private const val MIN_SEPARATOR_ASPECT_RATIO = 0.55f
    private const val MAX_SEPARATOR_ASPECT_RATIO = 1.80f
    private const val MIN_SEPARATOR_FILL_RATIO = 0.45f
    private const val MAX_SEPARATOR_CENTER_OFFSET_RATIO = 0.28f

    fun detect(pixels: IntArray, width: Int, height: Int): List<PaddleSolidMask> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()

        val minSide = height * MIN_SIDE_HEIGHT_RATIO
        val maxSide = height * MAX_SIDE_HEIGHT_RATIO
        return trustedRows(findCandidates(pixels, width, height, minSide, maxSide))
            .maxWithOrNull(
                compareBy<PaddleSolidMaskRow>(
                    { it.masks.size },
                    { row -> row.masks.sumOf { it.confidence.toDouble() } }
                )
            )
            ?.masks
            .orEmpty()
    }

    /**
     * Finds trusted square-mask rows before Paddle's DB text boxes are recognized.
     * Size is inferred from repeated geometry rather than the full image height, so
     * masks remain detectable inside a combined name + dialogue OCR crop.
     */
    fun detectRows(pixels: IntArray, width: Int, height: Int): List<PaddleSolidMaskRow> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()

        val referenceSide = min(width, height).toFloat()
        val minSide = max(GLOBAL_MIN_SIDE_PIXELS, referenceSide * GLOBAL_MIN_SIDE_IMAGE_RATIO)
        val maxSide = max(minSide, referenceSide * GLOBAL_MAX_SIDE_IMAGE_RATIO)
        val active = brightPixelMask(pixels, width, height)
        val components = connectedComponents(active, width, height)
        val rows = trustedRows(
            findCandidates(
                active = active,
                components = components,
                width = width,
                minSide = minSide,
                maxSide = maxSide
            )
        )
        return rows.map { row ->
            row.copy(separators = findSeparators(components, row))
        }
    }

    private fun findCandidates(
        pixels: IntArray,
        width: Int,
        height: Int,
        minSide: Float,
        maxSide: Float
    ): List<PaddleSolidMask> {
        val active = brightPixelMask(pixels, width, height)
        val components = connectedComponents(active, width, height)
        return findCandidates(active, components, width, minSide, maxSide)
    }

    private fun findCandidates(
        active: BooleanArray,
        components: List<PixelComponent>,
        width: Int,
        minSide: Float,
        maxSide: Float
    ): List<PaddleSolidMask> {
        return components
            .mapNotNull { component ->
                component.toCandidate(
                    active = active,
                    imageWidth = width,
                    minSide = minSide,
                    maxSide = maxSide
                )
            }
            .sortedWith(compareBy(PaddleSolidMask::top, PaddleSolidMask::left))
    }

    private fun brightPixelMask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        return BooleanArray(width * height) { index ->
            luminance(pixels[index]) >= MIN_BRIGHT_LUMA
        }
    }

    private fun findSeparators(
        components: List<PixelComponent>,
        row: PaddleSolidMaskRow
    ): List<PaddleSolidMaskSeparator> {
        val sortedMasks = row.masks.sortedBy(PaddleSolidMask::left)
        if (sortedMasks.size < 2) return emptyList()
        val minimumSide = row.averageSide * MIN_SEPARATOR_SIDE_RATIO
        val maximumSide = row.averageSide * MAX_SEPARATOR_SIDE_RATIO

        return sortedMasks.zipWithNext().mapNotNull { (first, second) ->
            val gap = second.left - first.right
            val averageWidth = (first.width + second.width) / 2f
            if (gap <= averageWidth * MAX_ADJACENT_GAP_RATIO ||
                gap > averageWidth * MAX_ROW_GAP_RATIO
            ) {
                return@mapNotNull null
            }

            components.asSequence()
                .filter { component ->
                    val componentWidth = component.right - component.left
                    val componentHeight = component.bottom - component.top
                    if (componentWidth < minimumSide || componentHeight < minimumSide) return@filter false
                    if (componentWidth > maximumSide || componentHeight > maximumSide) return@filter false
                    val aspectRatio = componentWidth.toFloat() / componentHeight.toFloat()
                    if (aspectRatio !in MIN_SEPARATOR_ASPECT_RATIO..MAX_SEPARATOR_ASPECT_RATIO) return@filter false
                    val fillRatio = component.pixelCount.toFloat() / (componentWidth * componentHeight).toFloat()
                    if (fillRatio < MIN_SEPARATOR_FILL_RATIO) return@filter false
                    val centerX = (component.left + component.right) / 2f
                    val centerY = (component.top + component.bottom) / 2f
                    centerX in first.right.toFloat()..second.left.toFloat() &&
                        abs(centerY - row.centerY) <= row.averageSide * MAX_SEPARATOR_CENTER_OFFSET_RATIO
                }
                .minByOrNull { component ->
                    val componentCenterX = (component.left + component.right) / 2f
                    abs(componentCenterX - (first.right + second.left) / 2f)
                }
                ?.let { component ->
                    val componentWidth = component.right - component.left
                    val componentHeight = component.bottom - component.top
                    val fillRatio = component.pixelCount.toFloat() / (componentWidth * componentHeight).toFloat()
                    PaddleSolidMaskSeparator(
                        left = component.left,
                        top = component.top,
                        right = component.right,
                        bottom = component.bottom,
                        confidence = fillRatio.coerceIn(0f, 1f)
                    )
                }
        }
    }

    private fun trustedRows(candidates: List<PaddleSolidMask>): List<PaddleSolidMaskRow> {
        if (candidates.size < 2) return emptyList()

        val rows = mutableListOf<PaddleSolidMaskRow>()
        for (firstIndex in 0 until candidates.lastIndex) {
            for (secondIndex in firstIndex + 1 until candidates.size) {
                val first = candidates[firstIndex]
                val second = candidates[secondIndex]
                val orderedPair = if (first.left <= second.left) first to second else second to first
                if (!areAdjacentMatches(orderedPair.first, orderedPair.second)) continue

                val matchingMasks = candidates
                    .filter { candidate ->
                        sameMaskGeometry(candidate, orderedPair.first) ||
                            sameMaskGeometry(candidate, orderedPair.second)
                    }
                    .sortedBy(PaddleSolidMask::left)
                val masks = rowClusterForSeed(
                    matchingMasks = matchingMasks,
                    firstSeed = orderedPair.first,
                    secondSeed = orderedPair.second
                )
                if (masks.size < 2) continue
                if (rows.any { existing -> sameMaskSet(existing.masks, masks) }) continue
                rows += PaddleSolidMaskRow(masks)
            }
        }
        return rows.sortedWith(compareBy(PaddleSolidMaskRow::top, PaddleSolidMaskRow::left))
    }

    private fun rowClusterForSeed(
        matchingMasks: List<PaddleSolidMask>,
        firstSeed: PaddleSolidMask,
        secondSeed: PaddleSolidMask
    ): List<PaddleSolidMask> {
        if (matchingMasks.size <= 2) return matchingMasks
        var startIndex = matchingMasks.indexOf(firstSeed)
        var endIndex = matchingMasks.indexOf(secondSeed)
        if (startIndex < 0 || endIndex < 0) return listOf(firstSeed, secondSeed).sortedBy(PaddleSolidMask::left)
        if (startIndex > endIndex) {
            val previousStart = startIndex
            startIndex = endIndex
            endIndex = previousStart
        }

        while (startIndex > 0 && masksBelongToSameRowCluster(
                matchingMasks[startIndex - 1],
                matchingMasks[startIndex]
            )
        ) {
            startIndex--
        }
        while (endIndex < matchingMasks.lastIndex && masksBelongToSameRowCluster(
                matchingMasks[endIndex],
                matchingMasks[endIndex + 1]
            )
        ) {
            endIndex++
        }
        return matchingMasks.subList(startIndex, endIndex + 1)
    }

    private fun masksBelongToSameRowCluster(
        first: PaddleSolidMask,
        second: PaddleSolidMask
    ): Boolean {
        val averageWidth = (first.width + second.width) / 2f
        val gap = second.left - first.right
        return gap >= 0 && gap <= averageWidth * MAX_ROW_GAP_RATIO
    }

    private fun sameMaskSet(
        first: List<PaddleSolidMask>,
        second: List<PaddleSolidMask>
    ): Boolean {
        if (first.size != second.size) return false
        return first.indices.all { index ->
            val firstMask = first[index]
            val secondMask = second[index]
            firstMask.left == secondMask.left &&
                firstMask.top == secondMask.top &&
                firstMask.right == secondMask.right &&
                firstMask.bottom == secondMask.bottom
        }
    }

    private fun connectedComponents(
        active: BooleanArray,
        width: Int,
        height: Int
    ): List<PixelComponent> {
        val visited = BooleanArray(active.size)
        val queue = IntArray(active.size)
        val components = mutableListOf<PixelComponent>()

        for (start in active.indices) {
            if (!active[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var pixelCount = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
                pixelCount++

                val minY = max(0, y - 1)
                val maxY = minOf(height - 1, y + 1)
                val minX = max(0, x - 1)
                val maxX = minOf(width - 1, x + 1)
                for (nextY in minY..maxY) {
                    for (nextX in minX..maxX) {
                        if (nextX == x && nextY == y) continue
                        val next = nextY * width + nextX
                        if (active[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            components += PixelComponent(left, top, right, bottom, pixelCount)
        }
        return components
    }

    private fun PixelComponent.toCandidate(
        active: BooleanArray,
        imageWidth: Int,
        minSide: Float,
        maxSide: Float
    ): PaddleSolidMask? {
        val componentWidth = right - left
        val componentHeight = bottom - top
        if (componentWidth < minSide || componentHeight < minSide) return null
        if (componentWidth > maxSide || componentHeight > maxSide) return null

        val aspectRatio = componentWidth.toFloat() / componentHeight.toFloat()
        if (aspectRatio !in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO) return null

        val area = componentWidth * componentHeight
        val fillRatio = pixelCount.toFloat() / area.toFloat()
        if (fillRatio < MIN_FILL_RATIO) return null

        val insetX = max(1, (componentWidth * 0.22f).roundToInt())
        val insetY = max(1, (componentHeight * 0.22f).roundToInt())
        val innerFill = activeRatio(
            active = active,
            imageWidth = imageWidth,
            left = left + insetX,
            top = top + insetY,
            right = right - insetX,
            bottom = bottom - insetY
        )
        if (innerFill < MIN_INNER_FILL_RATIO) return null

        val cornerWidth = max(2, (componentWidth * 0.18f).roundToInt())
        val cornerHeight = max(2, (componentHeight * 0.18f).roundToInt())
        val cornerFill = listOf(
            activeRatio(active, imageWidth, left, top, left + cornerWidth, top + cornerHeight),
            activeRatio(active, imageWidth, right - cornerWidth, top, right, top + cornerHeight),
            activeRatio(active, imageWidth, left, bottom - cornerHeight, left + cornerWidth, bottom),
            activeRatio(active, imageWidth, right - cornerWidth, bottom - cornerHeight, right, bottom)
        ).average().toFloat()
        if (cornerFill < MIN_CORNER_FILL_RATIO) return null

        val confidence = (
            fillRatio * 0.50f +
                innerFill * 0.30f +
                cornerFill * 0.20f
            ).coerceIn(0f, 1f)
        return PaddleSolidMask(left, top, right, bottom, confidence)
    }

    private fun activeRatio(
        active: BooleanArray,
        imageWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float {
        if (right <= left || bottom <= top) return 0f
        var count = 0
        for (y in top until bottom) {
            val rowOffset = y * imageWidth
            for (x in left until right) {
                if (active[rowOffset + x]) count++
            }
        }
        return count.toFloat() / ((right - left) * (bottom - top)).toFloat()
    }

    private fun areAdjacentMatches(first: PaddleSolidMask, second: PaddleSolidMask): Boolean {
        if (!sameMaskGeometry(first, second)) return false
        val averageWidth = (first.width + second.width) / 2f
        val gap = second.left - first.right
        return gap >= 0 && gap <= averageWidth * MAX_ADJACENT_GAP_RATIO
    }

    private fun sameMaskGeometry(first: PaddleSolidMask, second: PaddleSolidMask): Boolean {
        val widthTolerance = max(first.width, second.width) * MAX_SIZE_DIFFERENCE_RATIO
        val heightTolerance = max(first.height, second.height) * MAX_SIZE_DIFFERENCE_RATIO
        if (abs(first.width - second.width) > widthTolerance) return false
        if (abs(first.height - second.height) > heightTolerance) return false

        val baselineTolerance = max(first.height, second.height) * MAX_BASELINE_DIFFERENCE_RATIO
        return abs(first.top - second.top) <= baselineTolerance &&
            abs(first.bottom - second.bottom) <= baselineTolerance
    }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xff
        val green = (pixel shr 8) and 0xff
        val blue = pixel and 0xff
        return (red * 77 + green * 150 + blue * 29) shr 8
    }

    private data class PixelComponent(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixelCount: Int
    )
}

internal object PaddleSolidMaskMerger {
    private const val MASK_TEXT = "■"
    private const val SEPARATOR_TEXT = "・"

    fun merge(
        tokens: List<PaddlePositionedToken>,
        masks: List<PaddleSolidMask>,
        separators: List<PaddleSolidMaskSeparator> = emptyList()
    ): PaddleMaskMergeResult {
        if (masks.isEmpty() && separators.isEmpty()) {
            return PaddleMaskMergeResult(
                text = tokens.joinToString(separator = "") { it.text },
                confidence = averageConfidence(tokens.map(PaddlePositionedToken::confidence)),
                recoveredMaskCount = 0
            )
        }

        val maskIntervals = maskIntervals(masks)
        val separatorIntervals = separators.map { separator ->
            val margin = max(1, separator.right - separator.left).toFloat()
            (separator.left - margin) to (separator.right + margin)
        }
        val retainedTokens = tokens.filterNot { token ->
            maskIntervals.any { interval -> token.centerX in interval.first..interval.second } ||
                separatorIntervals.any { interval -> token.centerX in interval.first..interval.second }
        }
        val events = buildList {
            retainedTokens.forEach { token ->
                add(PositionedText(token.centerX, token.text, token.confidence))
            }
            masks.forEach { mask ->
                add(PositionedText(mask.centerX, MASK_TEXT, mask.confidence))
            }
            separators.forEach { separator ->
                add(PositionedText(separator.centerX, SEPARATOR_TEXT, separator.confidence))
            }
        }.sortedBy(PositionedText::centerX)

        return PaddleMaskMergeResult(
            text = events.joinToString(separator = "") { it.text },
            confidence = averageConfidence(events.map(PositionedText::confidence)),
            recoveredMaskCount = masks.size
        )
    }

    private fun maskIntervals(masks: List<PaddleSolidMask>): List<Pair<Float, Float>> {
        if (masks.isEmpty()) return emptyList()
        val sorted = masks.sortedBy(PaddleSolidMask::left)
        val intervals = mutableListOf<Pair<Float, Float>>()
        var start = sorted.first().left.toFloat()
        var end = sorted.first().right.toFloat()
        var previous = sorted.first()

        for (mask in sorted.drop(1)) {
            val averageWidth = (previous.width + mask.width) / 2f
            val sameRun = mask.left - previous.right <= averageWidth * 0.55f
            if (sameRun) {
                end = maxOf(end, mask.right.toFloat())
            } else {
                intervals += start to end
                start = mask.left.toFloat()
                end = mask.right.toFloat()
            }
            previous = mask
        }
        intervals += start to end
        return intervals
    }

    private fun averageConfidence(values: List<Float>): Float {
        return if (values.isEmpty()) 0f else values.average().toFloat().coerceIn(0f, 1f)
    }

    private data class PositionedText(
        val centerX: Float,
        val text: String,
        val confidence: Float
    )
}
