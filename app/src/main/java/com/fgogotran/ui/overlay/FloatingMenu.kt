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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FloatingMenu(
    autoTranslateEnabled: Boolean,
    onAutoTranslateChange: (Boolean) -> Unit,
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
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

        AutoTranslateRow(
            enabled = autoTranslateEnabled,
            onEnabledChange = onAutoTranslateChange
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
private fun AutoTranslateRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MenuIcon(icon = FloatingActionIcon.AUTO, color = Color(0xFF333333))
            Text(
                text = "自动翻译",
                fontSize = 15.sp,
                color = Color(0xFF333333)
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
    }
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
