package com.fgogotran.subtitle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ScreenOcrWorkflow { STORY, BATTLE_SUBTITLE, DIALOGUE_SUBTITLE }

/** One session-scoped selection: two passive OCR workflows can never be enabled together. */
@Singleton
class ScreenOcrWorkflowState @Inject constructor() {
    private val selection = MutableStateFlow(ScreenOcrWorkflow.STORY)
    val selected = selection.asStateFlow()
    val isBattle: Boolean get() = selected.value == ScreenOcrWorkflow.BATTLE_SUBTITLE
    val isDialogue: Boolean get() = selected.value == ScreenOcrWorkflow.DIALOGUE_SUBTITLE
    val isSubtitle: Boolean get() = selected.value != ScreenOcrWorkflow.STORY

    fun select(workflow: ScreenOcrWorkflow) { selection.value = workflow }
}
