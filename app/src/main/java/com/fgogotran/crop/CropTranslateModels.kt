package com.fgogotran.crop

import android.graphics.Rect
import kotlinx.coroutines.Job

/** A cancelled job still owns OCR resources until its cleanup has completed. */
internal fun cropBlocksBackgroundOcr(
    selectorVisible: Boolean,
    cropJob: Job?,
    resultVisible: Boolean
): Boolean = selectorVisible || cropJob?.isCompleted == false || resultVisible

enum class CropModeState {
    IDLE,
    SELECTING
}

data class CropTextLine(
    val text: String,
    val boundingBox: Rect
)
