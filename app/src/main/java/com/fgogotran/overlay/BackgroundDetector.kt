package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pixel-based checks for FGO story UI markers.
 *
 * Dialogue/name OCR uses fixed viewport regions. This detector only owns the
 * cheap pixel checks that are still part of the active auto-mode path.
 */
@Singleton
class BackgroundDetector @Inject constructor() {

    companion object {
        private const val DARK_LUMINANCE_THRESHOLD = 80

        private const val CHOICE_LEFT_ANCHOR_START_RATIO = 0.02f
        private const val CHOICE_LEFT_ANCHOR_END_RATIO = 0.22f
        private const val CHOICE_RIGHT_ANCHOR_START_RATIO = 0.76f
        private const val CHOICE_RIGHT_ANCHOR_END_RATIO = 0.98f
        private const val MIN_CHOICE_LEFT_ANCHOR_DARK_RATIO = 0.58f
        private const val MIN_CHOICE_RIGHT_ANCHOR_DARK_RATIO = 0.48f
        private const val MIN_CHOICE_HEIGHT = 30
        private const val MIN_FIXED_CHOICE_SLOT_DARK_RATIO = 0.46f
        private const val MIN_FIXED_CHOICE_RAW_OVERLAP_RATIO = 0.22f
        private const val MIN_FIXED_CHOICE_BORDER_RATIO = 0.08f

        private const val MIN_SKIP_WHITE_RATIO = 0.08f
        private const val MAX_SKIP_WHITE_RATIO = 0.55f
        private const val MIN_COMPLETE_MARKER_WHITE_RATIO = 0.018f
        private const val MIN_SKIP_CONFIRM_BUTTON_WHITE_RATIO = 0.35f
    }

    private val tag = "BackgroundDetector"

    private data class WhiteScore(
        val whitePixels: Int,
        val ratio: Float
    )

    private data class ChoiceSlotScore(
        val darkRatio: Float,
        val topBorderRatio: Float,
        val bottomBorderRatio: Float
    ) {
        val hasBorder: Boolean
            get() = topBorderRatio >= MIN_FIXED_CHOICE_BORDER_RATIO &&
                bottomBorderRatio >= MIN_FIXED_CHOICE_BORDER_RATIO

        val isVisible: Boolean
            get() = darkRatio >= MIN_FIXED_CHOICE_SLOT_DARK_RATIO && hasBorder

        val combinedScore: Float
            get() = darkRatio + topBorderRatio + bottomBorderRatio
    }

    private data class FixedChoiceLayoutCandidate(
        val slots: List<Rect>,
        val scores: List<ChoiceSlotScore>
    ) {
        val combinedScore: Float
            get() = scores.sumOf { it.combinedScore.toDouble() }.toFloat()
    }

