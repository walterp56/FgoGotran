package com.fgogotran.runner

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable admission stamp, inherited across dispatcher changes, never recaptured after awaits.
 * Identity also separates accessibility bindings within the same runner session.
 */
class RunnerSessionContext(val token: Long) :
    ThreadContextElement<RunnerSessionContext?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunnerSessionContext> {
        private val local = ThreadLocal<RunnerSessionContext?>()
        fun current(): RunnerSessionContext? = local.get()
    }

    override fun updateThreadContext(context: CoroutineContext): RunnerSessionContext? =
        local.get().also { local.set(this) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: RunnerSessionContext?) {
        local.set(oldState)
    }
}
