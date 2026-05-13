package com.fgogotran.story

import android.graphics.Rect
import com.fgogotran.ocr.OcrTextLine
import com.fgogotran.util.FgoLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the story scene detection heuristic.
 * @param isStoryScene whether the screen is likely a story/dialogue scene
 * @param confidence 0.0–1.0 how confident the detector is (0.95 = name label present, 0.7 = weak signal)
 * @param reason diagnostic string with detection metrics (for logcat debugging)
 */
data class StoryDetectionResult(
    val isStoryScene: Boolean,
    val confidence: Float,
    val reason: String
)

/**
 * Determines whether the current screen shows a story/dialogue scene.
 *
 * ## Heuristic
 * In FGO story scenes, dialogue text is tightly concentrated in the bottom ~30% of the screen
 * (the dialogue box). The top ~70% is character art and background.
 * In battle/UI screens, text is scattered across the screen (HP bars, skill names, etc.).
 *
 * ## Detection signals (cascading confidence model)
 * 1. **High concentration + name label present** → 0.95 confidence (strongest signal)
 * 2. **High concentration alone** → 0.80 confidence
 * 3. **4+ lines in dialogue zone** → 0.70 confidence (weak fallback)
 * 4. Otherwise → not a story scene
 *
 * ## Edge cases
 * - Craft Essence descriptions, servant profiles, and shop menus also have bottom-zone text
 *   but lack the name label, so they get lower confidence
 * - Battle Noble Phantasm cut-ins have text in the middle zone, not bottom, and are filtered out
 */
@Singleton
class StoryDetector @Inject constructor() {

    companion object {
        /**
         * FGO places its dialogue box in the bottom ~30% of the screen.
         * The remaining 70% is character art, background, and UI chrome.
         */
        private const val DIALOGUE_ZONE_BOTTOM_RATIO = 0.23f

        /** Minimum number of text lines that must be in the dialogue zone. */
        private const val MIN_LINES_IN_DIALOGUE_ZONE = 2

        /**
         * Ratio of dialogue-zone lines to total lines.
         * 60%+ means most text is in the bottom zone → likely a story scene.
         * In battle, text is scattered, so the ratio is much lower.
         */
        private const val DIALOGUE_CONCENTRATION_THRESHOLD = 0.6f
    }

    private val tag = "StoryDetector"

    /**
     * Analyzes OCR lines to determine if the current screen is a story scene.
     *
     * @param lines OCR text lines with bounding boxes
     * @param screenWidth screen width in pixels (for reference, not used in current heuristic)
     * @param screenHeight screen height in pixels
     */
    fun detect(
        lines: List<OcrTextLine>,
        screenWidth: Int,
        screenHeight: Int
    ): StoryDetectionResult {
        if (lines.isEmpty()) {
            return StoryDetectionResult(false, 0f, "No text detected")
        }

        // Dialogue zone is the bottom strip of the screen
        val dialogueZoneTop = (screenHeight * (1f - DIALOGUE_ZONE_BOTTOM_RATIO)).toInt()

        // Lines whose vertical center falls within the dialogue zone
        val dialogueLines = lines.filter { line ->
            line.boundingBox.centerY() >= dialogueZoneTop
        }

        val concentrationRatio = dialogueLines.size.toFloat() / lines.size.toFloat()

        // Text lines in the middle of the screen (between 25% and dialogue zone top).
        // These could be choice buttons or non-story UI elements.
        val middleZoneTop = (screenHeight * 0.25).toInt()
        val middleZoneBottom = dialogueZoneTop
        val middleLines = lines.filter { line ->
            val cy = line.boundingBox.centerY()
            cy in middleZoneTop..middleZoneBottom
        }

        // Name label: small text block in the top-left area just above the dialogue zone.
        // FGO places character names here (e.g., "マシュ・キリエライト").
        val nameLabelCandidates = lines.filter { line ->
            val box = line.boundingBox
            val cy = box.centerY()
            val cx = box.centerX()
            // Within 100px above dialogue zone, in the left 40% of screen, ≤10 chars
            cy in (dialogueZoneTop - 100)..dialogueZoneTop &&
            cx < screenWidth * 0.4f &&
            line.text.length <= 10
        }

        val hasEnoughDialogueLines = dialogueLines.size >= MIN_LINES_IN_DIALOGUE_ZONE
        val highConcentration = concentrationRatio >= DIALOGUE_CONCENTRATION_THRESHOLD

        // Cascading confidence model: more signals → higher confidence
        val isStory = when {
            hasEnoughDialogueLines && highConcentration -> true
            hasEnoughDialogueLines && nameLabelCandidates.isNotEmpty() -> true
            dialogueLines.size >= 4 -> true  // Weak fallback
            else -> false
        }

        val confidence = when {
            hasEnoughDialogueLines && highConcentration && nameLabelCandidates.isNotEmpty() -> 0.95f
            hasEnoughDialogueLines && highConcentration -> 0.8f
            dialogueLines.size >= 4 -> 0.7f
            else -> concentrationRatio
        }

        val reason = buildString {
            append("dialogue_lines=${dialogueLines.size} ")
            append("total_lines=${lines.size} ")
            append("concentration=${"%.2f".format(concentrationRatio)} ")
            append("middle_lines=${middleLines.size} ")
            append("name_candidates=${nameLabelCandidates.size}")
        }

        FgoLogger.debug(tag, "detect: isStory=$isStory, conf=$confidence, $reason")
        return StoryDetectionResult(isStory, confidence, reason)
    }
}
