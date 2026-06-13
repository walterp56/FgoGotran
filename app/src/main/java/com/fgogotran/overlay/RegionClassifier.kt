package com.fgogotran.overlay

import android.graphics.Rect
import com.fgogotran.ocr.OcrTextLine

/**
 * The three screen regions relevant to FGO dialogue overlays.
 */
enum class TextRegion {
    /** Bottom dialogue box where narrative/conversation text appears. */
    DIALOGUE_BOX,
    /** Character name label, positioned top-left just above the dialogue box. */
    NAME_LABEL,
    /** Player choice buttons, positioned in the middle area of the screen. */
    CHOICE_BUTTON
}

/**
 * A classified group of OCR text lines belonging to one screen region.
 * @property boundingBox the enclosing rectangle for all lines in this region.
 */
data class ClassifiedRegion(
    val region: TextRegion,
    val lines: List<OcrTextLine>,
    val boundingBox: Rect
)
