package com.fgogotran.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.translation.TranslationMode

private const val MENU_REFERENCE_DENSITY = 3f

@Composable
fun FloatingMenu(
    translationMode: TranslationMode,
    voiceSubtitleEnabled: Boolean = false,
    viewportScale: Float = 1f,
    onTranslationModeChange: (TranslationMode) -> Unit,
    onVoiceSubtitleChange: (Boolean) -> Unit = {},
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    val density = LocalDensity.current
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
            onModeChange = onTranslationModeChange
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

        VoiceSubtitleRow(
            enabled = voiceSubtitleEnabled,
            viewportScale = viewportScale,
            onCheckedChange = onVoiceSubtitleChange
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

        MenuRow(
            icon = FloatingActionIcon.CROP,
            viewportScale = viewportScale,
            label = "区域翻译",
            onClick = onCropTranslateClick
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = scaledMenuDp(1f, viewportScale, density))

        MenuRow(
            icon = FloatingActionIcon.HISTORY_LIST,
            viewportScale = viewportScale,
            label = "翻译LOG",
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

@Composable
private fun VoiceSubtitleRow(
    enabled: Boolean,
    viewportScale: Float,
    onCheckedChange: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!enabled) }
            .padding(
                horizontal = scaledMenuDp(20f, viewportScale, density),
                vertical = scaledMenuDp(10f, viewportScale, density)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scaledMenuDp(12f, viewportScale, density))
    ) {
        MenuIcon(
            icon = FloatingActionIcon.VOICE_SUBTITLE,
            color = Color(0xFF333333),
            viewportScale = viewportScale
        )
        Text(
            text = "语音字幕",
            fontSize = scaledMenuSp(15f, viewportScale, density),
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = null
        )
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
    val color = if (muted) Color(0xFF999999) else Color(0xFF333333)
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
    onModeChange: (TranslationMode) -> Unit
) {
    val density = LocalDensity.current
    Text(
        text = "翻译模式",
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
