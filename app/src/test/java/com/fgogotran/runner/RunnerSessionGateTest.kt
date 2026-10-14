package com.fgogotran.runner

import com.fgogotran.translation.TranslationMode
import com.fgogotran.translation.TranslationTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunnerSessionGateTest {
    @Test fun `off has no authority and duplicate starts share one token`() {
        val gate = RunnerSessionGate()
        assertNull(gate.captureActiveToken())
        assertFalse(gate.isCurrent(1L))
        val first = gate.beginSession()
        repeat(20) { assertEquals(first, gate.beginSession()) }
        assertTrue(gate.isCurrent(first))
    }

    @Test fun `stop is idempotent and stale teardown cannot close a new owner`() {
        val gate = RunnerSessionGate()
        repeat(100) {
            val old = gate.beginSession()
            assertTrue(gate.endSession(old))
            assertFalse(gate.endSession(old))
            val next = gate.beginSession()
            assertTrue(next > old)
            assertFalse(gate.isCurrent(old))
            assertFalse(gate.endSession(old))
            assertTrue(gate.isCurrent(next))
            gate.endSession(next)
        }
    }

    @Test fun `original async stamp survives every await and never adopts a restarted session`() = runBlocking {
        for (boundary in listOf("screenshot", "OCR", "translation", "voice profile", "TTS", "gesture", "capture")) {
            val gate = RunnerSessionGate()
            val old = gate.beginSession()
            val stamp = RunnerSessionContext(old)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val request = async(stamp) {
                assertSame(stamp, RunnerSessionContext.current(), boundary)
                entered.complete(Unit)
                release.await()
                withContext(Dispatchers.Default) {
                    assertSame(stamp, RunnerSessionContext.current(), boundary)
                    assertFalse(gate.isCurrent(RunnerSessionContext.current()!!.token), boundary)
                }
            }
            entered.await()
            gate.endSession(old)
            val next = gate.beginSession()
            release.complete(Unit)
            request.await()
            assertTrue(gate.isCurrent(next))
            assertNull(RunnerSessionContext.current()) // No thread-local authority leakage.
        }
    }

    @Test fun `rebound accessibility has a distinct stamp even within the same runner token`() = runBlocking {
        val gate = RunnerSessionGate()
        val token = gate.beginSession()
        val oldBinding = RunnerSessionContext(token)
        val newBinding = RunnerSessionContext(token)
        withContext(oldBinding) {
            assertFalse(RunnerSessionContext.current() === newBinding)
            withContext(newBinding) { assertSame(newBinding, RunnerSessionContext.current()) }
            assertSame(oldBinding, RunnerSessionContext.current())
        }
        assertNull(RunnerSessionContext.current())
    }

    @Test fun `pending request and menu settle flag are consumed atomically by their owner only`() {
        TranslationTrigger.setTranslationMode(TranslationMode.SEMI_AUTO)
        try {
            TranslationTrigger.requestTranslation(10L, afterMenuDismiss = true)
            assertNull(TranslationTrigger.consumeRequest(11L))
            assertNull(TranslationTrigger.consumeRequest(10L))
            TranslationTrigger.requestTranslation(11L, afterMenuDismiss = true)
            assertTrue(TranslationTrigger.consumeRequest(11L)!!.afterMenuDismiss)
            assertNull(TranslationTrigger.consumeRequest(11L))
            TranslationTrigger.requestTranslation(11L)
            TranslationTrigger.cancelPendingTranslation()
            assertNull(TranslationTrigger.consumeRequest(11L))
        } finally { TranslationTrigger.setTranslationMode(TranslationMode.MANUAL) }
    }
}
