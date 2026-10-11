package com.fgogotran.analytics

import com.fgogotran.translation.TranslateResult
import com.fgogotran.translation.TranslationApiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsSuccessTest {
    private val date = "2026-10-11"
    private val origin = AnalyticsUsageOrigin("jp", "semi_auto")
    private fun outbox() = AnalyticsOutbox("11111111-1111-4111-8111-111111111111")
        .withProfile(AnalyticsProfile("4.1.0", 16, "en"))
    private fun api(backend: String, target: String = "zh-Hant") = TranslateResult(
        "translation", backend, false, target, apiUsage = TranslationApiUsage.completed(backend)
    )
    private fun success(result: TranslateResult, source: AnalyticsUsageOrigin = origin, day: String = date) =
        checkNotNull(AnalyticsSuccess.translation(day, source, listOf(result)))

    @Test fun `fresh API display records actual backend mode server and output target`() {
        val recorded = outbox().withSuccess(success(api("qwen")))
        assertEquals("qwen", recorded.state?.lastSuccessfulBackend)
        assertEquals("semi_auto", recorded.state?.lastSuccessfulMode)
        assertEquals(setOf("jp"), recorded.days[date]?.known?.gameServers)
        assertEquals(setOf("zh-Hant"), recorded.days[date]?.known?.translationTargets)
        assertTrue(recorded.days[date]?.known?.voiceServers.orEmpty().isEmpty())
        assertFalse(recorded.days[date]!!.known.appOpened)
    }

    @Test fun `cache counts delivered use without inventing selected backend`() {
        val recorded = outbox().withSuccess(success(TranslateResult("cached", "cache", true, "en")))
        assertNull(recorded.state?.lastSuccessfulBackend)
        assertEquals("semi_auto", recorded.state?.lastSuccessfulMode)
        assertEquals(setOf("en"), recorded.days[date]?.known?.translationTargets)
    }

    @Test fun `local output preserves previous actual backend`() {
        val recorded = outbox().withSuccess(success(api("deepseek")))
            .withSuccess(success(TranslateResult("local name", "character-db", true), AnalyticsUsageOrigin("jp", "auto")))
        assertEquals("deepseek", recorded.state?.lastSuccessfulBackend)
        assertEquals("auto", recorded.state?.lastSuccessfulMode)
    }

    @Test fun `A to B to A same day advances current state without repeating daily memberships`() {
        val a = outbox().withSuccess(success(api("deepseek")))
        val b = a.withSuccess(success(api("gpt")))
        val againA = b.withSuccess(success(api("deepseek")))
        assertEquals(a.state!!.revision + 2, againA.state!!.revision)
        assertEquals("deepseek", againA.state!!.lastSuccessfulBackend)
        assertEquals(a.days, againA.days)
    }

    @Test fun `same output memberships and current fields do not increment revision`() {
        val first = outbox().withSuccess(success(api("deepseek")))
        assertEquals(first, first.withSuccess(success(api("deepseek"))))
        val acknowledged = first.acknowledge(first.nextReport()!!)
        assertNull(acknowledged.withSuccess(success(api("deepseek"))).nextReport())
    }

    @Test fun `mode and backend change independently`() {
        val first = outbox().withSuccess(success(api("deepseek")))
        val voice = first.withSuccess(AnalyticsSuccess.voice(date, AnalyticsUsageOrigin("cn", "manual")))
        assertEquals("manual", voice.state!!.lastSuccessfulMode)
        assertEquals("deepseek", voice.state!!.lastSuccessfulBackend)
        val crop = voice.withSuccess(success(api("claude"), AnalyticsUsageOrigin("jp")))
        assertEquals("manual", crop.state!!.lastSuccessfulMode)
        assertEquals("claude", crop.state!!.lastSuccessfulBackend)
    }

    @Test fun `battle and crop do not create normal workflow use`() {
        val recorded = outbox().withSuccess(success(api("gemini"), AnalyticsUsageOrigin("jp")))
        assertNull(recorded.state!!.lastSuccessfulMode)
        assertEquals("gemini", recorded.state!!.lastSuccessfulBackend)
    }

    @Test fun `voice-only use counts source and voice server without target backend or DAU`() {
        for (server in listOf("cn", "tw", "en")) {
            val recorded = outbox().withSuccess(AnalyticsSuccess.voice(date, AnalyticsUsageOrigin(server, "auto")))
            assertEquals("auto", recorded.state!!.lastSuccessfulMode)
            assertNull(recorded.state!!.lastSuccessfulBackend)
            assertEquals(setOf(server), recorded.days[date]!!.known.gameServers)
            assertEquals(setOf(server), recorded.days[date]!!.known.voiceServers)
            assertTrue(recorded.days[date]!!.known.translationTargets.isEmpty())
            assertFalse(recorded.days[date]!!.known.appOpened)
        }
    }

    @Test fun `translated voice adds voice membership but preserves text attribution`() {
        val text = outbox().withSuccess(success(api("zhipu")))
        val voice = text.withSuccess(AnalyticsSuccess.voice(date, origin))
        assertEquals(text.state, voice.state)
        assertEquals(setOf("zh-Hant"), voice.days[date]!!.known.translationTargets)
        assertEquals(setOf("jp"), voice.days[date]!!.known.voiceServers)
    }

    @Test fun `failure blank none and known status output do not count`() {
        for (result in listOf(api("gpt").copy(isFailure = true), api("gpt").copy(translatedText = " "),
            TranslateResult("error", "none", false), api("gpt").copy(countsAsUsage = false))) {
            assertNull(AnalyticsSuccess.translation(date, origin, listOf(result)))
        }
        assertNull(AnalyticsSuccess.translation(date, origin, emptyList()))
    }

    @Test fun `first-pass displayed output need not be trusted for context to count`() {
        assertEquals("gpt", success(api("gpt").copy(trustedForContext = false)).backend)
    }

    @Test fun `mixed local cache and API output keeps only actual newest provider`() {
        val older = api("gpt")
        val newer = api("claude", "en")
        val local = TranslateResult("local", "character-db", true)
        val facts = AnalyticsSuccess.translation(date, origin, listOf(newer, local, older))!!
        assertEquals("claude", facts.backend)
        assertEquals(setOf("zh-Hans", "zh-Hant", "en"), facts.daily.translationTargets)
    }

    @Test fun `failed newer API output cannot replace successful provider`() {
        val older = api("gpt")
        val failed = api("claude").copy(isFailure = true)
        assertEquals("gpt", AnalyticsSuccess.translation(date, origin, listOf(older, failed))!!.backend)
    }

    @Test fun `composite and locale copies preserve actual provider metadata`() {
        val original = api("qwen")
        val combined = TranslateResult("combined", "character-db", false,
            apiUsage = TranslationApiUsage.latest(listOf(original, TranslateResult("local", "glossary", true))))
        assertEquals("qwen", success(combined.copy(targetLocale = "en")).backend)
        assertEquals(original.apiUsage, combined.apiUsage)
    }

    @Test fun `profile changes preserve successful fields and usage does not become foreground`() {
        val recorded = outbox().withSuccess(success(api("gpt")))
            .withProfile(AnalyticsProfile("4.0.0", 15, "zh-Hant"))
        assertEquals("gpt", recorded.state!!.lastSuccessfulBackend)
        assertEquals("semi_auto", recorded.state!!.lastSuccessfulMode)
        assertEquals("zh-Hant", recorded.state!!.uiLanguage)
        assertFalse(recorded.days[date]!!.known.appOpened)
    }

    @Test fun `occurrence dates stay separate and ACK cannot clear newer delivery`() {
        val first = outbox().withSuccess(success(api("gpt")))
        val submitted = first.nextReport()!!
        val concurrent = first.withSuccess(success(api("claude", "en"), day = "2026-10-12"))
            .withSuccess(AnalyticsSuccess.voice(date, origin))
        val pending = concurrent.acknowledge(submitted).nextReport()!!
        assertEquals("claude", pending.state.lastSuccessfulBackend)
        assertEquals(listOf(date, "2026-10-12"), pending.daily.map { it.date })
        assertEquals(setOf("jp"), pending.daily.first().voiceServers)
        assertTrue(pending.daily.first().translationTargets.isEmpty())
    }

    @Test fun `persistent restart retains success and no scene content enters payload`() {
        val recorded = outbox().withSuccess(success(api("deepseek")))
        val restarted = AnalyticsProtocol.storageJson.decodeFromString<AnalyticsOutbox>(
            AnalyticsProtocol.storageJson.encodeToString(AnalyticsOutbox.serializer(), recorded))
        assertEquals(recorded, restarted)
        val body = AnalyticsProtocol.encode(restarted.nextReport()!!)
        assertFalse(body.contains("completionOrder"))
        assertFalse(body.contains("translatedText"))
        assertFalse(body.contains("apiUsage"))
        assertFalse(body.contains("scene"))
    }
}
