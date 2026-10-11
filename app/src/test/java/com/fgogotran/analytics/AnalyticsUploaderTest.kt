package com.fgogotran.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryAnalyticsStore(initial: AnalyticsOutbox = pendingAnalytics()) : AnalyticsOutboxStore {
    private val mutex = Mutex()
    private var value = initial
    override suspend fun readAnalyticsOutbox() = mutex.withLock { value }
    override suspend fun updateAnalyticsOutbox(change: (AnalyticsOutbox) -> AnalyticsOutbox) = mutex.withLock {
        change(value).also { value = it }
    }
}

class AnalyticsUploaderTest {
    @Test
    fun `valid durable ack drains pending state and dates`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        var sends = 0
        val session = AnalyticsUploadSession(store, send = {
            sends++
            AnalyticsHttpResponse(200, ackBody(it))
        })
        session.flushPending()
        session.flushPending()
        assertEquals(1, sends)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `network 429 and 5xx retry with bounded backoff without losing occurrence date`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        val pauses = mutableListOf<Long>()
        val reports = mutableListOf<AnalyticsReport>()
        val session = AnalyticsUploadSession(store, send = { report ->
            reports += report
            when (reports.size) {
                1 -> throw IOException("must not be logged")
                2 -> AnalyticsHttpResponse(429)
                3 -> AnalyticsHttpResponse(503)
                else -> AnalyticsHttpResponse(200, ackBody(report))
            }
        }, pause = { pauses += it }, jitter = { it })
        session.flushPending()
        assertEquals(listOf(2_000L, 4_000L, 8_000L), pauses)
        assertEquals(4, reports.size)
        assertEquals(1, reports.toSet().size)
        assertEquals("2026-10-11", reports.last().daily.single().date)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `four failures stop this session but a later activity can retry persisted data`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        var succeeds = false
        var sends = 0
        val session = AnalyticsUploadSession(store, send = {
            sends++
            if (succeeds) AnalyticsHttpResponse(200, ackBody(it)) else AnalyticsHttpResponse(408)
        }, pause = {}, jitter = { it })
        session.flushPending()
        assertEquals(4, sends)
        assertNotNull(store.readAnalyticsOutbox().nextReport())
        succeeds = true
        session.flushPending()
        assertEquals(5, sends)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `legacy or malformed 2xx response is not a successful ack`() = runBlocking<Unit> {
        for (body in listOf("{\"ok\":true}", "{broken", "")) {
            val store = MemoryAnalyticsStore()
            var sends = 0
            AnalyticsUploadSession(store, send = {
                sends++
                AnalyticsHttpResponse(200, body)
            }, pause = {}, jitter = { it }).flushPending()
            assertEquals(4, sends)
            assertNotNull(store.readAnalyticsOutbox().nextReport())
            assertNull(store.readAnalyticsOutbox().rejectedSnapshotHash)
        }
    }

