package com.fgogotran.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class AnalyticsHttpResponse(val status: Int, val body: String = "")

/** Bounded upload session. HTTP and backoff never hold the persistent-store edit lock. */
class AnalyticsUploadSession(
    private val store: AnalyticsOutboxStore,
    private val send: suspend (AnalyticsReport) -> AnalyticsHttpResponse,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: (Long) -> Long = { base -> Random.nextLong(base, base + base / 4 + 1) },
    private val log: (String) -> Unit = {}
) {
    private val uploadMutex = Mutex()

    suspend fun flushPending() = uploadMutex.withLock {
        var failures = 0
        var maxDates = AnalyticsProtocol.MAX_DATES
        while (true) {
            try {
                val outbox = store.readAnalyticsOutbox()
                val report = outbox.nextReport(maxDates) ?: return@withLock
                if (outbox.isQuarantined(report)) return@withLock
                val response = send(report)
                when {
                    response.status in 200..299 && AnalyticsProtocol.validAck(response.body, report) -> {
                        store.updateAnalyticsOutbox { it.acknowledge(report) }
                        failures = 0
                        continue
                    }
                    response.status == 413 && report.daily.size > 1 -> {
                        maxDates = maxOf(1, report.daily.size / 2)
                        continue
                    }
                    response.status in 400..499 && response.status !in setOf(408, 429) -> {
                        // Retain all facts. Stop this exact rejected snapshot until it changes.
                        store.updateAnalyticsOutbox { it.quarantine(report, response.status) }
                        log("Analytics v2 quarantined HTTP ${response.status}; facts retained")
                        return@withLock
                    }
                    else -> log("Analytics v2 retry pending HTTP ${response.status}; facts retained")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No UUID, request body, response body, URL or exception message in logs.
                log("Analytics v2 upload/storage unavailable; facts retained")
            }
            failures++
            if (failures >= MAX_ATTEMPTS) return@withLock
            pause(jitter(2_000L shl (failures - 1)))
        }
    }

    private companion object { const val MAX_ATTEMPTS = 4 }
}

/** One conflated, event-driven uploader; no periodic app heartbeat or always-running service. */
class AnalyticsUploader(
    scope: CoroutineScope,
    private val session: AnalyticsUploadSession,
    private val debounceMs: Long = 1_500L
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (ignored in requests) {
            delay(debounceMs)
            while (requests.tryReceive().isSuccess) { /* coalesce nearby changes */ }
            session.flushPending()
        }
    }

    fun requestFlush() { requests.trySend(Unit) }

    internal fun close() {
        requests.close()
        worker.cancel()
    }
}
