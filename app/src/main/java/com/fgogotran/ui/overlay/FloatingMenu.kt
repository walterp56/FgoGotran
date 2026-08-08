package com.fgogotran.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationMode
import kotlin.math.roundToInt

private const val MENU_REFERENCE_DENSITY = 3f

@Composable
fun FloatingMenu(
    translationMode: TranslationMode,
    viewportScale: Float = 1f,
    gameServer: String,
    voiceEnabled: Boolean,
    voiceVolumePercent: Int,
    onTranslationModeChange: (TranslationMode) -> Unit,
    onVoiceVolumeChange: (Int) -> Unit,
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    val density = LocalDensity.current
    val isJapaneseServer =
        SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scaledMenuDp(8f, viewportScale, density))
    ) {
        if (voiceEnabled) {
            VerticalVoiceVolumeSlider(
                enabled = true,
                volumePercent = voiceVolumePercent,
                viewportScale = viewportScale,
                onVolumeChange = onVoiceVolumeChange
            )
        }

        Column(
            modifier = Modifier
                .width(scaledMenuDp(230f, viewportScale, density))
                .background(Color.White, RoundedCornerShape(scaledMenuDp(16f, viewportScale, density)))
                .padding(vertical = scaledMenuDp(8f, viewportScale, density))
        ) {
            Text(
                text = "FgoGotran",
                fontSize = scaledMenuSp(14f, viewportScale, density),
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = scaledMenuDp(20f, viewportScale, density),
                        vertical = scaledMenuDp(12f, viewportScale, density)
                    )
            )

            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

            TranslationModeSelector(
                selectedMode = translationMode,
                viewportScale = viewportScale,
                label = if (isJapaneseServer) "翻译模式" else "朗读模式",
                onModeChange = onTranslationModeChange
            )

            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

            MenuRow(
                icon = FloatingActionIcon.CROP,
                viewportScale = viewportScale,
                label = "区域翻译",
                enabled = isJapaneseServer,
                onClick = onCropTranslateClick
            )

            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

            MenuRow(
                icon = FloatingActionIcon.HISTORY_LIST,
                viewportScale = viewportScale,
                label = "翻译LOG",
                enabled = isJapaneseServer,
                onClick = onHistoryClick
            )

            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

            MenuRow(
                icon = FloatingActionIcon.CLOSE_CIRCLE,
                viewportScale = viewportScale,
                label = "关闭服务",
                muted = true,
                onClick = onCloseClick
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: FloatingActionIcon,
    label: String,
    viewportScale: Float,
    muted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val color = when {
        !enabled -> Color(0xFFBBBBBB)
        muted -> Color(0xFF999999)
        else -> Color(0xFF333333)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(
                horizontal = scaledMenuDp(20f, viewportScale, density),
                vertical = scaledMenuDp(14f, viewportScale, density)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scaledMenuDp(12f, viewportScale, density))
    ) {
        MenuIcon(icon = icon, color = color, viewportScale = viewportScale)
        Text(
            text = label,
            fontSize = scaledMenuSp(15f, viewportScale, density),
            color = color
        )
    }
}

@Composable
private fun TranslationModeSelector(
    selectedMode: TranslationMode,
    viewportScale: Float,
    label: String,
    onModeChange: (TranslationMode) -> Unit
) {
    val density = LocalDensity.current
    Text(
        text = label,
        fontSize = scaledMenuSp(12f, viewportScale, density),
        fontWeight = FontWeight.Bold,
        color = Color(0xFF777777),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = scaledMenuDp(20f, viewportScale, density),
                vertical = scaledMenuDp(8f, viewportScale, density)
            )
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = scaledMenuDp(20f, viewportScale, density),
                vertical = scaledMenuDp(8f, viewportScale, density)
            ),
        horizontalArrangement = Arrangement.spacedBy(scaledMenuDp(6f, viewportScale, density))
    ) {
        ModeSegment(
            label = TranslationMode.MANUAL.label(),
            selected = selectedMode == TranslationMode.MANUAL,
            viewportScale = viewportScale,
            onClick = { onModeChange(TranslationMode.MANUAL) },
            modifier = Modifier.weight(1f)
        )
        ModeSegment(
            label = TranslationMode.SEMI_AUTO.label(),
            selected = selectedMode == TranslationMode.SEMI_AUTO,
            viewportScale = viewportScale,
            onClick = { onModeChange(TranslationMode.SEMI_AUTO) },
            modifier = Modifier.weight(1f)
        )
        ModeSegment(
            label = TranslationMode.AUTO.label(),
            selected = selectedMode == TranslationMode.AUTO,
            viewportScale = viewportScale,
            onClick = { onModeChange(TranslationMode.AUTO) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    viewportScale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val background = if (selected) Color(0xFF075F66) else Color(0xFFF2F3F5)
    val color = if (selected) Color.White else Color(0xFF333333)
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(scaledMenuDp(8f, viewportScale, density)))
            .clickable { onClick() }
            .padding(
                horizontal = scaledMenuDp(4f, viewportScale, density),
                vertical = scaledMenuDp(9f, viewportScale, density)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = scaledMenuSp(14f, viewportScale, density),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun TranslationMode.label(): String = when (this) {
    TranslationMode.MANUAL -> "手动"
    TranslationMode.SEMI_AUTO -> "半自动"
    TranslationMode.AUTO -> "全自动"
}

@Composable
private fun VerticalVoiceVolumeSlider(
    enabled: Boolean,
    volumePercent: Int,
    viewportScale: Float,
    onVolumeChange: (Int) -> Unit
) {
    val density = LocalDensity.current
    val safeVolume = SettingsRepository.normalizeAiVoiceVolumePercent(volumePercent)
    var displayVolume by remember { mutableStateOf(safeVolume.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(safeVolume, enabled) {
        if (!isDragging || !enabled) {
            isDragging = false
            displayVolume = safeVolume.toFloat()
        }
    }

    fun commitDisplayVolume() {
        if (!enabled) {
            isDragging = false
            displayVolume = safeVolume.toFloat()
            return
        }
        val committedVolume = SettingsRepository.normalizeAiVoiceVolumePercent(displayVolume.roundToInt())
        displayVolume = committedVolume.toFloat()
        if (committedVolume != safeVolume) {
            onVolumeChange(committedVolume)
        }
        isDragging = false
    }

    val displayVolumePercent = SettingsRepository.normalizeAiVoiceVolumePercent(displayVolume.roundToInt())
    val contentColor = if (enabled) Color(0xFF075F66) else Color(0xFF9AA2A7)

    Column(
        modifier = Modifier
            .width(scaledMenuDp(48f, viewportScale, density))
            .background(Color.White, RoundedCornerShape(scaledMenuDp(16f, viewportScale, density)))
            .padding(
                horizontal = scaledMenuDp(6f, viewportScale, density),
                vertical = scaledMenuDp(10f, viewportScale, density)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$displayVolumePercent%",
            fontSize = scaledMenuSp(10f, viewportScale, density),
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        VoiceVolumeTrack(
            enabled = enabled,
            volumePercent = displayVolume,
            viewportScale = viewportScale,
            onVolumeChange = { volume ->
                if (enabled) {
                    displayVolume = volume
                }
            },
            onDragStart = {
                if (enabled) {
                    isDragging = true
                }
            },
            onDragEnd = { commitDisplayVolume() }
        )
    }
}

@Composable
private fun VoiceVolumeTrack(
    enabled: Boolean,
    volumePercent: Float,
    viewportScale: Float,
    onVolumeChange: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit
) {
    val density = LocalDensity.current
    val trackHeight = scaledMenuDp(186f, viewportScale, density)
    val trackWidth = scaledMenuDp(34f, viewportScale, density)
    val primaryColor = if (enabled) Color(0xFF075F66) else Color(0xFF9AA2A7)
    val trackColor = if (enabled) Color(0xFFE7ECEF) else Color(0xFFE1E4E6)
    val thumbColor = if (enabled) Color.White else Color(0xFFF7F7F7)
    val minVolume = SettingsRepository.MIN_AI_VOICE_VOLUME_PERCENT
    val maxVolume = SettingsRepository.MAX_AI_VOICE_VOLUME_PERCENT
    val normalizedVolume = volumePercent.coerceIn(minVolume.toFloat(), maxVolume.toFloat())

    fun volumeFromY(y: Float, height: Float): Float {
        val safeHeight = height.coerceAtLeast(1f)
        val fraction = (1f - (y / safeHeight)).coerceIn(0f, 1f)
        return (minVolume + (maxVolume - minVolume) * fraction)
            .coerceIn(minVolume.toFloat(), maxVolume.toFloat())
    }

    val inputModifier = if (enabled) {
        Modifier.pointerInput(onVolumeChange, onDragStart, onDragEnd) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointerId = down.id
                onDragStart()
                try {
                    onVolumeChange(volumeFromY(down.position.y, size.height.toFloat()))

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            break
                        }
                        onVolumeChange(volumeFromY(change.position.y, size.height.toFloat()))
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                        }
                    }
                } finally {
                    onDragEnd()
                }
            }
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .padding(vertical = scaledMenuDp(8f, viewportScale, density))
            .then(inputModifier)
    ) {
        val trackStroke = scaledMenuDp(5f, viewportScale, density).toPx()
        val thumbRadius = scaledMenuDp(8f, viewportScale, density).toPx()
        val centerX = size.width / 2f
        val trackTop = thumbRadius
        val trackBottom = size.height - thumbRadius
        val usableHeight = (trackBottom - trackTop).coerceAtLeast(1f)
        val volumeRange = (maxVolume - minVolume).coerceAtLeast(1).toFloat()
        val fraction = (normalizedVolume - minVolume) / volumeRange
        val thumbCenterY = trackBottom - usableHeight * fraction

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(centerX - trackStroke / 2f, trackTop),
            size = Size(trackStroke, usableHeight),
            cornerRadius = CornerRadius(trackStroke / 2f, trackStroke / 2f)
        )
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(centerX - trackStroke / 2f, thumbCenterY),
            size = Size(trackStroke, trackBottom - thumbCenterY),
            cornerRadius = CornerRadius(trackStroke / 2f, trackStroke / 2f)
        )
        drawCircle(
            color = primaryColor.copy(alpha = 0.22f),
            radius = thumbRadius * 1.45f,
            center = Offset(centerX, thumbCenterY)
        )
        drawCircle(
            color = thumbColor,
            radius = thumbRadius,
            center = Offset(centerX, thumbCenterY)
        )
        drawCircle(
            color = primaryColor,
            radius = thumbRadius,
            center = Offset(centerX, thumbCenterY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = scaledMenuDp(2f, viewportScale, density).toPx()
            )
        )
    }
}

