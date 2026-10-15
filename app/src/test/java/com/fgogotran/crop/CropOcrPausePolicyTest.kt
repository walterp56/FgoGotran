package com.fgogotran.crop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executes the production pause rule with real jobs; native handoff needs device coverage. */
class CropOcrPausePolicyTest {
    @Test fun `idle and completed work do not leave a permanent pause`() {
        assertFalse(cropBlocksBackgroundOcr(false, null, false))
        assertFalse(cropBlocksBackgroundOcr(false, Job().apply { complete() }, false))
        assertFalse(cropBlocksBackgroundOcr(false, Job().apply { cancel() }, false))
    }

    @Test fun `selection processing result and status each keep scanning paused`() {
        assertTrue(cropBlocksBackgroundOcr(true, null, false))
        val loading = Job()
        try {
            assertTrue(cropBlocksBackgroundOcr(false, loading, false))
            assertTrue(cropBlocksBackgroundOcr(true, loading, false))
        } finally {
            loading.complete()
        }
        // Translation and status panels share the same overlay-visibility signal.
        assertTrue(cropBlocksBackgroundOcr(false, loading, true))
        assertTrue(cropBlocksBackgroundOcr(false, null, true))
        assertFalse(cropBlocksBackgroundOcr(false, loading, false))
    }

    @Test fun `selection hands ownership to job then result without a scan gap`() {
        val crop = Job()
        assertTrue(cropBlocksBackgroundOcr(true, null, false))
        assertTrue(cropBlocksBackgroundOcr(true, crop, false))
        assertTrue(cropBlocksBackgroundOcr(false, crop, false))
        assertTrue(cropBlocksBackgroundOcr(false, crop, true))
        crop.complete()
        assertTrue(cropBlocksBackgroundOcr(false, crop, true))
        assertFalse(cropBlocksBackgroundOcr(false, crop, false))
    }

    @Test fun `cancelled native owner stays busy until its cleanup completes`() = runBlocking {
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val crop = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    finishCleanup.await()
                }
            }
        }
        try {
            withTimeout(5_000) {
                crop.cancel()
                cleanupEntered.await()
                assertFalse(crop.isActive)
                assertFalse(crop.isCompleted)
                assertTrue(cropBlocksBackgroundOcr(false, crop, false))
                finishCleanup.complete(Unit)
                crop.join()
                assertFalse(cropBlocksBackgroundOcr(false, crop, false))
            }
        } finally {
            finishCleanup.complete(Unit)
            crop.cancel()
        }
    }

    @Test fun `replacement crop remains authoritative when old job completes`() {
        val oldCrop = Job()
        oldCrop.cancel()
        val newCrop = Job()
        try {
            assertTrue(cropBlocksBackgroundOcr(true, oldCrop, false))
            assertTrue(cropBlocksBackgroundOcr(true, newCrop, false))
            assertTrue(cropBlocksBackgroundOcr(false, newCrop, false))
        } finally {
            newCrop.complete()
        }
        assertFalse(cropBlocksBackgroundOcr(false, newCrop, false))
    }
}
