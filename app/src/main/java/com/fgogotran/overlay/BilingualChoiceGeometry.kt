package com.fgogotran.overlay

/** Moves the complete bilingual choice block inside its existing drawing plane. */
internal object BilingualChoiceGeometry {
    private const val DOWNWARD_OFFSET = 6f

    fun downwardShift(
        inkBottom: Float,
        planeBottom: Float,
        shadowPadding: Float,
        viewportScale: Float
    ): Float = (planeBottom - shadowPadding - inkBottom)
        .coerceIn(0f, DOWNWARD_OFFSET * viewportScale)
}