@Composable
private fun MenuIcon(
    icon: FloatingActionIcon,
    color: Color,
    viewportScale: Float
) {
    val density = LocalDensity.current
    val glyphSize = scaledMenuDp(24f, viewportScale, density)
    Box(
        modifier = Modifier.size(scaledMenuDp(28f, viewportScale, density)),
        contentAlignment = Alignment.Center
    ) {
        FloatingActionGlyph(
            icon = icon,
            color = color,
            contentScale = (glyphSize.value / 24f).coerceAtLeast(1f),
            modifier = Modifier.size(glyphSize)
        )
    }
}

private fun scaledMenuDp(baseDp: Float, viewportScale: Float, density: Density): Dp {
    val safeScale = viewportScale.coerceIn(0.75f, 1.4f)
    val target = with(density) { (baseDp * MENU_REFERENCE_DENSITY * safeScale).toDp() }
    return if (target.value > baseDp) target else baseDp.dp
}

private fun scaledMenuSp(baseSp: Float, viewportScale: Float, density: Density): TextUnit {
    val safeScale = viewportScale.coerceIn(0.75f, 1.4f)
    val target = with(density) { (baseSp * MENU_REFERENCE_DENSITY * safeScale).toSp() }
    return maxOf(baseSp, target.value).sp
}
