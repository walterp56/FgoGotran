package com.fgogotran.story

/** Processing-call-local owner: the caller owns the original; this scope owns only its retry. */
internal class ChoiceRetrySource<T : Any>(
    private val original: T,
    private val release: (T) -> Unit
) : AutoCloseable {
    var source: T = original
        private set
    private var retry: T? = null
    private var closed = false

    fun useRetry(frame: T) {
        check(!closed && retry == null && frame !== original)
        retry = frame
        source = frame
    }

    override fun close() {
        if (closed) return
        closed = true
        val owned = retry
        retry = null
        source = original
        owned?.let(release)
    }
}
