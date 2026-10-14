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
    data class PendingRequest(val runnerToken: Long, val afterMenuDismiss: Boolean)
    private val pending = AtomicReference<PendingRequest?>(null)
    private val translationMode = AtomicReference(TranslationMode.MANUAL)
    private val historyVisible = AtomicBoolean(false)
    private val menuVisible = AtomicBoolean(false)

    fun requestTranslation(runnerToken: Long, afterMenuDismiss: Boolean = false) {
        if (!canUserTapTranslate()) return
        pending.set(PendingRequest(runnerToken, afterMenuDismiss))
    }

    fun cancelPendingTranslation() {
        pending.set(null)
    }

    fun consumeRequest(runnerToken: Long): PendingRequest? {
        return pending.getAndSet(null)?.takeIf { it.runnerToken == runnerToken }
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
