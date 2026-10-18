package com.fgogotran.chapter.install

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Explicit actions take priority over a lifecycle-only reload, never over another action. */
internal class ChapterOperationLauncher(
    private val scope: CoroutineScope,
    private val state: () -> ChapterAssetTestState
) {
    private var work: Job? = null
    private var reloading = false

    @Synchronized
    fun start(reload: Boolean = false, block: suspend () -> Unit) {
        val previous = work
        if (previous?.isActive == true || state().busy) {
            if (reload || !reloading) return
            previous?.cancel()
        }
        reloading = reload
        work = scope.launch {
            // Let reload release the session mutex before running an activity-result action.
            previous?.join()
            block()
        }
    }

    @Synchronized
    fun background() {
        if (!state().critical) work?.cancel()
    }
}
