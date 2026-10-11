package com.fgogotran.translation

import java.util.concurrent.atomic.AtomicLong

/** Attribution only, never cached or sent with the translation prompt. */
data class TranslationApiUsage(val backend: String, val completionOrder: Long) {
    companion object {
        private val completions = AtomicLong()

        fun completed(backend: String) = TranslationApiUsage(backend, completions.incrementAndGet())

        fun latest(results: List<TranslateResult>): TranslationApiUsage? =
            results.mapNotNull { it.apiUsage }.maxByOrNull { it.completionOrder }
    }
}