    /**
     * Locates repeated black choice panels inside the known story choice zone.
     */
    fun detectChoiceButtons(bitmap: Bitmap, searchRegion: Rect): List<Rect> {
        val bounds = Rect(
            searchRegion.left.coerceIn(0, bitmap.width),
            searchRegion.top.coerceIn(0, bitmap.height),
            searchRegion.right.coerceIn(0, bitmap.width),
            searchRegion.bottom.coerceIn(0, bitmap.height)
        )
        if (bounds.width() <= 0 || bounds.height() <= 0) return emptyList()

        val minHeight = (bitmap.height * 0.055f).toInt().coerceAtLeast(MIN_CHOICE_HEIGHT)
        val maxHeight = (bitmap.height * 0.15f).toInt().coerceAtLeast(120)
        val edgePadding = (bitmap.height * 0.006f).toInt().coerceAtLeast(3)
        val width = bounds.width()
        val leftAnchor = Rect(
            bounds.left + (width * CHOICE_LEFT_ANCHOR_START_RATIO).toInt(),
            bounds.top,
            bounds.left + (width * CHOICE_LEFT_ANCHOR_END_RATIO).toInt(),
            bounds.bottom
        )
        val rightAnchor = Rect(
            bounds.left + (width * CHOICE_RIGHT_ANCHOR_START_RATIO).toInt(),
            bounds.top,
            bounds.left + (width * CHOICE_RIGHT_ANCHOR_END_RATIO).toInt(),
            bounds.bottom
        )
        val buttons = mutableListOf<Rect>()
        var darkRunStart: Int? = null
        var lastDarkRow = bounds.top
        var lightGapRows = 0

        fun finishRun(bottom: Int) {
            val top = darkRunStart ?: return
            addChoiceButton(
                buttons = buttons,
                searchRegion = bounds,
                left = bounds.left,
                right = bounds.right,
                top = top,
                bottom = bottom,
                minHeight = minHeight,
                maxHeight = maxHeight,
                edgePadding = edgePadding
            )
            darkRunStart = null
            lightGapRows = 0
        }

        for (y in bounds.top until bounds.bottom) {
            val isPanelRow = isChoicePanelAnchorRow(bitmap, y, leftAnchor, rightAnchor)
            if (isPanelRow && darkRunStart == null) {
                darkRunStart = y
                lastDarkRow = y
                lightGapRows = 0
            } else if (isPanelRow) {
                lastDarkRow = y
                lightGapRows = 0
            } else if (darkRunStart != null) {
                lightGapRows++
                if (lightGapRows > 3) {
                    finishRun(lastDarkRow + 1)
                }
            }
        }

        if (darkRunStart != null) {
            finishRun(lastDarkRow + 1)
        }

        FgoLogger.info(tag, "Choice zone detected ${buttons.size} panels in $bounds ${buttons.map { it.flattenToString() }}")
        return buttons
    }

    fun snapChoiceButtonsToFixedSlots(
        bitmap: Bitmap,
        rawButtons: List<Rect>,
        fixedSlotLayouts: List<List<Rect>>
    ): List<Rect> {
        val fixedButtons = fixedChoiceButtons(bitmap, rawButtons, fixedSlotLayouts)
        if (fixedButtons != null && fixedButtons != rawButtons) {
            FgoLogger.debug(
                tag,
                "Snapped choice panels to fixed FGO layout: raw=${rawButtons.map { it.flattenToString() }} " +
                    "fixed=${fixedButtons.map { it.flattenToString() }}"
            )
        }
        return fixedButtons ?: rawButtons
    }

    /**
     * Checks the stable top-right SKIP region before story OCR begins.
     */
    fun isSkipButtonVisible(bitmap: Bitmap, skipRegion: Rect): Boolean {
        val bounds = Rect(
            skipRegion.left.coerceIn(0, bitmap.width),
            skipRegion.top.coerceIn(0, bitmap.height),
            skipRegion.right.coerceIn(0, bitmap.width),
            skipRegion.bottom.coerceIn(0, bitmap.height)
        )
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        var whitePixels = 0
        var totalPixels = 0
        for (y in bounds.top until bounds.bottom step 2) {
            for (x in bounds.left until bounds.right step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val channelSpread = maxOf(r, g, b) - minOf(r, g, b)
                if (r >= 190 && g >= 190 && b >= 190 && channelSpread <= 30) {
                    whitePixels++
                }
                totalPixels++
            }
        }

        val whiteRatio = if (totalPixels == 0) 0f else whitePixels.toFloat() / totalPixels
        val visible = whiteRatio in MIN_SKIP_WHITE_RATIO..MAX_SKIP_WHITE_RATIO
        FgoLogger.debug(tag, "SKIP marker visible=$visible whiteRatio=$whiteRatio bounds=$bounds")
        return visible
    }

    /**
     * Detects FGO's bright continue diamond after dialogue typing completes.
     */
    fun isDialogueCompleteMarkerVisible(bitmap: Bitmap, markerRegion: Rect): Boolean {
        val baseBounds = Rect(
            markerRegion.left.coerceIn(0, bitmap.width),
            markerRegion.top.coerceIn(0, bitmap.height),
            markerRegion.right.coerceIn(0, bitmap.width),
            markerRegion.bottom.coerceIn(0, bitmap.height)
        )
        if (baseBounds.width() <= 0 || baseBounds.height() <= 0) return false

        val baseScore = completeMarkerWhiteScore(bitmap, baseBounds)
        val markerShapeVisible = hasCompleteMarkerShape(bitmap, baseBounds)
        val visible = markerShapeVisible || baseScore.ratio >= MIN_COMPLETE_MARKER_WHITE_RATIO
        FgoLogger.debug(
            tag,
            "Dialogue complete marker visible=$visible whiteRatio=${baseScore.ratio} " +
                "whitePixels=${baseScore.whitePixels} markerShape=$markerShapeVisible bounds=$baseBounds"
        )
        return visible
    }

