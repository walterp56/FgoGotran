package com.fgogotran.ui.overlay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fgogotran.R

/**
 * A draggable semi-transparent circular floating button.
 *
 * Like FGA's ScriptRunnerUI play button:
 * - Renders as a circle with a translate icon
 * - Semi-transparent (alpha = 0.6) so it doesn't fully obscure FGO content
 * - Supports drag gestures via [detectDragGestures]
 * - Calls [onClick] for a one-shot translation and [onLongClick] for the menu
 *
 * @param onClick called when the user taps the button (not drags)
 * @param onLongClick called when the user holds the button
 * @param onDrag called with dx,dy pixel deltas while the user drags
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun FloatingButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    Surface(
        color = Color(0xFF1E1E1E).copy(alpha = 0.6f),
        contentColor = Color.White.copy(alpha = 0.9f),
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // Translate icon (or text fallback if drawable not found)
        Icon(
            painter = painterResource(R.drawable.ic_translate),
            contentDescription = "翻譯選單",
            modifier = Modifier.padding(14.dp)
        )
    }
}
