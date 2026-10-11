package com.fgogotran.analytics

import kotlinx.serialization.encodeToString
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val ANALYTICS_TEST_ID = "12345678-1234-1234-1234-123456789ABC"
internal val ANALYTICS_TEST_PROFILE = AnalyticsProfile("4.1.0", 16, "zh-Hant")

internal fun pendingAnalytics(): AnalyticsOutbox = AnalyticsOutbox(ANALYTICS_TEST_ID)
    .onForeground(ANALYTICS_TEST_PROFILE, "2026-10-11")

internal fun ackBody(report: AnalyticsReport): String =
    """{"schema_version":2,"ok":true,"ack":{"state_revision":${report.state.revision},"daily_dates":[${report.daily.joinToString(",") { "\"${it.date}\"" }}]}}"""

class AnalyticsOutboxTest {
    @Test
    fun `first foreground records profile and app open without selected usage`() {
        val report = assertNotNull(pendingAnalytics().nextReport())
        assertEquals(ANALYTICS_TEST_ID, report.installId)
        assertEquals(1, report.state.revision)
        assertEquals("zh-Hant", report.state.uiLanguage)
        assertNull(report.state.lastSuccessfulMode)
        assertNull(report.state.lastSuccessfulBackend)
        assertEquals(listOf(AnalyticsDailyFacts("2026-10-11", appOpened = true)), report.daily)
        val json = AnalyticsProtocol.encode(report)
        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"app_version_code\":16"))
        assertFalse(json.contains("last_successful"))
        assertFalse(json.contains("game_servers"))
    }

    @Test
    fun `unchanged foreground after ack does not send another daily request`() {
        val original = pendingAnalytics()
        val acknowledged = original.acknowledge(assertNotNull(original.nextReport()))
        assertNull(acknowledged.onForeground(ANALYTICS_TEST_PROFILE, "2026-10-11").nextReport())
        val tomorrow = acknowledged.onForeground(ANALYTICS_TEST_PROFILE, "2026-10-12")
        val report = assertNotNull(tomorrow.nextReport())
        assertEquals(1, report.state.revision)
        assertEquals(listOf("2026-10-12"), report.daily.map { it.date })
        assertEquals(setOf("2026-10-12"), tomorrow.days.keys)
    }

    @Test
    fun `profile changes remain reportable after daily ack including version downgrade`() {
        val original = pendingAnalytics()
        val acknowledged = original.acknowledge(assertNotNull(original.nextReport()))
        val changed = acknowledged.withProfile(AnalyticsProfile("4.0.0", 15, "en"))
        val report = assertNotNull(changed.nextReport())
        assertEquals(2, report.state.revision)
        assertEquals("4.0.0", report.state.appVersion)
        assertEquals("en", report.state.uiLanguage)
        assertTrue(report.daily.isEmpty())
        assertEquals(changed, changed.withProfile(AnalyticsProfile("4.0.0", 15, "en")))
    }

    @Test
    fun `profile-only refresh does not fabricate app open or successful usage`() {
        val profileOnly = AnalyticsOutbox(ANALYTICS_TEST_ID).withProfile(ANALYTICS_TEST_PROFILE)
        val report = assertNotNull(profileOnly.nextReport())
        assertTrue(report.daily.isEmpty())
        assertNull(report.state.lastSuccessfulMode)
        assertNull(report.state.lastSuccessfulBackend)
    }

    @Test
    fun `profile refresh preserves independent last successful fields`() {
        val previous = pendingAnalytics().let {
            it.copy(state = it.state!!.copy(lastSuccessfulMode = "semi_auto", lastSuccessfulBackend = "gemini"))
        }
        val updated = previous.withProfile(AnalyticsProfile("4.1.1", 17, "en"))
        assertEquals("semi_auto", updated.state!!.lastSuccessfulMode)
        assertEquals("gemini", updated.state.lastSuccessfulBackend)
        assertEquals(2, updated.state.revision)
    }

    @Test
    fun `daily facts are merged by membership rather than event count`() {
        val date = "2026-10-11"
        val jp = AnalyticsDailyFacts(date, gameServers = setOf("jp"), translationTargets = setOf("zh-Hant"))
        var outbox = pendingAnalytics()
        repeat(100) { outbox = outbox.withDaily(jp) }
        outbox = outbox.withDaily(AnalyticsDailyFacts(date, gameServers = setOf("en"), voiceServers = setOf("en")))
        val daily = assertNotNull(outbox.nextReport()).daily.single()
        assertEquals(setOf("en", "jp"), daily.gameServers)
        assertEquals(setOf("zh-Hant"), daily.translationTargets)
        assertEquals(setOf("en"), daily.voiceServers)
        assertTrue(daily.appOpened)
        assertNull(outbox.state!!.lastSuccessfulBackend)
    }

    @Test
    fun `ack clears submitted facts only and never newer state or same-day additions`() {
        val original = pendingAnalytics().withDaily(AnalyticsDailyFacts("2026-10-11", gameServers = setOf("jp")))
        val submitted = assertNotNull(original.nextReport())
        val concurrent = original.withProfile(AnalyticsProfile("4.1.0", 16, "en"))
            .withDaily(AnalyticsDailyFacts("2026-10-11", gameServers = setOf("tw"), voiceServers = setOf("tw")))
            .withDaily(AnalyticsDailyFacts("2026-10-12", gameServers = setOf("cn")))
        val acknowledged = concurrent.acknowledge(submitted)
        val remainder = assertNotNull(acknowledged.nextReport())
        assertEquals(1, acknowledged.acknowledgedRevision)
        assertEquals(2, remainder.state.revision)
        assertEquals(listOf("2026-10-11", "2026-10-12"), remainder.daily.map { it.date })
        assertEquals(setOf("tw"), remainder.daily.first().gameServers)
        assertEquals(setOf("tw"), remainder.daily.first().voiceServers)
        assertFalse(remainder.daily.first().appOpened)
        assertNull(acknowledged.acknowledge(remainder).nextReport())
    }

    @Test
    fun `replaying an older ack cannot regress acknowledgement or resurrect data`() {
        val original = pendingAnalytics()
        val first = assertNotNull(original.nextReport())
        val updated = original.withProfile(AnalyticsProfile("4.1.0", 16, "en"))
        val second = assertNotNull(updated.nextReport())
        val acknowledged = updated.acknowledge(second).acknowledge(first)
        assertEquals(2, acknowledged.acknowledgedRevision)
        assertNull(acknowledged.nextReport())
    }

    @Test
    fun `storage roundtrip preserves identity revisions acknowledged facts and pending facts`() {
        val original = pendingAnalytics()
        val pending = original.acknowledge(assertNotNull(original.nextReport()))
            .withDaily(AnalyticsDailyFacts("2026-10-11", voiceServers = setOf("jp")))
        val restored = AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(
            AnalyticsProtocol.storageJson.encodeToString(pending)
        )
        assertEquals(pending, restored)
        assertEquals(ANALYTICS_TEST_ID, restored.installId)
        assertEquals(setOf("jp"), restored.nextReport()!!.daily.single().voiceServers)
        assertFalse(restored.nextReport()!!.daily.single().appOpened)
    }

    @Test
    fun `damaged storage fails closed instead of silently resetting identity or revision`() {
        val json = AnalyticsProtocol.storageJson.encodeToString(pendingAnalytics())
        assertFails { AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>("{broken") }
        assertFails { AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(json.replace("\"revision\":1", "\"revision\":-1")) }
        assertFails { AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(json.replace(ANALYTICS_TEST_ID, "invalid")) }
        assertFails { AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(json.dropLast(1) + ",\"unknown\":true}") }
    }

    @Test
    fun `backlog splits at 31 dates without expiration or loss`() {
        var outbox = AnalyticsOutbox(ANALYTICS_TEST_ID).withProfile(ANALYTICS_TEST_PROFILE)
        val firstDate = LocalDate.parse("2020-01-01")
        repeat(70) { index ->
            outbox = outbox.withDaily(AnalyticsDailyFacts(firstDate.plusDays(index.toLong()).toString(), appOpened = true))
        }
        val sent = mutableListOf<String>()
        val sizes = mutableListOf<Int>()
        while (true) {
            val report = outbox.nextReport() ?: break
            sizes += report.daily.size
            sent += report.daily.map { it.date }
            assertTrue(AnalyticsProtocol.encode(report).encodeToByteArray().size <= AnalyticsProtocol.MAX_BODY_BYTES)
            outbox = outbox.acknowledge(report)
        }
        assertEquals(listOf(31, 31, 8), sizes)
        assertEquals(70, sent.toSet().size)
        assertEquals(firstDate.toString(), sent.first())
    }

    @Test
    fun `byte bound splits dates and never returns a profile-only loop for an oversized day`() {
        val original = pendingAnalytics().withDaily(AnalyticsDailyFacts("2026-10-12", appOpened = true))
        val one = assertNotNull(original.nextReport(maxDates = 1))
        val oneSize = AnalyticsProtocol.encode(one).encodeToByteArray().size
        assertEquals(one, original.nextReport(maxBytes = oneSize))
        assertFailsWith<IllegalArgumentException> { original.nextReport(maxBytes = oneSize - 1) }
        assertFailsWith<IllegalArgumentException> { original.nextReport(maxDates = 32) }
    }

    @Test
    fun `foreground prunes only fully acknowledged older dates`() {
        val original = pendingAnalytics()
        val acknowledged = original.acknowledge(assertNotNull(original.nextReport()))
            .withDaily(AnalyticsDailyFacts("2020-01-01", appOpened = true))
        val next = acknowledged.onForeground(ANALYTICS_TEST_PROFILE, "2026-10-12")
        assertEquals(setOf("2020-01-01", "2026-10-12"), next.days.keys)
        assertEquals(listOf("2020-01-01", "2026-10-12"), next.nextReport()!!.daily.map { it.date })
    }

    @Test
    fun `quarantine retains facts blocks the same snapshot and permits changed snapshots`() {
        val original = pendingAnalytics()
        val report = assertNotNull(original.nextReport())
        val rejected = original.quarantine(report, 400)
        assertTrue(rejected.isQuarantined(report))
        assertEquals(original.days, rejected.days)
        val restored = AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(
            AnalyticsProtocol.storageJson.encodeToString(rejected)
        )
        assertTrue(restored.isQuarantined(report))
        val changed = restored.withProfile(AnalyticsProfile("4.1.0", 16, "en"))
        assertFalse(changed.isQuarantined(changed.nextReport()!!))
        assertNull(changed.acknowledge(changed.nextReport()!!).rejectedSnapshotHash)
    }

    @Test
    fun `ack requires exact submitted revision and date set including no dates`() {
        val report = pendingAnalytics().nextReport()!!
        val valid = ackBody(report)
        assertTrue(AnalyticsProtocol.validAck(valid, report))
        for (invalid in listOf(
            "", "{broken", "{\"ok\":true}", valid.replace("\"ok\":true", "\"ok\":false"),
            valid.replace("\"schema_version\":2", "\"schema_version\":1"),
            valid.replace("\"state_revision\":1", "\"state_revision\":2"),
            valid.replace("[\"2026-10-11\"]", "[]"),
            valid.replace("2026-10-11", "2026-10-12"),
            valid.replace("[\"2026-10-11\"]", "[\"2026-10-11\",\"2026-10-11\"]"),
            valid + " ".repeat(AnalyticsProtocol.MAX_ACK_BYTES)
        )) assertFalse(AnalyticsProtocol.validAck(invalid, report), invalid.take(120))
        val noDates = report.copy(daily = emptyList())
        assertTrue(AnalyticsProtocol.validAck(ackBody(noDates), noDates))
        assertFalse(AnalyticsProtocol.validAck(valid, noDates))
    }

    @Test
    fun `validations match receiver enums dates identity and safe integer limits`() {
        assertTrue(AnalyticsProtocol.validDate("2024-02-29"))
        assertFalse(AnalyticsProtocol.validDate("2026-02-29"))
        assertFalse(AnalyticsProtocol.validDate("2026-1-01"))
        assertFails { AnalyticsOutbox("not-a-uuid") }
        assertFails { AnalyticsProfile("4.1.0", 16, "system") }
        assertFails { AnalyticsProfile("", 16, "en") }
        assertFails { AnalyticsProfile("4.1.0", AnalyticsProtocol.MAX_REVISION + 1, "en") }
        assertFails { AnalyticsDailyFacts("2026-10-11", gameServers = setOf("azure")) }
        assertFails { AnalyticsDailyFacts("2026-10-11", translationTargets = setOf("ja")) }
        assertFails { pendingAnalytics().state!!.copy(lastSuccessfulBackend = "cache") }
        assertFails { pendingAnalytics().state!!.copy(lastSuccessfulMode = "crop") }
        val exhausted = pendingAnalytics().let { it.copy(state = it.state!!.copy(revision = AnalyticsProtocol.MAX_REVISION)) }
        assertFails { exhausted.withProfile(AnalyticsProfile("4.1.1", 17, "en")) }
    }
}
