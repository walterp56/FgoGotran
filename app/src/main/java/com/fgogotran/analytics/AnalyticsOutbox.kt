package com.fgogotran.analytics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate

/** Shared with the Phase 1 receiver. No settings selections or scene text belong here. */
object AnalyticsProtocol {
    const val MAX_DATES = 31
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_ACK_BYTES = 8 * 1024
    const val MAX_REVISION = 9_007_199_254_740_991L
    val languages = setOf("zh-Hans", "zh-Hant", "en")
    val servers = setOf("jp", "cn", "tw", "en")
    val modes = setOf("manual", "semi_auto", "auto", "not_used", "unknown")
    val backends = setOf("deepseek", "zhipu", "qwen", "gpt", "gemini", "claude", "custom_openai", "not_used", "unknown")
    val wireJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    val storageJson = Json { encodeDefaults = true }
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun validInstallId(value: String) = uuid.matches(value)

    fun validDate(value: String): Boolean = runCatching {
        Regex("\\d{4}-\\d{2}-\\d{2}").matches(value) && LocalDate.parse(value).toString() == value
    }.getOrDefault(false)

    fun encode(payload: AnalyticsReport): String = wireJson.encodeToString(payload)

    fun fingerprint(payload: AnalyticsReport): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(payload).encodeToByteArray()).joinToString("") { "%02x".format(it) }

    fun validAck(body: String, submitted: AnalyticsReport): Boolean = runCatching {
        if (body.encodeToByteArray().size > MAX_ACK_BYTES) return false
        val response = wireJson.decodeFromString<AnalyticsAcknowledgement>(body)
        response.schemaVersion == 2 && response.ok &&
            response.ack.stateRevision == submitted.state.revision &&
            response.ack.dailyDates.size == submitted.daily.size &&
            response.ack.dailyDates.toSet() == submitted.daily.map { it.date }.toSet()
    }.getOrDefault(false)
}

data class AnalyticsProfile(val appVersion: String, val appVersionCode: Long, val uiLanguage: String) {
    init {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,39}").matches(appVersion))
        require(appVersionCode in 0..AnalyticsProtocol.MAX_REVISION)
        require(uiLanguage in AnalyticsProtocol.languages)
    }
}

@Serializable
data class AnalyticsState(
    val revision: Long,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_version_code") val appVersionCode: Long,
    @SerialName("ui_language") val uiLanguage: String,
    @SerialName("last_successful_mode") val lastSuccessfulMode: String? = null,
    @SerialName("last_successful_backend") val lastSuccessfulBackend: String? = null
) {
    init {
        require(revision in 0..AnalyticsProtocol.MAX_REVISION)
        AnalyticsProfile(appVersion, appVersionCode, uiLanguage)
        require(lastSuccessfulMode == null || lastSuccessfulMode in AnalyticsProtocol.modes)
        require(lastSuccessfulBackend == null || lastSuccessfulBackend in AnalyticsProtocol.backends)
    }
}

@Serializable
data class AnalyticsDailyFacts(
    val date: String,
    @SerialName("app_opened") val appOpened: Boolean = false,
    @SerialName("game_servers") val gameServers: Set<String> = emptySet(),
    @SerialName("translation_targets") val translationTargets: Set<String> = emptySet(),
    @SerialName("voice_servers") val voiceServers: Set<String> = emptySet()
) {
    init {
        require(AnalyticsProtocol.validDate(date))
        require(gameServers.all { it in AnalyticsProtocol.servers })
        require(translationTargets.all { it in AnalyticsProtocol.languages })
        require(voiceServers.all { it in AnalyticsProtocol.servers })
    }

    fun isEmpty() = !appOpened && gameServers.isEmpty() && translationTargets.isEmpty() && voiceServers.isEmpty()

    fun union(other: AnalyticsDailyFacts): AnalyticsDailyFacts {
        require(date == other.date)
        return copy(
            appOpened = appOpened || other.appOpened,
            gameServers = (gameServers + other.gameServers).toSortedSet(),
            translationTargets = (translationTargets + other.translationTargets).toSortedSet(),
            voiceServers = (voiceServers + other.voiceServers).toSortedSet()
        )
    }

    fun minus(other: AnalyticsDailyFacts): AnalyticsDailyFacts {
        require(date == other.date)
        return copy(
            appOpened = appOpened && !other.appOpened,
            gameServers = (gameServers - other.gameServers).toSortedSet(),
            translationTargets = (translationTargets - other.translationTargets).toSortedSet(),
            voiceServers = (voiceServers - other.voiceServers).toSortedSet()
        )
    }
}

@Serializable
data class AnalyticsDayLedger(
    val known: AnalyticsDailyFacts,
    val acknowledged: AnalyticsDailyFacts = AnalyticsDailyFacts(known.date)
) {
    init { require(known.date == acknowledged.date) }
    fun pending() = known.minus(acknowledged)
}

@Serializable
data class AnalyticsReport(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("install_id") val installId: String,
    val state: AnalyticsState,
    val daily: List<AnalyticsDailyFacts> = emptyList()
)

