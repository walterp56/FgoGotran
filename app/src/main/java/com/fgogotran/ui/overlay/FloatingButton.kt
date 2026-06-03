package com.fgogotran.ui.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Draggable semi-transparent floating translate button.
 *
 * Tap requests one manual translation, long-press opens the menu, and movement
 * past touch slop drags the button. Keeping these gestures in one detector
 * avoids tap, long-press, and drag competing with each other.
 */
@Composable
fun FloatingButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val idleAlpha = 0.38f
    val pressedAlpha = 0.62f
    var pressed by remember { mutableStateOf(false) }
    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        label = "floatingButtonScale"
    )
    val buttonAlpha by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else idleAlpha,
        label = "floatingButtonAlpha"
    )
    val hapticFeedback = LocalHapticFeedback.current

    Surface(
        color = Color(0xFF1E1E1E).copy(alpha = buttonAlpha),
        contentColor = Color.White.copy(alpha = if (pressed) 0.9f else 0.68f),
        shape = CircleShape,
        shadowElevation = if (pressed) 8.dp else 2.dp,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
            }
            .pointerInput(onClick, onLongClick, onDrag) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                        pressed = true
                        var totalDrag = Offset.Zero
                        var firstDragDelta = Offset.Zero
                        var tapReleased = false
                        var dragStarted = false
                        var cancelled = false

                        withTimeoutOrNull(longPressTimeout) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }

                                if (change == null || change.isConsumed) {
                                    cancelled = true
                                    return@withTimeoutOrNull
                                }

                                if (change.changedToUpIgnoreConsumed()) {
                                    tapReleased = true
                                    return@withTimeoutOrNull
                                }

                                val delta = change.positionChange()
                                if (delta != Offset.Zero) {
                                    totalDrag += delta
                                    if (totalDrag.getDistance() > touchSlop) {
                                        dragStarted = true
                                        firstDragDelta = totalDrag
                                        change.consume()
                                        return@withTimeoutOrNull
                                    }
                                }
                            }
                        }

                        when {
                            dragStarted -> {
                                onDrag(firstDragDelta.x, firstDragDelta.y)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: break

                                    if (change.changedToUpIgnoreConsumed()) break

                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        change.consume()
                                        onDrag(delta.x, delta.y)
                                    }
                                }
                                pressed = false
                            }

                            tapReleased -> {
                                pressed = false
                                onClick()
                            }

                            cancelled -> {
                                pressed = false
                            }

                            else -> {
                                pressed = false
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongClick()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: break
                                    change.consume()
                                    if (change.changedToUpIgnoreConsumed()) break
                                }
                            }
                        }
                    }
                } finally {
                    pressed = false
                }
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {}
    }
}
