package com.fgogotran.analytics

import com.fgogotran.translation.TranslateResult
import com.fgogotran.translation.TranslationApiUsage

/** Captured at the originating workflow, not read back from settings after delivery. */
data class AnalyticsUsageOrigin(val server: String, val normalMode: String? = null) {
    init {
        require(server in AnalyticsProtocol.servers)
        require(normalMode == null || normalMode in setOf("manual", "semi_auto", "auto"))
    }
}

/** Small delivery fact; no scene text, audio, model name or credentials enter the recorder. */
data class AnalyticsSuccess(
    val daily: AnalyticsDailyFacts,
    val mode: String?,
    val backend: String? = null
) {
    init {
        require(mode == null || mode in setOf("manual", "semi_auto", "auto"))
        require(backend == null || backend in AnalyticsProtocol.backends - setOf("unknown", "not_used"))
    }

    companion object {
        fun translation(date: String, origin: AnalyticsUsageOrigin, displayed: List<TranslateResult>): AnalyticsSuccess? {
            val successful = displayed.filter { it.countsAsUsage && !it.isFailure &&
                it.backend != "none" && it.translatedText.isNotBlank() }
            if (successful.isEmpty()) return null
            return AnalyticsSuccess(
                AnalyticsDailyFacts(date, gameServers = setOf(origin.server),
                    translationTargets = successful.map { it.targetLocale }.toSet()),
                origin.normalMode,
                TranslationApiUsage.latest(successful)?.backend
            )
        }

        fun voice(date: String, origin: AnalyticsUsageOrigin) = AnalyticsSuccess(
            AnalyticsDailyFacts(date, gameServers = setOf(origin.server), voiceServers = setOf(origin.server)),
            origin.normalMode
        )
    }
}
