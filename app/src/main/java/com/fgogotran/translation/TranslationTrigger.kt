package com.fgogotran.translation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class TranslationMode {
    MANUAL,
    SEMI_AUTO,
    AUTO
}

/**
 * Bridge between the draggable overlay button and the accessibility pipeline.
 */
object TranslationTrigger {
    private val pending = AtomicBoolean(false)
    private val menuDismissSettleRequired = AtomicBoolean(false)
    private val translationMode = AtomicReference(TranslationMode.MANUAL)
    private val historyVisible = AtomicBoolean(false)
    private val menuVisible = AtomicBoolean(false)

    fun requestTranslation(afterMenuDismiss: Boolean = false) {
        if (!canUserTapTranslate()) return
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

    fun setTranslationMode(mode: TranslationMode) {
        translationMode.set(mode)
        cancelPendingTranslation()
    }

    fun translationMode(): TranslationMode {
        return translationMode.get()
    }

    fun isFullAutoEnabled(): Boolean {
        return translationMode.get() == TranslationMode.AUTO
    }

    fun isSemiAutoEnabled(): Boolean {
        return translationMode.get() == TranslationMode.SEMI_AUTO
    }

    fun canUserTapTranslate(): Boolean {
        return translationMode.get() != TranslationMode.AUTO
    }

    fun isAutoTranslateEnabled(): Boolean {
        return isFullAutoEnabled()
    }

    fun setHistoryVisible(visible: Boolean) {
        historyVisible.set(visible)
    }

    fun setMenuVisible(visible: Boolean) {
        menuVisible.set(visible)
    }

    fun isUiBlockingOcr(): Boolean {
        return historyVisible.get() || menuVisible.get()
    }
}
