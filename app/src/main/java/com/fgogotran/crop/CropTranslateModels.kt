package com.fgogotran.crop

import android.graphics.Rect

enum class CropModeState {
    IDLE,
    SELECTING
}

data class CropTextLine(
    val text: String,
    val boundingBox: Rect
)