    /**
     * Detects the SKIP confirmation modal's paired white buttons.
     */
    fun isSkipConfirmationVisible(bitmap: Bitmap, noButtonRegion: Rect, yesButtonRegion: Rect): Boolean {
        val noButtonRatio = neutralWhiteRatio(bitmap, noButtonRegion)
        val yesButtonRatio = neutralWhiteRatio(bitmap, yesButtonRegion)
        val visible = noButtonRatio >= MIN_SKIP_CONFIRM_BUTTON_WHITE_RATIO &&
            yesButtonRatio >= MIN_SKIP_CONFIRM_BUTTON_WHITE_RATIO
        FgoLogger.debug(
            tag,
            "SKIP confirmation visible=$visible noRatio=$noButtonRatio yesRatio=$yesButtonRatio"
        )
        return visible
    }

    private fun hasCompleteMarkerShape(bitmap: Bitmap, bounds: Rect): Boolean {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return false

        val visited = BooleanArray(width * height)
        val minPixels = maxOf(35, (width * height * 0.015f).toInt())
        val minWidth = maxOf(10, (width * 0.16f).toInt())
        val minHeight = maxOf(18, (height * 0.34f).toInt())
        val maxWidth = maxOf(minWidth, (width * 0.78f).toInt())
        val maxHeight = maxOf(minHeight, (height * 0.98f).toInt())

        fun index(x: Int, y: Int): Int = y * width + x

        for (localY in 0 until height) {
            for (localX in 0 until width) {
                val seedIndex = index(localX, localY)
                if (visited[seedIndex]) continue
                val screenX = bounds.left + localX
                val screenY = bounds.top + localY
                if (!isCompleteMarkerPixel(bitmap.getPixel(screenX, screenY))) {
                    visited[seedIndex] = true
                    continue
                }

                var count = 0
                var minX = localX
                var maxX = localX
                var minY = localY
                var maxY = localY
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(localX to localY)
                visited[seedIndex] = true

                while (queue.isNotEmpty()) {
                    val (x, y) = queue.removeFirst()
                    count++
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)

                    for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                        for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                            val nextIndex = index(nx, ny)
                            if (visited[nextIndex]) continue
                            visited[nextIndex] = true
                            if (isCompleteMarkerPixel(bitmap.getPixel(bounds.left + nx, bounds.top + ny))) {
                                queue.add(nx to ny)
                            }
                        }
                    }
                }

                val componentWidth = maxX - minX + 1
                val componentHeight = maxY - minY + 1
                val aspect = componentWidth.toFloat() / componentHeight.coerceAtLeast(1)
                if (count >= minPixels &&
                    componentWidth in minWidth..maxWidth &&
                    componentHeight in minHeight..maxHeight &&
                    aspect in 0.32f..1.18f
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun completeMarkerWhiteScore(bitmap: Bitmap, bounds: Rect): WhiteScore {
        var whitePixels = 0
        var totalPixels = 0
        for (y in bounds.top until bounds.bottom step 2) {
            for (x in bounds.left until bounds.right step 2) {
                if (isCompleteMarkerPixel(bitmap.getPixel(x, y))) {
                    whitePixels++
                }
                totalPixels++
            }
        }
        return WhiteScore(
            whitePixels = whitePixels,
            ratio = if (totalPixels == 0) 0f else whitePixels.toFloat() / totalPixels
        )
    }

    private fun isCompleteMarkerPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val channelSpread = maxOf(r, g, b) - minOf(r, g, b)
        return r >= 176 && g >= 176 && b >= 176 && channelSpread <= 58
    }

