package com.fgogotran.battle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Session-scoped battle OCR selection shared by the runner and accessibility services. */
@Singleton
class BattleModeState @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun setEnabled(active: Boolean) {
        _active.value = active
    }
}
