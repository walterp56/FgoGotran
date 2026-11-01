package com.fgogotran.chapter.install

import kotlinx.coroutines.*
import kotlin.test.*

class ChapterOperationLauncherTest {
    @Test fun `document result supersedes pending foreground reload`() = runBlocking {
        var state = ChapterAssetTestState()
        val launcher = ChapterOperationLauncher(this) { state }
        val entered = CompletableDeferred<Unit>()
        var released = false
        launcher.start(reload = true) {
            state = state.copy(busy = true)
            try { entered.complete(Unit); awaitCancellation() }
            finally { state = state.copy(busy = false); released = true }
        }
        entered.await()
        val exported = CompletableDeferred<Unit>()
        launcher.start {
            assertTrue(released)
            assertFalse(state.busy)
            exported.complete(Unit)
        }
        withTimeout(2_000) { exported.await() }
    }

    @Test fun `reload and repeated actions cannot interrupt an explicit action`() = runBlocking {
        val launcher = ChapterOperationLauncher(this) { ChapterAssetTestState() }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var ignored = 0
        launcher.start { entered.complete(Unit); finish.await() }
        entered.await()
        launcher.start(reload = true) { ignored++ }
        launcher.start { ignored++ }
        yield()
        assertEquals(0, ignored)
        finish.complete(Unit)
        Unit
    }

    @Test fun `background cancels preparation but preserves a critical operation`() = runBlocking {
        var state = ChapterAssetTestState()
        val launcher = ChapterOperationLauncher(this) { state }
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        launcher.start {
            try { entered.complete(Unit); awaitCancellation() }
            finally { cancelled.complete(Unit) }
        }
        entered.await()
        launcher.background()
        withTimeout(2_000) { cancelled.await() }
        yield()
        val criticalEntered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        launcher.start {
            state = state.copy(critical = true)
            criticalEntered.complete(Unit)
            finish.await()
            finished.complete(Unit)
        }
        criticalEntered.await()
        launcher.background()
        yield()
        assertFalse(finished.isCompleted)
        finish.complete(Unit)
        withTimeout(2_000) { finished.await() }
    }
}
