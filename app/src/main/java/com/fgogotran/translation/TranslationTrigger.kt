package com.fgogotran.translation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge between the draggable overlay button and the accessibility pipeline.
 */
object TranslationTrigger {
    private val pending = AtomicBoolean(false)

    fun requestTranslation() {
        pending.set(true)
    }

    fun consumeRequest(): Boolean {
        return pending.getAndSet(false)
    }
}
