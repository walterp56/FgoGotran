package com.fgogotran.ocr

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Cancellation is not a model failure and must not disable recognition batching. */
internal inline fun <T> paddleBatchOrFallback(
    requestContext: CoroutineContext,
    batch: () -> T,
    fallback: (Exception) -> T
): T {
    requestContext.ensureActive()
    return try {
        batch().also { requestContext.ensureActive() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        requestContext.ensureActive()
        fallback(error).also { requestContext.ensureActive() }
    }
}
