package com.fgogotran.overlay

/**
 * Horizontal reference-space geometry shared by dialogue OCR and rendering.
 *
 * The OCR right edge is the hard render boundary. The translated text keeps the same 29 px
 * margin on both sides of the OCR region while the established render/text left positions stay
 * unchanged.
 */
internal object DialogueReferenceGeometry {
    const val OCR_LEFT = 106f
    const val OCR_RIGHT = 1805f

    const val RENDER_LEFT = 35f
    const val RENDER_RIGHT = OCR_RIGHT

    const val TEXT_LEFT_INSET = 100f
    const val TEXT_LEFT = RENDER_LEFT + TEXT_LEFT_INSET
    const val TEXT_MARGIN = TEXT_LEFT - OCR_LEFT
    const val TEXT_RIGHT_INSET = TEXT_MARGIN
    const val TEXT_RIGHT = RENDER_RIGHT - TEXT_RIGHT_INSET
    const val TEXT_WIDTH = TEXT_RIGHT - TEXT_LEFT
}
