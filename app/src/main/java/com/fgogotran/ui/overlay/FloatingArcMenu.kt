package com.fgogotran.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationMode

private const val SCRIM_ALPHA = 0.18f
private val MENU_ACCENT_COLOR = Color(0xFF76518F)

@Composable
fun FloatingArcMenu(
    translationMode: TranslationMode,
    battleModeActive: Boolean,
    gameServer: String,
    liveVoiceTranslationEnabled: Boolean,
    buttonCenterX: Int,
    buttonCenterY: Int,
    onDismiss: () -> Unit,
    onTranslationModeChange: (TranslationMode) -> Unit,
    onBattleModeSelect: () -> Unit,
    onLiveVoiceTranslationToggle: (Boolean) -> Unit,
    onCropTranslateClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    val density = LocalDensity.current
    val isJapaneseServer =
        SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP
    val logLabel = stringResource(R.string.menu_log)
    val areaLabel = stringResource(R.string.menu_area)
    val battleLabel = stringResource(R.string.menu_battle)
    val liveLabel = stringResource(R.string.menu_live)
    val manualLabel = stringResource(R.string.menu_manual)
    val semiLabel = stringResource(R.string.menu_semi)
    val autoLabel = stringResource(R.string.menu_auto)
    val closeLabel = stringResource(R.string.menu_close)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    val navigationStyle = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val allSlots = featureSlots(
        logLabel, areaLabel, battleLabel, liveLabel, battleModeActive, isJapaneseServer,
        liveVoiceTranslationEnabled, onHistoryClick, onCropTranslateClick, onBattleModeSelect,
        onLiveVoiceTranslationToggle
    ) + modeSlots(
        manualLabel, semiLabel, autoLabel, closeLabel, translationMode, battleModeActive,
        onTranslationModeChange, onCloseClick
    )
    val insets = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.TopStart
    ) {
        val bounds = with(density) {
            ArcMenuRect(
                insets.getLeft(this, layoutDirection).toDp().value + 8f,
                insets.getTop(this).toDp().value + 8f,
                maxWidth.value - insets.getRight(this, layoutDirection).toDp().value - 8f,
                maxHeight.value - insets.getBottom(this).toDp().value - 8f
            )
        }
        val labels = allSlots.map { it.label }
        val pillSize = remember(labels, labelStyle, textMeasurer, density, bounds.width) {
            // Measure actual localized captions at the current Android font setting, with at most two lines.
            val widths = (listOf(64f, 80f, 96f, 112f, 128f, 144f) + bounds.width)
                .map { it.coerceAtMost(bounds.width).coerceAtLeast(1f) }.distinct()
            fun measureLabels(width: Float) =
                width to labels.map { label ->
                    textMeasurer.measure(
                        text = label,
                        style = labelStyle,
                        maxLines = 2,
                        constraints = Constraints(maxWidth = with(density) { (width - 8f).dp.roundToPx().coerceAtLeast(1) })
                    )
                }
            val (width, measured) = widths.asSequence().map(::measureLabels)
                .firstOrNull { (_, results) -> results.none { it.hasVisualOverflow } }
                ?: measureLabels(widths.last())
            val labelHeight = with(density) { measured.maxOf { it.size.height }.toDp().value }
            width to maxOf(54f, 20f + 3f + labelHeight + 12f + 2f)
        }
        val navMeasurement = textMeasurer.measure("›", navigationStyle)
        val navSize = maxOf(46f, with(density) { maxOf(navMeasurement.size.width, navMeasurement.size.height).toDp().value } + 12f)
        val layout = ArcMenuGeometry.layout(
            bounds, with(density) { buttonCenterX.toDp().value }, with(density) { buttonCenterY.toDp().value },
            pillSize.first, pillSize.second, navSize
        ) ?: return@BoxWithConstraints // A window smaller than one action plus navigation cannot host an arc.
        val pages = ArcMenuGeometry.pageIndices(layout.capacity)
        var page by remember(layout.capacity) { mutableStateOf(0) }
        val contentSlots = pages[page].map { allSlots[it] }

        contentSlots.forEachIndexed { index, slot ->
            val rect = layout.actions[index]
            ArcSector(
                slot = slot,
                labelStyle = labelStyle,
                modifier = Modifier
                    .offset(x = rect.left.dp, y = rect.top.dp)
                    .size(rect.width.dp, rect.height.dp)
            )
        }

        val nav = layout.navigation
        NavButton(
            label = if (page == pages.lastIndex) "‹" else "›",
            textStyle = navigationStyle,
            onClick = { page = (page + 1) % pages.size },
            modifier = Modifier
                .offset(x = nav.left.dp, y = nav.top.dp)
                .size(nav.width.dp, nav.height.dp)
        )
    }
}
private data class ArcSlot(
    val icon: FloatingActionIcon?,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val accentDanger: Boolean = false,
    val toggle: Boolean = false,
    val toggleOn: Boolean = false,
    val onClick: () -> Unit
)