@Serializable
private data class AnalyticsAcknowledgement(
    @SerialName("schema_version") val schemaVersion: Int,
    val ok: Boolean,
    val ack: AnalyticsAck
)

@Serializable
private data class AnalyticsAck(
    @SerialName("state_revision") val stateRevision: Long,
    @SerialName("daily_dates") val dailyDates: List<String>
)

/** One latest state and merged daily facts, never a scene/event history. */
@Serializable
data class AnalyticsOutbox(
    val installId: String,
    val state: AnalyticsState? = null,
    val acknowledgedRevision: Long = -1,
    val days: Map<String, AnalyticsDayLedger> = emptyMap(),
    val rejectedSnapshotHash: String? = null,
    val rejectedHttpStatus: Int? = null
) {
    init {
        require(AnalyticsProtocol.validInstallId(installId))
        require(acknowledgedRevision in -1..(state?.revision ?: -1))
        require(days.all { (date, ledger) -> date == ledger.known.date })
    }

    fun withProfile(profile: AnalyticsProfile): AnalyticsOutbox {
        val previous = state
        if (previous?.appVersion == profile.appVersion && previous.appVersionCode == profile.appVersionCode &&
            previous.uiLanguage == profile.uiLanguage) return this
        val revision = (previous?.revision ?: 0) + 1
        require(revision <= AnalyticsProtocol.MAX_REVISION)
        return copy(state = AnalyticsState(
            revision, profile.appVersion, profile.appVersionCode, profile.uiLanguage,
            previous?.lastSuccessfulMode, previous?.lastSuccessfulBackend
        ))
    }

    fun withDaily(facts: AnalyticsDailyFacts): AnalyticsOutbox {
        if (facts.isEmpty()) return this
        val previous = days[facts.date] ?: AnalyticsDayLedger(AnalyticsDailyFacts(facts.date))
        return copy(days = days + (facts.date to previous.copy(known = previous.known.union(facts))))
    }

    fun withSuccess(success: AnalyticsSuccess): AnalyticsOutbox {
        val previous = checkNotNull(state) { "Profile must exist before success recording" }
        val mode = success.mode ?: previous.lastSuccessfulMode
        val backend = success.backend ?: previous.lastSuccessfulBackend
        val recorded = if (mode == previous.lastSuccessfulMode && backend == previous.lastSuccessfulBackend) this
        else copy(state = previous.copy(
            revision = previous.revision + 1, lastSuccessfulMode = mode, lastSuccessfulBackend = backend
        ))
        return recorded.withDaily(success.daily)
    }

    fun onForeground(profile: AnalyticsProfile, date: String): AnalyticsOutbox {
        val recorded = withProfile(profile).withDaily(AnalyticsDailyFacts(date, appOpened = true))
        // Only old, fully acknowledged summaries may be discarded. Offline facts never expire.
        return recorded.copy(days = recorded.days.filter { (day, ledger) -> day >= date || !ledger.pending().isEmpty() })
    }

    fun nextReport(maxDates: Int = AnalyticsProtocol.MAX_DATES, maxBytes: Int = AnalyticsProtocol.MAX_BODY_BYTES): AnalyticsReport? {
        require(maxDates in 1..AnalyticsProtocol.MAX_DATES)
        val latest = state ?: return null
        val pending = days.toSortedMap().values.map { it.pending() }.filterNot { it.isEmpty() }
        if (latest.revision <= acknowledgedRevision && pending.isEmpty()) return null
        var report = AnalyticsReport(2, installId, latest)
        require(AnalyticsProtocol.encode(report).encodeToByteArray().size <= maxBytes)
        for (day in pending.take(maxDates)) {
            val expanded = report.copy(daily = report.daily + day)
            if (AnalyticsProtocol.encode(expanded).encodeToByteArray().size > maxBytes) {
                require(report.daily.isNotEmpty()) { "Analytics day exceeds request size" }
                break
            }
            report = expanded
        }
        return report
    }

    fun isQuarantined(report: AnalyticsReport) = rejectedSnapshotHash == AnalyticsProtocol.fingerprint(report)

    fun quarantine(report: AnalyticsReport, status: Int): AnalyticsOutbox = copy(
        rejectedSnapshotHash = AnalyticsProtocol.fingerprint(report), rejectedHttpStatus = status
    )

    fun acknowledge(report: AnalyticsReport): AnalyticsOutbox {
        require(report.installId == installId && report.state.revision <= (state?.revision ?: -1))
        val updated = days.toMutableMap()
        for (submitted in report.daily) {
            val current = updated[submitted.date] ?: continue
            updated[submitted.date] = current.copy(acknowledged = current.acknowledged.union(submitted))
        }
        return copy(
            acknowledgedRevision = maxOf(acknowledgedRevision, report.state.revision),
            days = updated, rejectedSnapshotHash = null, rejectedHttpStatus = null
        )
    }
}

/** Implementations must apply read-modify-write atomically, without holding a lock during HTTP. */
interface AnalyticsOutboxStore {
    suspend fun readAnalyticsOutbox(): AnalyticsOutbox
    suspend fun updateAnalyticsOutbox(change: (AnalyticsOutbox) -> AnalyticsOutbox): AnalyticsOutbox
}
