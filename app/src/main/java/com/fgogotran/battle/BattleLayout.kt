package com.fgogotran.battle

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Stable battle regions on FGO's centered 1920x1080 gameplay canvas. */
object BattleLayout {
    val subtitle = FgoReferenceRect(100, 688, 1820, 866)
    val resultHeader = FgoReferenceRect(720, 0, 1200, 185)

    /**
     * FGO moves the battle counters horizontally for different aspect ratios and
     * UI safe areas. This deliberately wide crop contains every observed position;
     * [BattleHudDetector] finds the aligned label stack inside it.
     */
    val hudSearch = FgoReferenceRect(900, 0, 1650, 235)

    internal val hudLabelRows = listOf(
        FgoReferenceRect(0, 14, 130, 66),
        FgoReferenceRect(0, 68, 130, 118),
        FgoReferenceRect(0, 119, 130, 174)
    )

    internal const val HUD_LEFT_MIN = 900
    internal const val HUD_LEFT_MAX = 1520
    internal const val HUD_LEFT_STEP = 10
    internal const val HUD_TOP_OFFSET_MIN = 0
    internal const val HUD_TOP_OFFSET_MAX = 60
    internal const val HUD_TOP_OFFSET_STEP = 5

    fun map(rect: FgoReferenceRect, width: Int, height: Int) = FgoViewportGeometry.map(rect, width, height)
}

/** Battle-caption typography expressed on FGO's 1920x1080 reference canvas. */
object BattleSubtitleStyle {
    private const val REFERENCE_HEIGHT_PX = 1080f
    private const val REFERENCE_TEXT_SIZE_PX = 44f

    /** Matches FGO's subtitle line separation without changing the font size to fit longer text. */
    const val LINE_SPACING_MULTIPLIER = 1.25f

    fun textSizePx(screenWidth: Int, screenHeight: Int): Float {
        val viewportHeight = FgoViewportGeometry.viewport(screenWidth, screenHeight).height
        return REFERENCE_TEXT_SIZE_PX * viewportHeight / REFERENCE_HEIGHT_PX
    }

    /**
     * Battle OCR rows and model output line breaks describe source/display wrapping,
     * not paragraph semantics. Reflow them so the overlay can use its full safe width.
     * A space is retained only when joining two ASCII words.
     */
    fun displayText(text: String): String {
        val rows = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (rows.isEmpty()) return ""
        return buildString(text.length) {
            rows.forEachIndexed { index, row ->
                if (index > 0 && lastOrNull().isAsciiWordCharacter() && row.first().isAsciiWordCharacter()) {
                    append(' ')
                }
                append(row)
            }
        }
    }

    /** Exact width for short captions; the full safe band for captions that must wrap. */
    fun captionWidthPx(textWidthPx: Float, horizontalPaddingPx: Int, maxWidthPx: Int): Int {
        if (maxWidthPx <= 0) return 0
        val desired = ceil(textWidthPx.coerceAtLeast(0f)).toInt() +
            horizontalPaddingPx.coerceAtLeast(0) * 2
        return desired.coerceIn(1, maxWidthPx)
    }

    private fun Char?.isAsciiWordCharacter(): Boolean =
        this != null && code < 128 && isLetterOrDigit()
}

/** A HUD stack located in normalized 1920x1080 reference coordinates. */
data class BattleHudMatch(
    val referenceLeft: Int,
    val referenceTopOffset: Int,
    val matchedLabels: Int,
    val score: Float
)

/** Locates the stable three-label stack while excluding the changing battle counters. */
object BattleHudDetector {
    /** One OCR confirmation on entry prevents a similarly colored effect from entering battle. */
    fun confirmsLabels(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
        return listOf("BATTLE", "ENEMY", "TURN").count { it in compact } >= 2
    }

    /** Compatibility entry point for callers that only need presence. */
    fun isVisible(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
        return locate(width, height, pixel) != null
    }

    /**
     * Prefer the last confirmed position, then search the complete observed safe area.
     * Sampling is performed once for the whole area so sliding candidates do not make
     * repeated Bitmap.getPixel calls.
     */
    fun locate(
        width: Int,
        height: Int,
        pixel: (Int, Int) -> Int,
        preferred: BattleHudMatch? = null
    ): BattleHudMatch? {
        if (width < height || height < 240) return null

        preferred?.let { previous ->
            val localSamples = HudSamples(
                width,
                height,
                candidateSampleRegion(previous.referenceLeft, previous.referenceTopOffset),
                pixel
            )
            scoreCandidate(localSamples, previous.referenceLeft, previous.referenceTopOffset)
                ?.let { return it }
        }

        val samples = HudSamples(width, height, BattleLayout.hudSearch, pixel)
        if (samples.isEmpty) return null
        var best: BattleHudMatch? = null
        var topOffset = BattleLayout.HUD_TOP_OFFSET_MIN
        while (topOffset <= BattleLayout.HUD_TOP_OFFSET_MAX) {
            var left = BattleLayout.HUD_LEFT_MIN
            while (left <= BattleLayout.HUD_LEFT_MAX) {
                val candidate = scoreCandidate(samples, left, topOffset)
                if (candidate != null && (best == null ||
                        candidate.matchedLabels > best.matchedLabels ||
                        candidate.matchedLabels == best.matchedLabels && candidate.score > best.score)) {
                    best = candidate
                }
                left += BattleLayout.HUD_LEFT_STEP
            }
            topOffset += BattleLayout.HUD_TOP_OFFSET_STEP
        }
        return best
    }

