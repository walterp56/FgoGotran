package com.fgogotran.story

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChoiceRetrySourceTest {
    private class Frame(val width: Int, val height: Int, val text: String) {
        var releases = 0
    }

    private val original = Frame(1920, 1080, "old")
    private val retry = Frame(2340, 1080, "new")
    private fun scope() = ChoiceRetrySource(original) { it.releases++ }

    @Test
    fun `without retry the original remains caller owned`() {
        val frames = scope()
        assertSame(original, frames.source)
        frames.close()
        frames.close()
        assertEquals(0, original.releases)
    }

    @Test
    fun `successful retry stays alive through downstream work and supplies its own dimensions`() {
        val frames = scope()
        try {
            frames.useRetry(retry)
            for (stage in listOf("format", "colour", "freshness", "mask", "render")) {
                assertSame(retry, frames.source, stage)
                assertEquals("new", frames.source.text, stage)
                assertEquals(2340, frames.source.width, stage)
                assertEquals(1080, frames.source.height, stage)
                assertEquals(0, retry.releases, stage)
            }
        } finally {
            frames.close()
        }
        frames.close()
        assertEquals(1, retry.releases)
        assertEquals(0, original.releases)
    }

    @Test
    fun `retry failure and cancellation release only the owned frame exactly once`() {
        for (error in listOf(IllegalStateException("OCR failed"), CancellationException("stopped"))) {
            val retryFrame = Frame(2340, 1080, "new")
            val frames = scope()
            val thrown = assertFailsWith<Exception> {
                try {
                    frames.useRetry(retryFrame)
                    throw error
                } finally {
                    frames.close()
                }
            }
            assertSame(error, thrown)
            frames.close()
            assertEquals(1, retryFrame.releases)
            assertEquals(0, original.releases)
        }
    }

    @Test
    fun `incomplete retry is discarded before downstream submission and released on early return`() {
        var submitted = false
        val frames = scope()
        fun process(): Boolean {
            try {
                frames.useRetry(retry)
                if (!ChoiceRecognitionPolicy.isComplete(listOf(1, 2), listOf(1 to "一"))) return false
                submitted = true
                return true
            } finally {
                frames.close()
            }
        }
        assertFalse(process())
        assertFalse(submitted)
        assertTrue(retry.releases == 1)
        assertEquals(0, original.releases)
    }
}
