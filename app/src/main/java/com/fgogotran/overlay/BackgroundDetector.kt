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

        /** Minimum height (px) for a dark band to be considered a choice button. */
        private const val MIN_CHOICE_HEIGHT = 30
    }

    private val tag = "BackgroundDetector"

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

        val sampledColumns = ((bounds.width() + 1) / 2).coerceAtLeast(1)
        val minHeight = (bitmap.height * 0.03f).toInt().coerceAtLeast(16)
        val edgePadding = (bitmap.height * 0.006f).toInt().coerceAtLeast(3)
        val buttons = mutableListOf<Rect>()
        var darkRunStart: Int? = null

        for (y in bounds.top until bounds.bottom) {
            val darkPixels = countDarkPixelsInRow(bitmap, bounds.left, bounds.right, y)
            val isPanelRow = darkPixels.toFloat() / sampledColumns > DARK_ROW_RATIO

            if (isPanelRow && darkRunStart == null) {
                darkRunStart = y
            } else if (!isPanelRow && darkRunStart != null) {
                addChoiceButton(buttons, bounds, darkRunStart, y, minHeight, edgePadding)
                darkRunStart = null
            }
        }

        darkRunStart?.let {
            addChoiceButton(buttons, bounds, it, bounds.bottom, minHeight, edgePadding)
        }

        FgoLogger.info(tag, "Choice zone detected ${buttons.size} panels in $bounds")
        return buttons
    }

    private fun addChoiceButton(
        buttons: MutableList<Rect>,
        searchRegion: Rect,
        top: Int,
        bottom: Int,
        minHeight: Int,
        edgePadding: Int
    ) {
        if (bottom - top < minHeight) return
        buttons.add(
            Rect(
                searchRegion.left,
                (top - edgePadding).coerceAtLeast(searchRegion.top),
                searchRegion.right,
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
