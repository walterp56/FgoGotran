package com.fgogotran.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.util.FgoLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects FGO story UI regions by scanning background pixel colors.
 *
 * FGO's dialogue box, name label, and choice buttons all have consistent
 * dark semi-transparent backgrounds that contrast with the bright character
 * art above them. This detector finds those regions by scanning for
 * luminance transitions (dark → bright), no OCR required.
 */
@Singleton
class BackgroundDetector @Inject constructor() {

    data class DetectedRegions(
        val dialogueBox: Rect?,
        val nameLabel: Rect?,
        val choiceButtons: List<Rect>
    )

    companion object {
        /** Luminance below this value (0-255) is considered "dark" UI background. */
        private const val DARK_LUMINANCE_THRESHOLD = 80

        /** Sample every Nth column when scanning for dialogue box top edge. */
        private const val COLUMN_SAMPLE_STEP = 10

        /** Horizontal block size for computing average row luminance. */
        private const val ROW_SCAN_BLOCK_SIZE = 20

        /** Minimum fraction of dark pixels in a row for it to count as dark. */
        private const val DARK_ROW_RATIO = 0.6f

        /** Choice text can break the center, so detect the stable dark left/right panel anchors. */
        private const val CHOICE_LEFT_ANCHOR_START_RATIO = 0.02f
        private const val CHOICE_LEFT_ANCHOR_END_RATIO = 0.22f
        private const val CHOICE_RIGHT_ANCHOR_START_RATIO = 0.76f
        private const val CHOICE_RIGHT_ANCHOR_END_RATIO = 0.98f
        private const val MIN_CHOICE_LEFT_ANCHOR_DARK_RATIO = 0.58f
        private const val MIN_CHOICE_RIGHT_ANCHOR_DARK_RATIO = 0.48f

        /** Minimum height (px) for a dark band to be considered a choice button. */
        private const val MIN_CHOICE_HEIGHT = 30

        /**
         * The SKIP label and pill border occupy roughly 20% of the marked button
         * region in the supplied captures. Keep margin for scaled/animated frames.
         */
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

    fun detect(bitmap: Bitmap): DetectedRegions {
        val w = bitmap.width
        val h = bitmap.height
        FgoLogger.info(tag, "Detecting regions on ${w}x${h}")

        val dialogueTop = findDialogueTop(bitmap, w, h)
        FgoLogger.info(tag, "  dialogueTop=$dialogueTop")

        val nameLabel = if (dialogueTop > 0) {
            findNameLabel(bitmap, w, dialogueTop)
        } else null
        FgoLogger.info(tag, "  nameLabel=${nameLabel?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "none"}")

        val choiceButtons = if (dialogueTop > 0) {
            findChoiceButtons(bitmap, w, h, dialogueTop)
        } else emptyList()
        FgoLogger.info(tag, "  choiceButtons=${choiceButtons.size} ${choiceButtons.map { "[${it.left},${it.top},${it.right},${it.bottom}]" }}")

        return DetectedRegions(
            dialogueBox = if (dialogueTop > 0) Rect(0, dialogueTop, w, h) else null,
            nameLabel = nameLabel,
            choiceButtons = choiceButtons
        )
    }

    /**
     * Locates the repeated black choice panels inside the known story choice zone.
     *
     * The zone is viewport-aware and already excludes the SKIP control. Choices
     * share horizontal bounds but form a variable number of dark vertical bands.
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

    /**
     * Checks the stable top-right SKIP region before story OCR begins.
     *
     * SKIP is rendered as white text and a white pill border. A bounded bright
     * neutral-pixel ratio avoids treating a solid bright scene background as the
     * control while keeping this check cheaper than OCR.
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
     * Detects FGO's bright continue diamond, which appears only after dialogue typing completes.
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

    /**
     * Detects the SKIP confirmation modal's paired white buttons, which must not be translated.
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

    // ─── Step 1: Find dialogue box top edge ─────────────────────────

    /**
     * Scans columns bottom→top, finding the Y position where pixel luminance
     * transitions from dark (dialogue box background) to bright (character art).
     * Takes the median of all column samples for robustness against edge noise.
     */
    private fun findDialogueTop(bitmap: Bitmap, w: Int, h: Int): Int {
        val transitionYs = mutableListOf<Int>()

        for (x in 0 until w step COLUMN_SAMPLE_STEP) {
            var wasDark = true
            for (y in h - 1 downTo h / 2) {
                val lum = getBlockLuminance(bitmap, x, y, ROW_SCAN_BLOCK_SIZE)
                val isDark = lum < DARK_LUMINANCE_THRESHOLD
                if (wasDark && !isDark) {
                    transitionYs.add(y)
                    break
                }
                wasDark = isDark
            }
        }

        return if (transitionYs.size > w / COLUMN_SAMPLE_STEP / 3) {
            transitionYs.sorted()[transitionYs.size / 2]
        } else {
            0
        }
    }

    // ─── Step 2: Find name label ────────────────────────────────────

    /**
     * Searches the zone just above the dialogue box, on the left side,
     * for a small dark rectangle (the character name plate).
     */
    private fun findNameLabel(bitmap: Bitmap, w: Int, dialogueTop: Int): Rect? {
        val searchRight = (w * 0.4f).toInt()
        val searchTop = maxOf(0, dialogueTop - 120)
        val searchBottom = dialogueTop

        var bestRect: Rect? = null
        var bestScore = 0

        for (y in searchTop until searchBottom step 5) {
            for (x in 0 until searchRight step 5) {
                if (x >= bitmap.width || y >= bitmap.height) continue
                val lum = getPixelLuminance(bitmap, x, y)
                if (lum < DARK_LUMINANCE_THRESHOLD) {
                    val rect = expandDarkRect(bitmap, x, y, searchRight, dialogueTop)
                    val score = rect.width() * rect.height()
                    if (score > bestScore &&
                        rect.width() in 80..(searchRight) &&
                        rect.height() in 20..100) {
                        bestScore = score
                        bestRect = rect
                    }
                }
            }
        }
        return bestRect
    }

    // ─── Step 3: Find choice buttons ────────────────────────────────

    /**
     * Scans the middle zone (20% to dialogue top) for horizontal dark bands.
     * Each band corresponds to a choice button background.
     */
    private fun findChoiceButtons(
        bitmap: Bitmap,
        w: Int,
        h: Int,
        dialogueTop: Int
    ): List<Rect> {
        val searchTop = (h * 0.2f).toInt()
        val buttons = mutableListOf<Rect>()
        var inDarkRun = false
        var runStart = 0
        var runLeft = 0
        var runRight = 0

        for (y in searchTop until dialogueTop) {
            val darkPixels = countDarkPixelsInRow(bitmap, 0, w, y)
            val isDarkRow = darkPixels.toFloat() / w > DARK_ROW_RATIO

            if (!inDarkRun && isDarkRow) {
                runStart = y
                runLeft = findDarkRunStart(bitmap, w, y)
                runRight = findDarkRunEnd(bitmap, w, y)
                inDarkRun = true
            } else if (inDarkRun && !isDarkRow) {
                if (y - runStart >= MIN_CHOICE_HEIGHT) {
                    buttons.add(Rect(runLeft, runStart, runRight, y))
                }
                inDarkRun = false
            } else if (inDarkRun && isDarkRow) {
                // Expand horizontal bounds
                val left = findDarkRunStart(bitmap, w, y)
                val right = findDarkRunEnd(bitmap, w, y)
                if (left < runLeft) runLeft = left
                if (right > runRight) runRight = right
            }
        }

        // Catch a run that reaches the dialogue box
        if (inDarkRun) {
            val y = dialogueTop
            if (y - runStart >= MIN_CHOICE_HEIGHT) {
                buttons.add(Rect(runLeft, runStart, runRight, y))
            }
        }

        return buttons
    }

    // ─── Pixel helpers ──────────────────────────────────────────────

    private fun getPixelLuminance(bitmap: Bitmap, x: Int, y: Int): Int {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        // BT.601 luminance formula
        return (0.299f * r + 0.587f * g + 0.114f * b).toInt()
    }

    private fun getBlockLuminance(bitmap: Bitmap, cx: Int, cy: Int, size: Int): Int {
        val half = size / 2
        var sum = 0
        var count = 0
        val left = maxOf(0, cx - half)
        val right = minOf(bitmap.width - 1, cx + half)
        val top = maxOf(0, cy - half)
        val bottom = minOf(bitmap.height - 1, cy + half)
        for (y in top..bottom step 2) {
            for (x in left..right step 2) {
                sum += getPixelLuminance(bitmap, x, y)
                count++
            }
        }
        return if (count > 0) sum / count else 255
    }

    /**
     * Expands outward from a dark seed pixel to find the bounding rect
     * of the dark region it belongs to.
     */
    private fun expandDarkRect(
        bitmap: Bitmap,
        seedX: Int,
        seedY: Int,
        maxX: Int,
        maxY: Int
    ): Rect {
        var left = seedX
        var right = seedX
        var top = seedY
        var bottom = seedY

        // Expand left
        while (left > 0 && getPixelLuminance(bitmap, left - 1, seedY) < DARK_LUMINANCE_THRESHOLD) {
            left--
        }
        // Expand right
        while (right < maxX - 1 && getPixelLuminance(bitmap, right + 1, seedY) < DARK_LUMINANCE_THRESHOLD) {
            right++
        }
        // Expand up
        while (top > 0 && getPixelLuminance(bitmap, seedX, top - 1) < DARK_LUMINANCE_THRESHOLD) {
            top--
        }
        // Expand down
        while (bottom < maxY - 1 && getPixelLuminance(bitmap, seedX, bottom + 1) < DARK_LUMINANCE_THRESHOLD) {
            bottom++
        }

        return Rect(left, top, right, bottom)
    }

    private fun countDarkPixelsInRow(bitmap: Bitmap, left: Int, right: Int, y: Int): Int {
        var count = 0
        val actualRight = minOf(right, bitmap.width)
        for (x in left until actualRight step 2) {
            if (getPixelLuminance(bitmap, x, y) < DARK_LUMINANCE_THRESHOLD) {
                count++
            }
        }
        return count
    }

    private fun findDarkRunStart(bitmap: Bitmap, w: Int, y: Int): Int {
        for (x in 0 until w step 2) {
            if (getPixelLuminance(bitmap, x, y) < DARK_LUMINANCE_THRESHOLD) {
                // Walk back to find exact edge
                var edge = x
                while (edge > 0 && getPixelLuminance(bitmap, edge - 1, y) < DARK_LUMINANCE_THRESHOLD) {
                    edge--
                }
                return edge
            }
        }
        return 0
    }

    private fun findDarkRunEnd(bitmap: Bitmap, w: Int, y: Int): Int {
        for (x in w - 1 downTo 0 step 2) {
            if (getPixelLuminance(bitmap, x, y) < DARK_LUMINANCE_THRESHOLD) {
                var edge = x
                while (edge < w - 1 && getPixelLuminance(bitmap, edge + 1, y) < DARK_LUMINANCE_THRESHOLD) {
                    edge++
                }
                return edge
            }
        }
        return w
    }
}
