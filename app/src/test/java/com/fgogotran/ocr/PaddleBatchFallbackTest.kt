package com.fgogotran.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PaddleBatchFallbackTest {
    @Test fun `already cancelled requests start neither batch nor fallback`() {
        val job = Job().apply { cancel() }
        var calls = 0
        assertFailsWith<CancellationException> {
            paddleBatchOrFallback(job, { calls++; "batch" }, { calls++; "fallback" })
        }
        assertEquals(0, calls)
    }

    @Test fun `explicit cancellation propagates without disabling batching`() {
        val cancellation = CancellationException("Cancelled OCR")
        var batchingEnabled = true
        val thrown = assertFailsWith<CancellationException> {
            paddleBatchOrFallback(Job(), { throw cancellation }, {
                batchingEnabled = false
                "fallback"
            })
        }
        assertSame(cancellation, thrown)
        assertTrue(batchingEnabled)
    }

    @Test fun `cancellation during a successful native batch skips fallback`() {
        val job = Job()
        var fallbackCalled = false
        assertFailsWith<CancellationException> {
            paddleBatchOrFallback(job, { job.cancel(); "obsolete output" }, {
                fallbackCalled = true
                "fallback"
            })
        }
        assertFalse(fallbackCalled)
    }

    @Test fun `cancellation takes priority over concurrent native error`() {
        val job = Job()
        var fallbackCalled = false
        assertFailsWith<CancellationException> {
            paddleBatchOrFallback(job, {
                job.cancel()
                error("Native batch failed")
            }, {
                fallbackCalled = true
                "fallback"
            })
        }
        assertFalse(fallbackCalled)
    }

    @Test fun `genuine active batch failure retains existing fallback`() {
        val error = IllegalStateException("Unsupported batch")
        var fallbackCalls = 0
        assertEquals("single crops", paddleBatchOrFallback(Job(), { throw error }, {
            assertSame(error, it)
            fallbackCalls++
            "single crops"
        }))
        assertEquals(1, fallbackCalls)
    }

    @Test fun `fallback cancellation is not swallowed or retried`() {
        val job = Job()
        var fallbackCalls = 0
        assertFailsWith<CancellationException> {
            paddleBatchOrFallback(job, { error("Unsupported batch") }, {
                fallbackCalls++
                job.cancel()
                "obsolete fallback"
            })
        }
        assertEquals(1, fallbackCalls)
    }

    @Test fun `one cancelled request does not poison the next context`() {
        assertFailsWith<CancellationException> {
            paddleBatchOrFallback(Job().apply { cancel() }, { "old" }, { "fallback" })
        }
        var fallbackCalled = false
        assertEquals("new", paddleBatchOrFallback(Job(), { "new" }, {
            fallbackCalled = true
            "fallback"
        }))
        assertFalse(fallbackCalled)
    }
}
