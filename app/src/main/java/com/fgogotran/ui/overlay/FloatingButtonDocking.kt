package com.fgogotran.ui.overlay

import kotlin.math.roundToInt

enum class FloatingDockSide { LEFT, RIGHT }

internal data class FloatingControlBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** Display-only docking rules shared by native and translated-overlay gestures. */
internal object FloatingButtonDocking {
    const val EDGE_ALLOWANCE_DP = 12f
    const val HANDLE_WIDTH_DP = 16f
    const val HANDLE_HEIGHT_DP = 48f
    const val HANDLE_LINE_WIDTH_DP = 3f
    const val HANDLE_LINE_HEIGHT_DP = 28f

    fun canStartDrag(side: FloatingDockSide?, dx: Float, dy: Float, slop: Float): Boolean =
        when (side) {
            FloatingDockSide.LEFT -> dx > slop
            FloatingDockSide.RIGHT -> -dx > slop
            null -> dx * dx + dy * dy > slop * slop
        }

    fun sideAfterRelease(
        mode: FloatingButtonMode,
        cancelled: Boolean,
        startedDocked: Boolean,
        buttonX: Int,
        buttonSize: Int,
        viewport: FloatingControlBounds,
        allowance: Int
    ): FloatingDockSide? {
        // A reveal gesture must not immediately hide its own restored button.
        if (mode != FloatingButtonMode.AUTO || cancelled || startedDocked) return null
        return when {
            buttonX - viewport.left <= allowance -> FloatingDockSide.LEFT
            viewport.right - buttonX - buttonSize <= allowance -> FloatingDockSide.RIGHT
            else -> null
        }
    }

    fun buttonBounds(x: Int, y: Int, size: Int, viewport: FloatingControlBounds): FloatingControlBounds {
        val left = x.coerceIn(viewport.left, (viewport.right - size).coerceAtLeast(viewport.left))
        val top = y.coerceIn(viewport.top, (viewport.bottom - size).coerceAtLeast(viewport.top))
        return FloatingControlBounds(left, top, left + size, top + size)
    }

    fun revealedButtonBounds(
        pointerX: Float,
        pointerY: Float,
        size: Int,
        viewport: FloatingControlBounds
    ): FloatingControlBounds = buttonBounds(
        (pointerX - size / 2f).roundToInt(),
        (pointerY - size / 2f).roundToInt(),
        size,
        viewport
    )

    fun handleBounds(
        side: FloatingDockSide,
        buttonY: Int,
        buttonSize: Int,
        width: Int,
        height: Int,
        viewport: FloatingControlBounds
    ): FloatingControlBounds {
        val safeWidth = width.coerceIn(1, viewport.width)
        val safeHeight = height.coerceIn(1, viewport.height)
        val left = when (side) {
            FloatingDockSide.LEFT -> viewport.left
            FloatingDockSide.RIGHT -> viewport.right - safeWidth
        }
        val top = (buttonY + (buttonSize - safeHeight) / 2)
            .coerceIn(viewport.top, viewport.bottom - safeHeight)
        return FloatingControlBounds(left, top, left + safeWidth, top + safeHeight)
    }
}
