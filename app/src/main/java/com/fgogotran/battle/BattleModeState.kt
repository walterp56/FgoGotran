package com.fgogotran.battle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares only the confirmed battle-mode indicator with UI owned by another service. */
@Singleton
class BattleModeState @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    internal fun setActive(active: Boolean) {
        _active.value = active
    }
}