private fun featureSlots(
    logLabel: String,
    areaLabel: String,
    battleLabel: String,
    liveLabel: String,
    battleModeActive: Boolean,
    isJapaneseServer: Boolean,
    liveVoiceTranslationEnabled: Boolean,
    onHistory: () -> Unit,
    onCrop: () -> Unit,
    onBattleSelect: () -> Unit,
    onLiveVoiceToggle: (Boolean) -> Unit
): List<ArcSlot> = listOf(
    ArcSlot(
        icon = FloatingActionIcon.HISTORY_LIST,
        label = logLabel,
        enabled = isJapaneseServer,
        onClick = onHistory
    ),
    ArcSlot(
        icon = FloatingActionIcon.CROP,
        label = areaLabel,
        enabled = isJapaneseServer,
        onClick = onCrop
    ),
    ArcSlot(
        icon = FloatingActionIcon.BATTLE,
        label = battleLabel,
        selected = battleModeActive,
        enabled = isJapaneseServer,
        onClick = onBattleSelect
    ),
    ArcSlot(
        icon = null,
        label = liveLabel,
        toggle = true,
        toggleOn = liveVoiceTranslationEnabled,
        onClick = { onLiveVoiceToggle(!liveVoiceTranslationEnabled) }
    )
)

private fun modeSlots(
    manualLabel: String,
    semiLabel: String,
    autoLabel: String,
    closeLabel: String,
    translationMode: TranslationMode,
    battleModeActive: Boolean,
    onModeChange: (TranslationMode) -> Unit,
    onClose: () -> Unit
): List<ArcSlot> = listOf(
    ArcSlot(
        icon = FloatingActionIcon.GO,
        label = manualLabel,
        selected = !battleModeActive && translationMode == TranslationMode.MANUAL,
        onClick = { onModeChange(TranslationMode.MANUAL) }
    ),
    ArcSlot(
        icon = FloatingActionIcon.SEMI,
        label = semiLabel,
        selected = !battleModeActive && translationMode == TranslationMode.SEMI_AUTO,
        onClick = { onModeChange(TranslationMode.SEMI_AUTO) }
    ),
    ArcSlot(
        icon = FloatingActionIcon.AUTO,
        label = autoLabel,
        selected = !battleModeActive && translationMode == TranslationMode.AUTO,
        onClick = { onModeChange(TranslationMode.AUTO) }
    ),
    ArcSlot(
        icon = FloatingActionIcon.CLOSE_CIRCLE,
        label = closeLabel,
        accentDanger = true,
        onClick = onClose
    )
)

@Composable
private fun ArcSector(
    slot: ArcSlot,
    labelStyle: TextStyle,
    modifier: Modifier
) {
    val background = when {
        slot.accentDanger -> Color(0xFFFFF1F0)
        slot.selected -> MENU_ACCENT_COLOR
        slot.enabled -> Color.White
        else -> Color(0xFFE9EAEC)
    }
    val contentColor = when {
        slot.accentDanger -> Color(0xFFB3261E)
        slot.selected -> Color.White
        slot.enabled -> Color(0xFF333333)
        else -> Color(0xFFAAAAAA)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(enabled = slot.enabled, onClick = slot.onClick)
            .padding(
                horizontal = 4.dp,
                vertical = 6.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (slot.toggle) {
            ToggleDot(on = slot.toggleOn)
        } else if (slot.icon != null) {
            FloatingActionGlyph(
                icon = slot.icon,
                color = contentColor,
                contentScale = 1f,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.size(3.dp))
        Text(
            text = slot.label,
            color = contentColor,
            style = labelStyle,
            fontWeight = if (slot.selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToggleDot(on: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (on) MENU_ACCENT_COLOR else Color(0xFFC9CBCE))
    )
}

@Composable
private fun NavButton(
    label: String,
    textStyle: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.96f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFF333333),
            style = textStyle,
            fontWeight = FontWeight.Bold
        )
    }
}
