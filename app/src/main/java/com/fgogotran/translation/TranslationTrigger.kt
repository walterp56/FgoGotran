package com.fgogotran.translation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge between the draggable overlay button and the accessibility pipeline.
 */
object TranslationTrigger {
    private val pending = AtomicBoolean(false)
    private val menuDismissSettleRequired = AtomicBoolean(false)
    private val autoTranslateEnabled = AtomicBoolean(false)

    fun requestTranslation(afterMenuDismiss: Boolean = false) {
        if (autoTranslateEnabled.get()) return
        menuDismissSettleRequired.set(afterMenuDismiss)
        pending.set(true)
    }

    fun cancelPendingTranslation() {
        pending.set(false)
        menuDismissSettleRequired.set(false)
    }

    fun consumeRequest(): Boolean {
        return pending.getAndSet(false)
    }

    fun consumeMenuDismissSettleRequired(): Boolean {
        return menuDismissSettleRequired.getAndSet(false)
    }

    fun setAutoTranslateEnabled(enabled: Boolean) {
        autoTranslateEnabled.set(enabled)
        if (enabled) {
            cancelPendingTranslation()
        }
    }

    fun isAutoTranslateEnabled(): Boolean {
        return autoTranslateEnabled.get()
    }
}
