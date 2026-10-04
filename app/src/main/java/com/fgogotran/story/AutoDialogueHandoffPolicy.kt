package com.fgogotran.story

import com.fgogotran.overlay.DialogueRenderTextPolicy
import com.fgogotran.overlay.FgoReferenceRect

/** Narrow exception to the generic two-row story heuristic during an AUTO tap handoff. */
internal object AutoDialogueHandoffPolicy {
    /** Inputs are already accepted OCR and main-row bounds from the same completed capture. */
    fun canAcceptSingleRow(
        strictDialogueComplete: Boolean,
        hasChoices: Boolean,
        dialogueText: String?,
        dialogueCrop: FgoReferenceRect,
        dialogueOcrBounds: List<FgoReferenceRect>,
        mainLineBounds: List<DialogueRenderTextPolicy.LineBounds>
    ): Boolean {
        if (!strictDialogueComplete || hasChoices || dialogueText.isNullOrBlank()) return false
        if (dialogueCrop.width <= 0 || dialogueCrop.height <= 0) return false
        if (dialogueOcrBounds.isEmpty() || dialogueOcrBounds.any { line ->
                line.width <= 0 || line.height <= 0 ||
                    line.left < dialogueCrop.left || line.right > dialogueCrop.right ||
                    line.top < dialogueCrop.top || line.bottom > dialogueCrop.bottom
            }
        ) return false
        if (mainLineBounds.isEmpty() || mainLineBounds.any { line ->
                line.bottom <= line.top ||
                    line.top < dialogueCrop.top || line.bottom > dialogueCrop.bottom
            }
        ) return false

        // Speaker boxes are never supplied here; paired ruby is already excluded from main rows.
        // Do not require Japanese words: accepted ellipsis/dash-only dialogue is real content too.
        return DialogueRenderTextPolicy.visualRowCount(mainLineBounds) == 1
    }
}