    private fun candidateSampleRegion(left: Int, topOffset: Int): FgoReferenceRect {
        val first = BattleLayout.hudLabelRows.first()
        val last = BattleLayout.hudLabelRows.last()
        return FgoReferenceRect(
            left = left,
            top = (topOffset + first.top - 4).coerceAtLeast(0),
            right = left + first.right,
            bottom = (topOffset + last.bottom + 4).coerceAtMost(1080)
        )
    }

    private fun scoreCandidate(samples: HudSamples, left: Int, topOffset: Int): BattleHudMatch? {
        val labels = BattleLayout.hudLabelRows.mapIndexed { index, row ->
            val reference = FgoReferenceRect(
                left + row.left,
                topOffset + row.top,
                left + row.right,
                topOffset + row.bottom
            )
            samples.score(index, reference)
        }
        val matched = labels.count(LabelScore::matches)
        if (matched < 2) return null
        return BattleHudMatch(
            referenceLeft = left,
            referenceTopOffset = topOffset,
            matchedLabels = matched,
            score = labels.filter(LabelScore::matches).sumOf { it.quality.toDouble() }.toFloat()
        )
    }

    private data class LabelScore(val matches: Boolean, val quality: Float)

    private class HudSamples(
        private val screenWidth: Int,
        private val screenHeight: Int,
        referenceBounds: FgoReferenceRect,
        pixel: (Int, Int) -> Int
    ) {
        private val bounds: FgoReferenceRect
        private val step: Int
        private val columns: Int
        private val rows: Int
        private val colors: IntArray
        val isEmpty: Boolean get() = columns <= 0 || rows <= 0

        init {
            val mapped = BattleLayout.map(referenceBounds, screenWidth, screenHeight)
            bounds = FgoReferenceRect(
                mapped.left.coerceIn(0, screenWidth),
                mapped.top.coerceIn(0, screenHeight),
                mapped.right.coerceIn(0, screenWidth),
                mapped.bottom.coerceIn(0, screenHeight)
            )
            val viewportHeight = FgoViewportGeometry.viewport(screenWidth, screenHeight).height
            step = (viewportHeight / 1080f * 2f).roundToInt().coerceAtLeast(1)
            columns = ceilDiv(bounds.width, step)
            rows = ceilDiv(bounds.height, step)
            colors = if (columns <= 0 || rows <= 0) IntArray(0) else IntArray(columns * rows) { index ->
                val column = index % columns
                val row = index / columns
                val x = (bounds.left + column * step).coerceAtMost(screenWidth - 1)
                val y = (bounds.top + row * step).coerceAtMost(screenHeight - 1)
                pixel(x, y)
            }
        }

        fun score(label: Int, reference: FgoReferenceRect): LabelScore {
            if (isEmpty) return LabelScore(false, 0f)
            val mapped = BattleLayout.map(reference, screenWidth, screenHeight)
            val left = ceilDiv((mapped.left - bounds.left).coerceAtLeast(0), step).coerceAtMost(columns)
            val top = ceilDiv((mapped.top - bounds.top).coerceAtLeast(0), step).coerceAtMost(rows)
            val right = ceilDiv((mapped.right - bounds.left).coerceAtLeast(0), step).coerceIn(left, columns)
            val bottom = ceilDiv((mapped.bottom - bounds.top).coerceAtLeast(0), step).coerceIn(top, rows)
            val regionWidth = right - left
            val regionHeight = bottom - top
            if (regionWidth <= 0 || regionHeight <= 0) return LabelScore(false, 0f)

            val occupiedColumns = BooleanArray(regionWidth)
            val occupiedRows = BooleanArray(regionHeight)
            var ink = 0
            var outlined = 0
            for (row in top until bottom) for (column in left until right) {
                val color = colors[row * columns + column]
                if (!matchesColor(label, color)) continue
                ink++
                occupiedColumns[column - left] = true
                occupiedRows[row - top] = true
                if ((row > 0 && isDark(colors[(row - 1) * columns + column])) ||
                    (row + 1 < rows && isDark(colors[(row + 1) * columns + column]))) {
                    outlined++
                }
            }

            val area = regionWidth * regionHeight.toFloat()
            val inkRatio = ink / area
            val horizontalCoverage = occupiedColumns.count { it } / regionWidth.toFloat()
            val verticalCoverage = occupiedRows.count { it } / regionHeight.toFloat()
            val outlineRatio = outlined / ink.coerceAtLeast(1).toFloat()
            val matches = ink >= 4 && inkRatio in 0.022f..0.48f &&
                horizontalCoverage >= 0.30f && verticalCoverage >= 0.13f && outlineRatio >= 0.20f
            if (!matches) return LabelScore(false, 0f)

            // Actual label glyphs occupy about seven percent of their row. Prefer
            // compact outlined text over large same-colour battle effects.
            val densityQuality = (1f - abs(inkRatio - 0.07f) / 0.20f).coerceIn(0f, 1f)
            val quality = densityQuality + min(horizontalCoverage, 0.75f) +
                min(verticalCoverage, 0.50f) + min(outlineRatio, 0.60f)
            return LabelScore(true, quality)
        }

        private fun matchesColor(label: Int, color: Int): Boolean {
            val red = color shr 16 and 255
            val green = color shr 8 and 255
            val blue = color and 255
            return when (label) {
                0 -> red > 155 && green > 100 && red > blue + 45 && green > blue + 25
                1 -> red > 140 && red > green * 1.30 && red > blue * 1.35
                else -> green > 125 && blue > 125 && green > red + 30 && blue > red + 30
            }
        }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        if (value <= 0) 0 else (value + divisor - 1) / divisor

    private fun isDark(pixel: Int) = maxOf(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255) < 140
}
