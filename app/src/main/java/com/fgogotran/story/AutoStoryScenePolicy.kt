package com.fgogotran.story

/** AUTO routing only. Detector thresholds, OCR and choice recognition remain independent. */
internal object AutoStoryScenePolicy {
    enum class Stage { WAITING, DIALOGUE, CHOICES }

    fun select(strictDiamond: Boolean, hasChoices: Boolean, fallbackDialogueComplete: Boolean): Stage = when {
        strictDiamond -> Stage.DIALOGUE
        hasChoices -> Stage.CHOICES
        fallbackDialogueComplete -> Stage.DIALOGUE
        else -> Stage.WAITING
    }

    /** A request/result can own exactly one kind of content, including its speaker name. */
    fun contentStage(hasName: Boolean, hasDialogue: Boolean, hasChoices: Boolean): Stage? = when {
        hasDialogue && !hasChoices -> Stage.DIALOGUE
        hasChoices && !hasDialogue && !hasName -> Stage.CHOICES
        else -> null
    }

    fun acceptsResult(expected: Stage, observed: Stage): Boolean =
        expected != Stage.WAITING && expected == observed
}
