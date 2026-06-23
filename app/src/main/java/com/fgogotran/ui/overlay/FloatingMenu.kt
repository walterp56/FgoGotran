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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.translation.TranslationMode

@Composable
fun FloatingMenu(
    translationMode: TranslationMode,
    onTranslationModeChange: (TranslationMode) -> Unit,
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(230.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "FgoGotran",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF333333),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        TranslationModeSelector(
            selectedMode = translationMode,
            onModeChange = onTranslationModeChange
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        MenuRow(
            icon = FloatingActionIcon.CROP,
            label = "区域翻译",
            onClick = onCropTranslateClick
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        MenuRow(
            icon = FloatingActionIcon.HISTORY_LIST,
            label = "翻译LOG",
            onClick = onHistoryClick
        )

        HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)

        MenuRow(
            icon = FloatingActionIcon.CLOSE_CIRCLE,
            label = "关闭服务",
            muted = true,
            onClick = onCloseClick
        )
    }
}

@Composable
private fun MenuRow(
    icon: FloatingActionIcon,
    label: String,
    muted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val color = if (muted) Color(0xFF999999) else Color(0xFF333333)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MenuIcon(icon = icon, color = color)
        Text(
            text = label,
            fontSize = 15.sp,
            color = color
        )
    }
}

@Composable
private fun TranslationModeSelector(
    selectedMode: TranslationMode,
    onModeChange: (TranslationMode) -> Unit
) {
    Text(
        text = "翻译模式",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF777777),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModeSegment(
            label = TranslationMode.MANUAL.label(),
            selected = selectedMode == TranslationMode.MANUAL,
            onClick = { onModeChange(TranslationMode.MANUAL) },
            modifier = Modifier.weight(1f)
        )
        ModeSegment(
            label = TranslationMode.SEMI_AUTO.label(),
            selected = selectedMode == TranslationMode.SEMI_AUTO,
            onClick = { onModeChange(TranslationMode.SEMI_AUTO) },
            modifier = Modifier.weight(1f)
        )
        ModeSegment(
            label = TranslationMode.AUTO.label(),
            selected = selectedMode == TranslationMode.AUTO,
            onClick = { onModeChange(TranslationMode.AUTO) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (selected) Color(0xFF075F66) else Color(0xFFF2F3F5)
    val color = if (selected) Color.White else Color(0xFF333333)
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
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
    TranslationMode.AUTO -> "自动"
}

@Composable
private fun MenuIcon(
    icon: FloatingActionIcon,
    color: Color
) {
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center
    ) {
        FloatingActionGlyph(
            icon = icon,
            color = color,
            modifier = Modifier.size(24.dp)
        )
    }
}
