package com.fgogotran.ui.overlay

import android.view.MotionEvent
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
import com.fgogotran.R
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private const val DEFAULT_FLOATING_BUTTON_SIZE_DP = 54f

enum class FloatingButtonMode {
    MANUAL,
    SEMI_AUTO,
    AUTO,
    BATTLE,
    CROP
}

internal fun FloatingButtonMode.withBattleIndicator(battleModeActive: Boolean): FloatingButtonMode =
    if (battleModeActive && this != FloatingButtonMode.CROP) FloatingButtonMode.BATTLE else this

enum class FloatingActionIcon {
    GO,
    SEMI,
    AUTO,
    BATTLE,
    CROP,
    HISTORY_LIST,
    CLOSE_CIRCLE
}

@Composable
private fun FloatingActionIcon.textLabel(): String = when (this) {
    FloatingActionIcon.GO -> stringResource(R.string.mode_glyph_manual)
    FloatingActionIcon.SEMI -> stringResource(R.string.mode_glyph_semi)
    FloatingActionIcon.AUTO -> stringResource(R.string.mode_glyph_auto)
    FloatingActionIcon.BATTLE -> stringResource(R.string.mode_glyph_battle)
    else -> error("$this is not a text action")
}

/**
 * Draggable semi-transparent floating translate button.
 *
 * Tap requests one manual translation, long-press opens the menu, and movement
 * past touch slop drags the button. Keeping these gestures in one detector
 * avoids tap, long-press, and drag competing with each other.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun FloatingButton(
    mode: FloatingButtonMode,
    buttonSize: Dp = 54.dp,
    dockedSide: FloatingDockSide? = null,
    showFailureRing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: (Float, Float) -> Boolean,
    onDragTo: (Float, Float) -> Boolean,
    onDragEnd: (cancelled: Boolean) -> Unit
) {
    val visualButtonSize = buttonSize
    val glyphSize = visualButtonSize * 0.78f
    val glyphContentScale = (visualButtonSize.value / DEFAULT_FLOATING_BUTTON_SIZE_DP)
        .coerceIn(0.72f, 1.34f)
    val idleAlpha = 0.25f
    val pressedAlpha = 0.62f
    val baseColor = when (mode) {
        FloatingButtonMode.MANUAL -> Color(0xFF1E1E1E)
        FloatingButtonMode.SEMI_AUTO -> Color(0xFF1E1E1E)
        FloatingButtonMode.AUTO -> Color(0xFF1E1E1E)
        FloatingButtonMode.BATTLE -> Color(0xFF1E1E1E)
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
    val view = LocalView.current
    val rawTouchPosition = remember(view) { FloatArray(2) }
    val observeRawTouch = remember(view) {
        { event: MotionEvent ->
            rawTouchPosition[0] = event.rawX
            rawTouchPosition[1] = event.rawY
        }
    }
    val currentDockedSide by rememberUpdatedState(dockedSide)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragTo by rememberUpdatedState(onDragTo)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val modeDescription = stringResource(
        if (dockedSide != null) R.string.floating_button_edge_handle_desc else when (mode) {
            FloatingButtonMode.MANUAL -> R.string.mode_desc_manual
            FloatingButtonMode.SEMI_AUTO -> R.string.mode_desc_semi
            FloatingButtonMode.AUTO -> R.string.mode_desc_auto
            FloatingButtonMode.BATTLE -> R.string.mode_desc_battle
            FloatingButtonMode.CROP -> R.string.mode_desc_crop
        }
    )

    Box(
        modifier = Modifier
            .size(
                width = if (dockedSide != null) FloatingButtonDocking.HANDLE_WIDTH_DP.dp else visualButtonSize,
                height = if (dockedSide != null) FloatingButtonDocking.HANDLE_HEIGHT_DP.dp else visualButtonSize
            )
            .semantics {
                contentDescription = modeDescription
            }
            // Initial-pass observation supplies screen coordinates before the Main-pass detector.
            // Do not combine a local event position with a window location that moves mid-gesture.
            .motionEventSpy(observeRawTouch)
            // Keep this detector alive while a reveal resizes the same overlay window.
            .pointerInput(view) {
                fun screenPosition(): Offset = Offset(rawTouchPosition[0], rawTouchPosition[1])
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val downScreenPosition = screenPosition()
                        val gestureDockedSide = currentDockedSide
                        val dragSlop = max(viewConfiguration.touchSlop, 18.dp.toPx())
                        val longPressTimeout = minOf(viewConfiguration.longPressTimeoutMillis, 420L)

                        pressed = true
                        var firstDragPosition = downScreenPosition
                        var tapReleased = false
                        var dragStarted = false
                        var cancelled = false

                        suspend fun AwaitPointerEventScope.awaitDragOrRelease() {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }

                                if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                                    cancelled = true
                                    return
                                }

                                if (change.changedToUpIgnoreConsumed()) {
                                    tapReleased = true
                                    return
                                }

                                val position = screenPosition()
                                val displacement = position - downScreenPosition
                                if (FloatingButtonDocking.canStartDrag(
                                        gestureDockedSide, displacement.x, displacement.y, dragSlop
                                    )) {
                                    dragStarted = true
                                    firstDragPosition = position
                                    change.consume()
                                    return
                                }
                            }
                        }
                        // The handle has no long-press timer: it only waits for movement or release.
                        if (gestureDockedSide == null) {
                            withTimeoutOrNull(longPressTimeout) { awaitDragOrRelease() }
                        } else {
                            awaitDragOrRelease()
                        }

                        when {
                            dragStarted -> {
                                var released = false
                                val started = currentOnDragStart(downScreenPosition.x, downScreenPosition.y)
                                try {
                                    if (started && currentOnDragTo(firstDragPosition.x, firstDragPosition.y)) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == pointerId }
                                                ?: break
                                            if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                                            val position = screenPosition()
                                            if (!currentOnDragTo(position.x, position.y)) break
                                            if (change.changedToUpIgnoreConsumed()) {
                                                change.consume()
                                                released = true
                                                break
                                            }
                                            if (!change.pressed) break
                                            change.consume()
                                        }
                                    }
                                } finally {
                                    if (started) currentOnDragEnd(!released)
                                }
                                pressed = false
                            }

                            tapReleased -> {
                                pressed = false
                                if (gestureDockedSide == null) currentOnClick()
                            }

                            cancelled -> {
                                pressed = false
                            }

                            else -> {
                                pressed = false
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnLongClick()
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
            },
        contentAlignment = Alignment.Center
    ) {
        if (dockedSide != null) {
            Canvas(Modifier.fillMaxSize()) {
                val halfLineHeight = FloatingButtonDocking.HANDLE_LINE_HEIGHT_DP.dp.toPx() / 2f
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(size.width / 2f, size.height / 2f - halfLineHeight),
                    end = Offset(size.width / 2f, size.height / 2f + halfLineHeight),
                    strokeWidth = FloatingButtonDocking.HANDLE_LINE_WIDTH_DP.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        } else Surface(
            color = baseColor.copy(alpha = buttonAlpha),
            contentColor = Color.White.copy(alpha = if (pressed) 0.9f else 0.68f),
            border = if (showFailureRing) BorderStroke(3.dp, Color(0xFFFF4A4A)) else null,
            shape = CircleShape,
            shadowElevation = if (pressed) 8.dp else 0.dp,
            modifier = Modifier
                .size(visualButtonSize)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionGlyph(
                    icon = when (mode) {
                        FloatingButtonMode.MANUAL -> FloatingActionIcon.GO
                        FloatingButtonMode.SEMI_AUTO -> FloatingActionIcon.SEMI
                        FloatingButtonMode.AUTO -> FloatingActionIcon.AUTO
                        FloatingButtonMode.BATTLE -> FloatingActionIcon.BATTLE
                        FloatingButtonMode.CROP -> FloatingActionIcon.CROP
                    },
                    prominent = true,
                    color = Color.White.copy(alpha = if (pressed) 0.95f else 0.50f),
                    contentScale = glyphContentScale,
                    modifier = Modifier.size(glyphSize)
                )
            }
        }
    }
}

@Composable
fun FloatingActionGlyph(
    icon: FloatingActionIcon,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    prominent: Boolean = false,
    contentScale: Float = 1f
) {
    when (icon) {
        FloatingActionIcon.GO,
        FloatingActionIcon.SEMI,
        FloatingActionIcon.AUTO,
        FloatingActionIcon.BATTLE -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon.textLabel(),
                color = color,
                fontSize = scaledGlyphFontSize(icon, prominent, contentScale),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }

        FloatingActionIcon.CROP -> CropCornerIcon(
            modifier = modifier,
            color = color,
            contentScale = contentScale
        )
        FloatingActionIcon.HISTORY_LIST -> ListIcon(
            modifier = modifier,
            color = color,
            contentScale = contentScale
        )
        FloatingActionIcon.CLOSE_CIRCLE -> CloseCircleIcon(
            modifier = modifier,
            color = color,
            contentScale = contentScale
        )
    }
}

private fun scaledGlyphFontSize(
    icon: FloatingActionIcon,
    prominent: Boolean,
    contentScale: Float
) = (when {
    icon == FloatingActionIcon.GO && prominent -> 15f
    icon == FloatingActionIcon.SEMI && prominent -> 21f
    icon == FloatingActionIcon.SEMI -> 17f
    icon == FloatingActionIcon.AUTO && prominent -> 21f
    icon == FloatingActionIcon.AUTO -> 17f
    icon == FloatingActionIcon.BATTLE && prominent -> 21f
    icon == FloatingActionIcon.BATTLE -> 17f
    else -> 13f
} * contentScale).sp

@Composable
private fun CropCornerIcon(
    modifier: Modifier,
    color: Color,
    strokeWidth: Dp = 2.4.dp,
    contentScale: Float = 1f
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx() * contentScale
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
    strokeWidth: Dp = 2.2.dp,
    contentScale: Float = 1f
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx() * contentScale
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
    strokeWidth: Dp = 2.2.dp,
    contentScale: Float = 1f
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx() * contentScale
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



