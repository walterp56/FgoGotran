package com.fgogotran.ui.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull

enum class FloatingButtonMode {
    MANUAL,
    AUTO,
    CROP
}

enum class FloatingActionIcon {
    GO,
    AUTO,
    CROP,
    HISTORY_LIST,
    CLOSE_CIRCLE
}

/**
 * Draggable semi-transparent floating translate button.
 *
 * Tap requests one manual translation, long-press opens the menu, and movement
 * past touch slop drags the button. Keeping these gestures in one detector
 * avoids tap, long-press, and drag competing with each other.
 */
@Composable
fun FloatingButton(
    mode: FloatingButtonMode,
    showFailureRing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val idleAlpha = 0.38f
    val pressedAlpha = 0.62f
    val baseColor = when (mode) {
        FloatingButtonMode.MANUAL -> Color(0xFF1E1E1E)
        FloatingButtonMode.AUTO -> Color(0xFF1E1E1E)
        FloatingButtonMode.CROP -> Color(0xFF075F66)
    }
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
        color = baseColor.copy(alpha = buttonAlpha),
        contentColor = Color.White.copy(alpha = if (pressed) 0.9f else 0.68f),
        border = if (showFailureRing) BorderStroke(3.dp, Color(0xFFFF4A4A)) else null,
        shape = CircleShape,
        shadowElevation = if (pressed) 8.dp else 2.dp,
        modifier = Modifier
            .size(56.dp)
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
        ) {
            FloatingActionGlyph(
                icon = when (mode) {
                    FloatingButtonMode.MANUAL -> FloatingActionIcon.GO
                    FloatingButtonMode.AUTO -> FloatingActionIcon.AUTO
                    FloatingButtonMode.CROP -> FloatingActionIcon.CROP
                },
                prominent = true,
                color = Color.White.copy(alpha = if (pressed) 0.95f else 0.82f),
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
fun FloatingActionGlyph(
    icon: FloatingActionIcon,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    prominent: Boolean = false
) {
    when (icon) {
        FloatingActionIcon.GO,
        FloatingActionIcon.AUTO -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (icon == FloatingActionIcon.GO) "GO" else "AUTO",
                color = color,
                fontSize = when {
                    icon == FloatingActionIcon.GO && prominent -> 15.sp
                    icon == FloatingActionIcon.AUTO && prominent -> 10.sp
                    icon == FloatingActionIcon.AUTO -> 8.sp
                    else -> 13.sp
                },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        FloatingActionIcon.CROP -> CropCornerIcon(modifier = modifier, color = color)
        FloatingActionIcon.HISTORY_LIST -> ListIcon(modifier = modifier, color = color)
        FloatingActionIcon.CLOSE_CIRCLE -> CloseCircleIcon(modifier = modifier, color = color)
    }
}

@Composable
private fun CropCornerIcon(
    modifier: Modifier,
    color: Color,
    strokeWidth: Dp = 2.4.dp
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val right = size.width - inset
        val bottom = size.height - inset
        val length = size.minDimension * 0.34f

        drawLine(color, Offset(inset, inset), Offset(inset + length, inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, inset), Offset(inset, inset + length), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, inset), Offset(right - length, inset), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, inset), Offset(right, inset + length), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, bottom), Offset(inset + length, bottom), stroke, StrokeCap.Round)
        drawLine(color, Offset(inset, bottom), Offset(inset, bottom - length), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right - length, bottom), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - length), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ListIcon(
    modifier: Modifier,
    color: Color,
    strokeWidth: Dp = 2.2.dp
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val left = size.width * 0.18f
        val right = size.width * 0.82f
        listOf(0.28f, 0.5f, 0.72f).forEach { yFraction ->
            val y = size.height * yFraction
            drawLine(color, Offset(left, y), Offset(right, y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun CloseCircleIcon(
    modifier: Modifier,
    color: Color,
    strokeWidth: Dp = 2.2.dp
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val radius = size.minDimension / 2f - stroke / 2f
        drawCircle(color = color, radius = radius, style = Stroke(width = stroke))

        val inset = size.minDimension * 0.34f
        drawLine(
            color,
            Offset(inset, inset),
            Offset(size.width - inset, size.height - inset),
            stroke,
            StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width - inset, inset),
            Offset(inset, size.height - inset),
            stroke,
            StrokeCap.Round
        )
    }
}
