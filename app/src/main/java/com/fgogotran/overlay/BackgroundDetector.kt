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
        private const val MIN_PARTIAL_FIXED_CHOICE_DARK_RATIO = 0.42f
        private const val MIN_PARTIAL_FIXED_CHOICE_HEIGHT_RATIO = 0.36f
        private const val MAX_PARTIAL_FIXED_CHOICE_HEIGHT_RATIO = 1.10f
        private const val MIN_PARTIAL_FIXED_CHOICE_WIDTH_RATIO = 0.86f
        private const val MIN_PARTIAL_FIXED_CHOICE_SLOT_OVERLAP_RATIO = 0.82f

        private const val COMPLETE_MARKER_MIN_ASPECT = 0.42f
        private const val COMPLETE_MARKER_MAX_ASPECT = 0.95f
        private const val COMPLETE_MARKER_EDGE_GUARD_PX = 2
        private const val MIN_DIALOGUE_COMPLETE_EVIDENCE_WHITE_PIXELS = 100
        private const val MIN_DIALOGUE_COMPLETE_EVIDENCE_RATIO = 0.05f
        private const val MAX_DIALOGUE_COMPLETE_EVIDENCE_WHITE_PIXELS = 900
        private const val MAX_DIALOGUE_COMPLETE_EVIDENCE_RATIO = 0.35f
        private const val MIN_MARKER_COMPONENT_PIXELS = 8
        private const val COMPONENT_EVIDENCE_MIN_ASPECT = 0.38f
        private const val COMPONENT_EVIDENCE_MAX_ASPECT = 1.05f
        private const val COMPONENT_EVIDENCE_MIN_COMPACTNESS = 0.28f
        private const val COMPONENT_EVIDENCE_MAX_COMPACTNESS = 0.85f
    }

    private val tag = "BackgroundDetector"

    private data class WhiteScore(
        val whitePixels: Int,
        val ratio: Float
    )

    private data class MarkerColorProfile(
        val r: Int,
        val g: Int,
        val b: Int,
        val luminance: Int
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
        val matches: List<FixedChoiceSlotMatch>
    ) {
        val combinedScore: Float
            get() = matches.sumOf { it.combinedScore.toDouble() }.toFloat()
    }

    private data class PartialChoiceSlotScore(
        val rawDarkRatio: Float,
        val borderRatio: Float,
        val widthRatio: Float,
        val heightRatio: Float,
        val slotOverlapRatio: Float
    ) {
        val isVisible: Boolean
            get() = rawDarkRatio >= MIN_PARTIAL_FIXED_CHOICE_DARK_RATIO &&
                borderRatio >= MIN_FIXED_CHOICE_BORDER_RATIO &&
                widthRatio >= MIN_PARTIAL_FIXED_CHOICE_WIDTH_RATIO &&
                heightRatio in MIN_PARTIAL_FIXED_CHOICE_HEIGHT_RATIO..MAX_PARTIAL_FIXED_CHOICE_HEIGHT_RATIO &&
                slotOverlapRatio >= MIN_PARTIAL_FIXED_CHOICE_SLOT_OVERLAP_RATIO

        val combinedScore: Float
            get() = rawDarkRatio + borderRatio + widthRatio + slotOverlapRatio
    }

    private data class FixedChoiceSlotMatch(
        val fixedScore: ChoiceSlotScore,
        val partialScore: PartialChoiceSlotScore?
    ) {
        val hasRawEvidence: Boolean
            get() = partialScore?.let { score ->
                score.widthRatio >= MIN_PARTIAL_FIXED_CHOICE_WIDTH_RATIO &&
                    score.slotOverlapRatio >= MIN_PARTIAL_FIXED_CHOICE_SLOT_OVERLAP_RATIO
            } == true

        val isVisible: Boolean
            get() = fixedScore.isVisible || partialScore?.isVisible == true

        val combinedScore: Float
            get() = fixedScore.combinedScore + (partialScore?.combinedScore ?: 0f)

        fun debugString(): String {
            val partial = partialScore
            return if (partial == null) {
                "fixed(dark=${fixedScore.darkRatio},top=${fixedScore.topBorderRatio},bottom=${fixedScore.bottomBorderRatio})"
            } else {
                "fixed(dark=${fixedScore.darkRatio},top=${fixedScore.topBorderRatio},bottom=${fixedScore.bottomBorderRatio})/" +
                    "raw(dark=${partial.rawDarkRatio},border=${partial.borderRatio},height=${partial.heightRatio},overlap=${partial.slotOverlapRatio})"
            }
        }
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
        if (fixedButtons != null) {
            FgoLogger.debug(
                tag,
                "Snapped choice panels to fixed FGO layout: raw=${rawButtons.map { it.flattenToString() }} " +
                    "fixed=${fixedButtons.map { it.flattenToString() }}"
            )
            return fixedButtons
        }
        if (rawButtons.isNotEmpty()) {
            FgoLogger.debug(
                tag,
                "Discarding raw choice evidence that did not match a fixed FGO layout: " +
                    rawButtons.map { it.flattenToString() }
            )
        }
        return emptyList()
    }

    /**
     * One-shot report for the continue diamond: strict shape, component evidence and the legacy
     * white-score diagnostic. Computing all three from one pass keeps the per-frame cost identical
     * to the old strict-only check.
     */
    fun dialogueCompleteMarkerReport(bitmap: Bitmap, markerRegion: Rect): DialogueMarkerReport {
        val baseBounds = clampMarkerBounds(bitmap, markerRegion)
        if (baseBounds.width() <= 0 || baseBounds.height() <= 0) {
            return DialogueMarkerReport(
                whitePixels = 0,
                ratio = 0f,
                shapeVisible = false,
                evidence = false
            )
        }

        val markerProfile = completeMarkerColorProfile(bitmap, baseBounds)
        val baseScore = completeMarkerWhiteScore(bitmap, baseBounds, markerProfile)
        val components = markerComponents(bitmap, baseBounds, markerProfile)
        val regionWidth = baseBounds.width()
        val regionHeight = baseBounds.height()
        val shapeVisible = components.any { it.matchesStrictShape(regionWidth, regionHeight) }
        val evidenceComponent = components.firstOrNull { component ->
            component.matchesDiamondEvidence(regionWidth, regionHeight)
        }
        val componentEvidence = evidenceComponent != null
        // Diagnostic only for one validation run. Component evidence is now the sole
        // fallback signal; strict shape remains the primary completion signal.
        val whiteScoreEvidence = baseScore.whitePixels in
            MIN_DIALOGUE_COMPLETE_EVIDENCE_WHITE_PIXELS..MAX_DIALOGUE_COMPLETE_EVIDENCE_WHITE_PIXELS &&
            baseScore.ratio in
            MIN_DIALOGUE_COMPLETE_EVIDENCE_RATIO..MAX_DIALOGUE_COMPLETE_EVIDENCE_RATIO
        val evidence = componentEvidence
        val componentSummary = components
            .joinToString("; ") { component ->
                "px=${component.pixels},size=${component.width}x${component.height}," +
                    "aspect=${component.aspect},fill=${component.compactness}," +
                    "center=${component.centerX.toInt()},${component.centerY.toInt()}," +
                    "edge=${component.touchesRegionEdge(regionWidth, regionHeight)}"
            }
            .ifBlank { "none" }

        FgoLogger.debug(
            tag,
            "Dialogue complete marker visible=$shapeVisible markerRatio=${baseScore.ratio} " +
                "whitePixels=${baseScore.whitePixels} markerShape=$shapeVisible bounds=$baseBounds " +
                "componentEvidence=$componentEvidence whiteScoreEvidence=$whiteScoreEvidence " +
                "evidence=$evidence components=[$componentSummary]"
        )
        return DialogueMarkerReport(
            whitePixels = baseScore.whitePixels,
            ratio = baseScore.ratio,
            shapeVisible = shapeVisible,
            evidence = evidence
        )
    }

    /**
     * Detects FGO's continue diamond after dialogue typing completes.
     */
    fun isDialogueCompleteMarkerVisible(bitmap: Bitmap, markerRegion: Rect): Boolean =
        dialogueCompleteMarkerReport(bitmap, markerRegion).shapeVisible

    private fun clampMarkerBounds(bitmap: Bitmap, markerRegion: Rect): Rect =
        Rect(
            markerRegion.left.coerceIn(0, bitmap.width),
            markerRegion.top.coerceIn(0, bitmap.height),
            markerRegion.right.coerceIn(0, bitmap.width),
            markerRegion.bottom.coerceIn(0, bitmap.height)
        )

    /** One white blob found inside the continue-diamond region. */
    private data class MarkerComponent(
        val pixels: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)
        val compactness: Float
            get() = pixels.toFloat() / (width * height).coerceAtLeast(1).toFloat()
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f

        fun touchesRegionEdge(regionWidth: Int, regionHeight: Int): Boolean =
            minX <= COMPLETE_MARKER_EDGE_GUARD_PX ||
                minY <= COMPLETE_MARKER_EDGE_GUARD_PX ||
                maxX >= regionWidth - 1 - COMPLETE_MARKER_EDGE_GUARD_PX ||
                maxY >= regionHeight - 1 - COMPLETE_MARKER_EDGE_GUARD_PX

        /** The strict test the dialogue-complete gate has always used. */
        fun matchesStrictShape(regionWidth: Int, regionHeight: Int): Boolean {
            val minPixels = maxOf(35, (regionWidth * regionHeight * 0.015f).toInt())
            val minWidth = maxOf(10, (regionWidth * 0.16f).toInt())
            val minHeight = maxOf(18, (regionHeight * 0.34f).toInt())
            val maxWidth = maxOf(minWidth, (regionWidth * 0.78f).toInt())
            val maxHeight = maxOf(minHeight, (regionHeight * 0.98f).toInt())
            return pixels >= minPixels &&
                width in minWidth..maxWidth &&
                height in minHeight..maxHeight &&
                aspect in COMPLETE_MARKER_MIN_ASPECT..COMPLETE_MARKER_MAX_ASPECT &&
                !touchesRegionEdge(regionWidth, regionHeight)
        }

        /**
         * Loose, position-independent evidence for the known jumping diamond graphic.
         * The component may move inside the marker rectangle; only its area, proportions,
         * fill density and edge distance are used here.
         */
        fun matchesDiamondEvidence(regionWidth: Int, regionHeight: Int): Boolean {
            val regionArea = (regionWidth * regionHeight).coerceAtLeast(1)
            val minPixels = maxOf(35, (regionArea * 0.015f).toInt())
            val maxPixels = maxOf(minPixels, (regionArea * 0.55f).toInt())
            val minWidth = maxOf(8, (regionWidth * 0.12f).toInt())
            val maxWidth = maxOf(minWidth, (regionWidth * 0.85f).toInt())
            val minHeight = maxOf(12, (regionHeight * 0.24f).toInt())
            val maxHeight = maxOf(minHeight, (regionHeight * 0.98f).toInt())
            return pixels in minPixels..maxPixels &&
                width in minWidth..maxWidth &&
                height in minHeight..maxHeight &&
                aspect >= COMPONENT_EVIDENCE_MIN_ASPECT &&
                aspect <= COMPONENT_EVIDENCE_MAX_ASPECT &&
                compactness >= COMPONENT_EVIDENCE_MIN_COMPACTNESS &&
                compactness <= COMPONENT_EVIDENCE_MAX_COMPACTNESS &&
                !touchesRegionEdge(regionWidth, regionHeight)
        }

    }

    private fun markerComponents(
        bitmap: Bitmap,
        bounds: Rect,
        markerProfile: MarkerColorProfile?
    ): List<MarkerComponent> {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return emptyList()

        val visited = BooleanArray(width * height)
        val components = mutableListOf<MarkerComponent>()

        fun index(x: Int, y: Int): Int = y * width + x

        for (localY in 0 until height) {
            for (localX in 0 until width) {
                val seedIndex = index(localX, localY)
                if (visited[seedIndex]) continue
                val screenX = bounds.left + localX
                val screenY = bounds.top + localY
                if (!isCompleteMarkerPixel(bitmap.getPixel(screenX, screenY), markerProfile)) {
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
                            if (isCompleteMarkerPixel(bitmap.getPixel(bounds.left + nx, bounds.top + ny), markerProfile)) {
                                queue.add(nx to ny)
                            }
                        }
                    }
                }

                // Sub-pixel specks cannot be the marker: the strict test needs far more pixels.
                if (count >= MIN_MARKER_COMPONENT_PIXELS) {
                    components.add(MarkerComponent(count, minX, minY, maxX, maxY))
                }
            }
        }
        return components
    }

    private fun completeMarkerColorProfile(bitmap: Bitmap, bounds: Rect): MarkerColorProfile? {
        var darkR = 0L
        var darkG = 0L
        var darkB = 0L
        var darkLuminance = 0L
        var darkCount = 0
        var allR = 0L
        var allG = 0L
        var allB = 0L
        var allLuminance = 0L
        var allCount = 0

        for (y in bounds.top until bounds.bottom step 3) {
            for (x in bounds.left until bounds.right step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = luminance(r, g, b)
                allR += r.toLong()
                allG += g.toLong()
                allB += b.toLong()
                allLuminance += luminance.toLong()
                allCount++
                if (luminance <= 118) {
                    darkR += r.toLong()
                    darkG += g.toLong()
                    darkB += b.toLong()
                    darkLuminance += luminance.toLong()
                    darkCount++
                }
            }
        }

        val count = if (darkCount >= 6) darkCount else allCount
        if (count <= 0) return null
        val useDark = darkCount >= 6
        val rSum = if (useDark) darkR else allR
        val gSum = if (useDark) darkG else allG
        val bSum = if (useDark) darkB else allB
        val luminanceSum = if (useDark) darkLuminance else allLuminance
        return MarkerColorProfile(
            r = (rSum / count).toInt(),
            g = (gSum / count).toInt(),
            b = (bSum / count).toInt(),
            luminance = (luminanceSum / count).toInt()
        )
    }

    private fun completeMarkerWhiteScore(
        bitmap: Bitmap,
        bounds: Rect,
        markerProfile: MarkerColorProfile?
    ): WhiteScore {
        var whitePixels = 0
        var totalPixels = 0
        for (y in bounds.top until bounds.bottom step 2) {
            for (x in bounds.left until bounds.right step 2) {
                if (isCompleteMarkerPixel(bitmap.getPixel(x, y), markerProfile)) {
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

    private fun isCompleteMarkerPixel(pixel: Int, markerProfile: MarkerColorProfile? = null): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val channelSpread = maxOf(r, g, b) - minOf(r, g, b)
        val brightNeutral = r >= 176 && g >= 176 && b >= 176 && channelSpread <= 58
        val dimBlueWhite = r >= 118 && g >= 132 && b >= 145 &&
            b >= r + 16 &&
            g >= r + 8 &&
            channelSpread <= 72
        val localBlueWhite = markerProfile != null &&
            r >= 90 && g >= 105 && b >= 120 &&
            luminance(r, g, b) >= markerProfile.luminance + 28 &&
            r >= markerProfile.r + 8 &&
            g >= markerProfile.g + 10 &&
            b >= markerProfile.b + 18 &&
            b >= r + 8 &&
            channelSpread <= 96
        return brightNeutral || dimBlueWhite || localBlueWhite
    }

    private fun luminance(r: Int, g: Int, b: Int): Int {
        return (r * 299 + g * 587 + b * 114) / 1000
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
        if (fixedSlotLayouts.isEmpty() || rawButtons.isEmpty()) return null

        val candidate = fixedSlotLayouts
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }
            .mapNotNull { layout ->
                val clippedLayout = layout.mapNotNull { clippedToBitmap(it, bitmap) }
                if (clippedLayout.size != layout.size) return@mapNotNull null
                val matches = clippedLayout.map { slot -> fixedChoiceSlotMatch(bitmap, rawButtons, slot) }
                if (!matches.all { it.isVisible }) return@mapNotNull null
                if (!matches.any { it.hasRawEvidence }) return@mapNotNull null

                FixedChoiceLayoutCandidate(clippedLayout, matches)
            }
            .maxWithOrNull(
                compareBy<FixedChoiceLayoutCandidate> { it.slots.size }
                    .thenBy { it.combinedScore }
            )

        if (candidate != null) {
            FgoLogger.debug(
                tag,
                "Fixed choice slot signature accepted: count=${candidate.slots.size}, " +
                    "scores=${candidate.matches.map { it.debugString() }}"
            )
            return candidate.slots
        }

        return null
    }

    private fun fixedChoiceSlotMatch(
        bitmap: Bitmap,
        rawButtons: List<Rect>,
        slot: Rect
    ): FixedChoiceSlotMatch {
        val fixedScore = fixedChoiceSlotScore(bitmap, slot)
        val partialScore = rawButtons
            .filter { raw -> verticalOverlapRatio(raw, slot) >= MIN_FIXED_CHOICE_RAW_OVERLAP_RATIO }
            .map { raw -> partialFixedChoiceSlotScore(bitmap, raw, slot) }
            .maxByOrNull { it.combinedScore }
        return FixedChoiceSlotMatch(fixedScore, partialScore)
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

    private fun partialFixedChoiceSlotScore(bitmap: Bitmap, rawButton: Rect, slot: Rect): PartialChoiceSlotScore {
        val raw = clippedToBitmap(rawButton, bitmap) ?: return PartialChoiceSlotScore(0f, 0f, 0f, 0f, 1f)
        val fixed = clippedToBitmap(slot, bitmap) ?: return PartialChoiceSlotScore(0f, 0f, 0f, 0f, 1f)
        if (raw.height() <= 0 || fixed.height() <= 0 || fixed.width() <= 0) {
            return PartialChoiceSlotScore(0f, 0f, 0f, 0f, 1f)
        }

        val rawDarkBounds = Rect(raw).apply {
            val verticalInset = (height() * 0.16f).toInt().coerceAtLeast(4)
            top += verticalInset
            bottom -= verticalInset
        }
        val rawDarkRatio = if (rawDarkBounds.height() > 0) {
            darkRatioInRect(bitmap, rawDarkBounds)
        } else {
            darkRatioInRect(bitmap, raw)
        }

        val horizontalInset = (fixed.width() * 0.035f).toInt().coerceAtLeast(12)
        val borderBandHeight = (fixed.height() * 0.08f).toInt().coerceIn(5, 12)
        val topFixedBorder = horizontalBand(
            fixed = fixed,
            centerY = fixed.top + borderBandHeight / 2,
            horizontalInset = horizontalInset,
            bandHeight = borderBandHeight
        )
        val bottomFixedBorder = horizontalBand(
            fixed = fixed,
            centerY = fixed.bottom - borderBandHeight / 2,
            horizontalInset = horizontalInset,
            bandHeight = borderBandHeight
        )
        val topRawBorder = horizontalBand(
            fixed = fixed,
            centerY = raw.top + borderBandHeight / 2,
            horizontalInset = horizontalInset,
            bandHeight = borderBandHeight
        )
        val bottomRawBorder = horizontalBand(
            fixed = fixed,
            centerY = raw.bottom - borderBandHeight / 2,
            horizontalInset = horizontalInset,
            bandHeight = borderBandHeight
        )
        val borderRatio = maxOf(
            choiceBorderRatioInRect(bitmap, topFixedBorder),
            choiceBorderRatioInRect(bitmap, bottomFixedBorder),
            choiceBorderRatioInRect(bitmap, topRawBorder),
            choiceBorderRatioInRect(bitmap, bottomRawBorder)
        )

        return PartialChoiceSlotScore(
            rawDarkRatio = rawDarkRatio,
            borderRatio = borderRatio,
            widthRatio = raw.width().toFloat() / fixed.width().coerceAtLeast(1),
            heightRatio = raw.height().toFloat() / fixed.height().coerceAtLeast(1),
            slotOverlapRatio = verticalOverlapRatio(raw, fixed)
        )
    }

    private fun horizontalBand(
        fixed: Rect,
        centerY: Int,
        horizontalInset: Int,
        bandHeight: Int
    ): Rect {
        val halfHeight = bandHeight / 2
        return Rect(
            fixed.left + horizontalInset,
            centerY - halfHeight,
            fixed.right - horizontalInset,
            centerY - halfHeight + bandHeight
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

/**
 * Result of one continue-diamond check.
 *
 * @property whitePixels sampled white marker pixels inside the fixed marker region
 * @property ratio sampled white ratio; menu panels and other bright UI push it far higher
 * @property shapeVisible the strict diamond shape used as the primary completion signal
 * @property evidence component-based evidence used by the three-frame completion fallback
 */
data class DialogueMarkerReport(
    val whitePixels: Int,
    val ratio: Float,
    val shapeVisible: Boolean,
    val evidence: Boolean
)