    private fun neutralWhiteRatio(bitmap: Bitmap, region: Rect): Float {
        val bounds = Rect(
            region.left.coerceIn(0, bitmap.width),
            region.top.coerceIn(0, bitmap.height),
            region.right.coerceIn(0, bitmap.width),
            region.bottom.coerceIn(0, bitmap.height)
        )
        if (bounds.width() <= 0 || bounds.height() <= 0) return 0f

        var whitePixels = 0
        var totalPixels = 0
        for (y in bounds.top until bounds.bottom step 2) {
            for (x in bounds.left until bounds.right step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val channelSpread = maxOf(r, g, b) - minOf(r, g, b)
                if (r >= 190 && g >= 190 && b >= 190 && channelSpread <= 35) {
                    whitePixels++
                }
                totalPixels++
            }
        }

        return if (totalPixels == 0) 0f else whitePixels.toFloat() / totalPixels
    }

    private fun isChoicePanelAnchorRow(
        bitmap: Bitmap,
        y: Int,
        leftAnchor: Rect,
        rightAnchor: Rect
    ): Boolean {
        return darkRatioInRow(bitmap, leftAnchor.left, leftAnchor.right, y) >= MIN_CHOICE_LEFT_ANCHOR_DARK_RATIO &&
            darkRatioInRow(bitmap, rightAnchor.left, rightAnchor.right, y) >= MIN_CHOICE_RIGHT_ANCHOR_DARK_RATIO
    }