    @Test
    fun `400 quarantines unchanged snapshot across restart without acknowledging it`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        var sends = 0
        fun uploader() = AnalyticsUploadSession(store, send = {
            sends++
            AnalyticsHttpResponse(400)
        }, pause = { error("permanent rejection must not back off") })
        uploader().flushPending()
        uploader().flushPending()
        assertEquals(1, sends)
        assertNotNull(store.readAnalyticsOutbox().nextReport())
        assertEquals(-1, store.readAnalyticsOutbox().acknowledgedRevision)
        store.updateAnalyticsOutbox { it.withProfile(AnalyticsProfile("4.1.0", 16, "en")) }
        uploader().flushPending()
        assertEquals(2, sends)
    }

    @Test
    fun `413 splits the batch and all dates eventually acknowledge`() = runBlocking<Unit> {
        var initial = AnalyticsOutbox(ANALYTICS_TEST_ID).withProfile(ANALYTICS_TEST_PROFILE)
        repeat(35) { index ->
            initial = initial.withDaily(AnalyticsDailyFacts(LocalDate.parse("2026-01-01").plusDays(index.toLong()).toString(), appOpened = true))
        }
        val store = MemoryAnalyticsStore(initial)
        val sizes = mutableListOf<Int>()
        val acceptedDates = mutableSetOf<String>()
        AnalyticsUploadSession(store, send = { report ->
            sizes += report.daily.size
            if (report.daily.size > 3) AnalyticsHttpResponse(413) else {
                acceptedDates += report.daily.map { it.date }
                AnalyticsHttpResponse(200, ackBody(report))
            }
        }, pause = { error("splitting must not sleep") }).flushPending()
        assertEquals(listOf(31, 15, 7, 3), sizes.take(4))
        assertEquals(35, acceptedDates.size)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `unsplittable 413 keeps and quarantines single date or profile-only data`() = runBlocking<Unit> {
        for (initial in listOf(pendingAnalytics(), AnalyticsOutbox(ANALYTICS_TEST_ID).withProfile(ANALYTICS_TEST_PROFILE))) {
            val store = MemoryAnalyticsStore(initial)
            var sends = 0
            val session = AnalyticsUploadSession(store, send = { sends++; AnalyticsHttpResponse(413) })
            session.flushPending()
            session.flushPending()
            assertEquals(1, sends)
            assertEquals(413, store.readAnalyticsOutbox().rejectedHttpStatus)
            assertNotNull(store.readAnalyticsOutbox().nextReport())
        }
    }

    @Test
    fun `new facts and profile written during HTTP survive that request's ack`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        val sent = mutableListOf<AnalyticsReport>()
        AnalyticsUploadSession(store, send = { report ->
            sent += report
            if (sent.size == 1) store.updateAnalyticsOutbox {
                it.withProfile(AnalyticsProfile("4.1.0", 16, "en"))
                    .withDaily(AnalyticsDailyFacts("2026-10-11", gameServers = setOf("jp")))
            }
            AnalyticsHttpResponse(200, ackBody(report))
        }).flushPending()
        assertEquals(2, sent.size)
        assertEquals(1, sent.first().state.revision)
        assertEquals(2, sent.last().state.revision)
        assertEquals(setOf("jp"), sent.last().daily.single().gameServers)
        assertTrue(!sent.last().daily.single().appOpened)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `lost response after remote commit safely replays the same memberships`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        val remoteDates = mutableSetOf<String>()
        var sends = 0
        AnalyticsUploadSession(store, send = { report ->
            sends++
            remoteDates += report.daily.map { it.date }
            if (sends == 1) throw IOException("lost response")
            AnalyticsHttpResponse(200, ackBody(report))
        }, pause = {}, jitter = { it }).flushPending()
        assertEquals(2, sends)
        assertEquals(setOf("2026-10-11"), remoteDates)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `failed local ack persistence retries without marking pending data handled`() = runBlocking<Unit> {
        val backing = MemoryAnalyticsStore()
        var failOnce = true
        val store = object : AnalyticsOutboxStore by backing {
            override suspend fun updateAnalyticsOutbox(change: (AnalyticsOutbox) -> AnalyticsOutbox): AnalyticsOutbox {
                if (failOnce) { failOnce = false; throw IOException("disk unavailable") }
                return backing.updateAnalyticsOutbox(change)
            }
        }
        var sends = 0
        AnalyticsUploadSession(store, send = {
            sends++
            AnalyticsHttpResponse(200, ackBody(it))
        }, pause = {}, jitter = { it }).flushPending()
        assertEquals(2, sends)
        assertNull(store.readAnalyticsOutbox().nextReport())
    }

    @Test
    fun `cancellation propagates without ack or quarantine`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        val session = AnalyticsUploadSession(store, send = { throw CancellationException("stopped") })
        assertFailsWith<CancellationException> { session.flushPending() }
        assertNotNull(store.readAnalyticsOutbox().nextReport())
        assertNull(store.readAnalyticsOutbox().rejectedSnapshotHash)
    }

    @Test
    fun `concurrent flushes share one in-flight upload without locking out persistence`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        val session = AnalyticsUploadSession(store, send = {
            sends++
            entered.complete(Unit)
            release.await()
            AnalyticsHttpResponse(200, ackBody(it))
        })
        withTimeout(5_000) {
            val first = launch { session.flushPending() }
            entered.await()
            val second = launch { session.flushPending() }
            // This edit completes while HTTP remains suspended: no shared store/HTTP lock.
            store.updateAnalyticsOutbox { it }
            release.complete(Unit)
            first.join()
            second.join()
        }
        assertEquals(1, sends)
    }

    @Test
    fun `event-driven worker coalesces requests without a periodic heartbeat`() = runBlocking<Unit> {
        val store = MemoryAnalyticsStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val idleScope = CoroutineScope(coroutineContext + SupervisorJob())
        var sends = 0
        val uploader = AnalyticsUploader(idleScope, AnalyticsUploadSession(store, send = {
            sends++
            entered.complete(Unit)
            release.await()
            AnalyticsHttpResponse(200, ackBody(it))
        }), debounceMs = 0)
        try {
            repeat(100) { uploader.requestFlush() }
            withTimeout(5_000) {
                entered.await()
                repeat(100) { uploader.requestFlush() }
                release.complete(Unit)
                // A flush shares the session mutex; use the store to verify completion below.
                while (store.readAnalyticsOutbox().nextReport() != null) kotlinx.coroutines.yield()
            }
            assertEquals(1, sends)
        } finally {
            uploader.close()
            idleScope.cancel()
        }
    }

    @Test
    fun `failure logs contain neither request identity nor exception content`() = runBlocking<Unit> {
        val messages = mutableListOf<String>()
        AnalyticsUploadSession(MemoryAnalyticsStore(), send = { throw IOException("secret request $ANALYTICS_TEST_ID") },
            pause = {}, jitter = { it }, log = { messages += it }).flushPending()
        assertEquals(4, messages.size)
        assertTrue(messages.none { it.contains(ANALYTICS_TEST_ID) || it.contains("secret") })
    }
}
