package com.fgogotran.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationMode

private const val MENU_REFERENCE_DENSITY = 3f

@Composable
fun FloatingMenu(
    translationMode: TranslationMode,
    viewportScale: Float = 1f,
    gameServer: String,
    aiVoiceEnabled: Boolean,
    liveVoiceTranslationEnabled: Boolean,
    onTranslationModeChange: (TranslationMode) -> Unit,
    onLiveVoiceTranslationToggle: (Boolean) -> Unit,
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val isJapaneseServer =
        SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP
    val modeLabel = floatingMenuModeLabel(
        isJapaneseServer = isJapaneseServer,
        aiVoiceEnabled = aiVoiceEnabled
    )
    val serverLabel = SettingsRepository.gameServerDisplayName(gameServer)
    val menuShape = RoundedCornerShape(scaledMenuDp(16f, viewportScale, density))
    val menuWidth = minOf(
        scaledMenuDp(276f, viewportScale, density),
        (configuration.screenWidthDp * 0.9f).dp
    )
    val menuMaxHeight = (configuration.screenHeightDp * 0.9f).dp
    Column(
        modifier = Modifier
            .width(menuWidth)
            .heightIn(max = menuMaxHeight)
            .clip(menuShape)
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(scaledMenuDp(12f, viewportScale, density)),
        verticalArrangement = Arrangement.spacedBy(scaledMenuDp(10f, viewportScale, density))
    ) {
        MenuHeader(
            serverLabel = serverLabel,
            viewportScale = viewportScale
        )

        TranslationModeSelector(
            selectedMode = translationMode,
            viewportScale = viewportScale,
            label = modeLabel,
            onModeChange = onTranslationModeChange
        )

        LiveVoiceTranslationSwitchRow(
            checked = liveVoiceTranslationEnabled,
            viewportScale = viewportScale,
            onCheckedChange = onLiveVoiceTranslationToggle
        )

        MenuActionGroup(
            isJapaneseServer = isJapaneseServer,
            viewportScale = viewportScale,
            onCropTranslateClick = onCropTranslateClick,
            onHistoryClick = onHistoryClick
        )

        CloseServiceRow(
            viewportScale = viewportScale,
            onClick = onCloseClick
        )
    }
}

@Composable
private fun MenuHeader(
    serverLabel: String,
    viewportScale: Float
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = scaledMenuDp(4f, viewportScale, density),
                vertical = scaledMenuDp(2f, viewportScale, density)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FgoGotran",
            fontSize = scaledMenuSp(16f, viewportScale, density),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF252525),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = serverLabel,
            fontSize = scaledMenuSp(12f, viewportScale, density),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF075F66),
            modifier = Modifier
                .background(
                    color = Color(0xFFE5F3F2),
                    shape = RoundedCornerShape(scaledMenuDp(20f, viewportScale, density))
                )
                .padding(
                    horizontal = scaledMenuDp(10f, viewportScale, density),
                    vertical = scaledMenuDp(5f, viewportScale, density)
                )
        )
    }
}

@Composable
private fun LiveVoiceTranslationSwitchRow(
    checked: Boolean,
    viewportScale: Float,
    onCheckedChange: (Boolean) -> Unit
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(scaledMenuDp(12f, viewportScale, density))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (checked) Color(0xFFEAF6F4) else Color(0xFFF7F8FA))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(
                horizontal = scaledMenuDp(12f, viewportScale, density),
                vertical = scaledMenuDp(10f, viewportScale, density)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scaledMenuDp(12f, viewportScale, density))
    ) {
        Text(
            text = "实时字幕",
            fontSize = scaledMenuSp(15f, viewportScale, density),
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Composable
private fun MenuActionGroup(
    isJapaneseServer: Boolean,
    viewportScale: Float,
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit
) {
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(scaledMenuDp(12f, viewportScale, density)))
            .background(Color(0xFFF7F8FA))
    ) {
        MenuRow(
            icon = FloatingActionIcon.CROP,
            viewportScale = viewportScale,
            label = "区域翻译",
            enabled = isJapaneseServer,
            onClick = onCropTranslateClick
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = scaledMenuDp(12f, viewportScale, density)),
            color = Color(0xFFE6E8EA),
            thickness = scaledMenuDp(1f, viewportScale, density)
        )
        MenuRow(
            icon = FloatingActionIcon.HISTORY_LIST,
            viewportScale = viewportScale,
            label = "翻译LOG",
            enabled = isJapaneseServer,
            onClick = onHistoryClick
        )
    }
}

@Composable
private fun CloseServiceRow(
    viewportScale: Float,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val color = Color(0xFFB3261E)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(scaledMenuDp(12f, viewportScale, density)))
            .background(Color(0xFFFFF1F0))
            .clickable(onClick = onClick)
            .padding(
                horizontal = scaledMenuDp(12f, viewportScale, density),
                vertical = scaledMenuDp(10f, viewportScale, density)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scaledMenuDp(12f, viewportScale, density))
    ) {
        MenuIcon(
            icon = FloatingActionIcon.CLOSE_CIRCLE,
            color = color,
            viewportScale = viewportScale
        )
        Text(
            text = "关闭服务",
            fontSize = scaledMenuSp(15f, viewportScale, density),
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun MenuRow(
    icon: FloatingActionIcon,
    label: String,
    viewportScale: Float,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val color = when {
        !enabled -> Color(0xFFBBBBBB)
        else -> Color(0xFF333333)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(
                horizontal = scaledMenuDp(12f, viewportScale, density),
                vertical = scaledMenuDp(11f, viewportScale, density)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(scaledMenuDp(12f, viewportScale, density)))
            .background(Color(0xFFF7F8FA))
            .padding(
                horizontal = scaledMenuDp(12f, viewportScale, density),
                vertical = scaledMenuDp(12f, viewportScale, density)
            ),
        verticalArrangement = Arrangement.spacedBy(scaledMenuDp(10f, viewportScale, density))
    ) {
        Text(
            text = label,
            fontSize = scaledMenuSp(12f, viewportScale, density),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF777777),
            maxLines = 1
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
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

private fun floatingMenuModeLabel(
    isJapaneseServer: Boolean,
    aiVoiceEnabled: Boolean
): String = when {
    isJapaneseServer && aiVoiceEnabled -> "翻译 + 朗读模式"
    isJapaneseServer -> "翻译模式"
    aiVoiceEnabled -> "朗读模式"
    else -> "朗读模式（未开启）"
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