    private fun darkRatioInRow(bitmap: Bitmap, left: Int, right: Int, y: Int): Float {
        var dark = 0
        var total = 0
        val actualLeft = left.coerceIn(0, bitmap.width)
        val actualRight = right.coerceIn(0, bitmap.width)
        for (x in actualLeft until actualRight step 2) {
            if (getPixelLuminance(bitmap, x, y) < DARK_LUMINANCE_THRESHOLD) {
                dark++
            }
            total++
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    private fun fixedChoiceButtons(
        bitmap: Bitmap,
        rawButtons: List<Rect>,
        fixedSlotLayouts: List<List<Rect>>
    ): List<Rect>? {
        if (rawButtons.isEmpty() || fixedSlotLayouts.isEmpty()) return null

        val candidate = fixedSlotLayouts
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }
            .mapNotNull { layout ->
                val clippedLayout = layout.mapNotNull { clippedToBitmap(it, bitmap) }
                if (clippedLayout.size != layout.size) return@mapNotNull null
                val scores = clippedLayout.map { slot -> fixedChoiceSlotScore(bitmap, slot) }
                if (!scores.all { it.isVisible }) return@mapNotNull null
                if (!rawButtons.any { raw ->
                    clippedLayout.any { slot ->
                        verticalOverlapRatio(raw, slot) >= MIN_FIXED_CHOICE_RAW_OVERLAP_RATIO
                    }
                }) {
                    return@mapNotNull null
                }

                FixedChoiceLayoutCandidate(clippedLayout, scores)
            }
            .maxWithOrNull(
                compareBy<FixedChoiceLayoutCandidate> { it.slots.size }
                    .thenBy { it.combinedScore }
            )

        if (candidate != null) {
            FgoLogger.debug(
                tag,
                "Fixed choice slot signature accepted: count=${candidate.slots.size}, " +
                    "scores=${candidate.scores.map { "dark=${it.darkRatio},top=${it.topBorderRatio},bottom=${it.bottomBorderRatio}" }}"
            )
        }
        return candidate?.slots
    }

    private fun clippedToBitmap(rect: Rect, bitmap: Bitmap): Rect? {
        return Rect(rect).takeIf { clipped ->
            clipped.intersect(0, 0, bitmap.width, bitmap.height) &&
                clipped.width() > 0 &&
                clipped.height() > 0
        }
    }

    private fun fixedChoiceSlotScore(bitmap: Bitmap, slot: Rect): ChoiceSlotScore {
        val bounds = Rect(slot)
        if (!bounds.intersect(0, 0, bitmap.width, bitmap.height) ||
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            return ChoiceSlotScore(0f, 0f, 0f)
        }

        val width = bounds.width()
        val height = bounds.height()
        val sampleTop = bounds.top + (height * 0.16f).toInt()
        val sampleBottom = bounds.bottom - (height * 0.10f).toInt()
        val leftAnchor = Rect(
            bounds.left + (width * CHOICE_LEFT_ANCHOR_START_RATIO).toInt(),
            sampleTop,
            bounds.left + (width * CHOICE_LEFT_ANCHOR_END_RATIO).toInt(),
            sampleBottom
        )
        val rightAnchor = Rect(
            bounds.left + (width * CHOICE_RIGHT_ANCHOR_START_RATIO).toInt(),
            sampleTop,
            bounds.left + (width * CHOICE_RIGHT_ANCHOR_END_RATIO).toInt(),
            sampleBottom
        )
        val darkRatio = minOf(
            darkRatioInRect(bitmap, leftAnchor),
            darkRatioInRect(bitmap, rightAnchor)
        )
        val horizontalInset = (width * 0.035f).toInt().coerceAtLeast(12)
        val borderBandHeight = (height * 0.08f).toInt().coerceIn(5, 12)
        val topBorder = Rect(
            bounds.left + horizontalInset,
            bounds.top,
            bounds.right - horizontalInset,
            bounds.top + borderBandHeight
        )
        val bottomBorder = Rect(
            bounds.left + horizontalInset,
            bounds.bottom - borderBandHeight,
            bounds.right - horizontalInset,
            bounds.bottom
        )

        return ChoiceSlotScore(
            darkRatio = darkRatio,
            topBorderRatio = choiceBorderRatioInRect(bitmap, topBorder),
            bottomBorderRatio = choiceBorderRatioInRect(bitmap, bottomBorder)
        )
    }

    private fun darkRatioInRect(bitmap: Bitmap, rect: Rect): Float {
        val bounds = Rect(rect)
        if (!bounds.intersect(0, 0, bitmap.width, bitmap.height) ||
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            return 0f
        }

        var dark = 0
        var total = 0
        for (y in bounds.top until bounds.bottom step 3) {
            for (x in bounds.left until bounds.right step 3) {
                if (getPixelLuminance(bitmap, x, y) < DARK_LUMINANCE_THRESHOLD) {
                    dark++
                }
                total++
            }
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    private fun choiceBorderRatioInRect(bitmap: Bitmap, rect: Rect): Float {
        val bounds = Rect(rect)
        if (!bounds.intersect(0, 0, bitmap.width, bitmap.height) ||
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            return 0f
        }

        var border = 0
        var total = 0
        for (y in bounds.top until bounds.bottom step 2) {
            for (x in bounds.left until bounds.right step 2) {
                if (isChoiceBorderPixel(bitmap.getPixel(x, y))) {
                    border++
                }
                total++
            }
        }
        return if (total == 0) 0f else border.toFloat() / total
    }

    private fun isChoiceBorderPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val brightNeutral = r >= 175 && g >= 185 && b >= 190 && max - min <= 82
        val cyanBlue = r >= 80 && g >= 125 && b >= 150 && b >= r + 24 && g >= r + 12
        val paleBlue = r >= 110 && g >= 150 && b >= 170 && b >= r + 18 && max - min <= 115
        return brightNeutral || cyanBlue || paleBlue
    }

    private fun verticalOverlapRatio(first: Rect, second: Rect): Float {
        val overlap = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        if (overlap <= 0) return 0f
        return overlap.toFloat() / minOf(first.height(), second.height()).coerceAtLeast(1)
    }

    private fun addChoiceButton(
        buttons: MutableList<Rect>,
        searchRegion: Rect,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        minHeight: Int,
        maxHeight: Int,
        edgePadding: Int
    ) {
        val height = bottom - top
        if (height < minHeight) return
        if (height > maxHeight) {
            FgoLogger.debug(
                tag,
                "Ignoring oversized choice-like panel height=$height max=$maxHeight bounds=Rect(${searchRegion.left}, $top - ${searchRegion.right}, $bottom)"
            )
            return
        }
        buttons.add(
            Rect(
                (left - edgePadding).coerceAtLeast(searchRegion.left),
                (top - edgePadding).coerceAtLeast(searchRegion.top),
                (right + edgePadding).coerceAtMost(searchRegion.right),
                (bottom + edgePadding).coerceAtMost(searchRegion.bottom)
            )
        )
    }

    private fun getPixelLuminance(bitmap: Bitmap, x: Int, y: Int): Int {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }
}
