package com.fgogotran.voice

import com.fgogotran.runner.RunnerSessionGate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InGameVoiceRequestGateTest {
    @Test fun `off rejects production requests`() {
        val runner = RunnerSessionGate()
        val voice = InGameVoiceRequestGate(runner::isCurrent)
        assertNull(voice.reserve(1L, "line"))
    }

    @Test fun `request order is reserved before slow profile preparation`() {
        val runner = RunnerSessionGate()
        val token = runner.beginSession()
        val voice = InGameVoiceRequestGate(runner::isCurrent)
        val slow = assertNotNull(voice.reserve(token, "old"))
        val fast = assertNotNull(voice.reserve(token, "new"))
        assertTrue(voice.attachCacheMaterial(fast, "new audio"))
        assertFalse(voice.attachCacheMaterial(slow, "old audio"))
        assertFalse(voice.isCurrent(slow))
        assertTrue(voice.isCurrent(fast))
    }

    @Test fun `stop rejects late synthesis and clears dedupe but cached audio can be reused`() {
        val runner = RunnerSessionGate()
        val token = runner.beginSession()
        val voice = InGameVoiceRequestGate(runner::isCurrent)
        val old = assertNotNull(voice.reserve(token, "first line"))
        assertTrue(voice.attachCacheMaterial(old, "cached audio"))
        assertNull(voice.reserve(token, "first line"))
        runner.endSession(token)
        voice.invalidate(token)
        assertFalse(voice.isCurrent(old))
        assertNull(voice.reserve(token, "first line"))
        val next = runner.beginSession()
        val fresh = assertNotNull(voice.reserve(next, "first line"))
        assertTrue(voice.attachCacheMaterial(fresh, "cached audio"))
        voice.invalidate(token) // Late old teardown must not stop new audio.
        voice.clearFailed(old)
        assertTrue(voice.isCurrent(fresh))
    }

    @Test fun `failed or cancelled latest request is retryable but old failure cannot erase new dedupe`() {
        val runner = RunnerSessionGate()
        val token = runner.beginSession()
        val voice = InGameVoiceRequestGate(runner::isCurrent)
        val first = assertNotNull(voice.reserve(token, "line"))
        voice.clearFailed(first)
        val retry = assertNotNull(voice.reserve(token, "line"))
        voice.clearFailed(first)
        assertTrue(voice.isCurrent(retry))
        assertNull(voice.reserve(token, "line"))
    }

    @Test fun `cache material duplicates stay suppressed within one session`() {
        val runner = RunnerSessionGate()
        val token = runner.beginSession()
        val voice = InGameVoiceRequestGate(runner::isCurrent)
        val first = assertNotNull(voice.reserve(token, "original"))
        assertTrue(voice.attachCacheMaterial(first, "same audio"))
        val duplicate = assertNotNull(voice.reserve(token, "normalized variant"))
        assertFalse(voice.attachCacheMaterial(duplicate, "same audio"))
        val different = assertNotNull(voice.reserve(token, "different"))
        assertTrue(voice.attachCacheMaterial(different, "different audio"))
    }
}
