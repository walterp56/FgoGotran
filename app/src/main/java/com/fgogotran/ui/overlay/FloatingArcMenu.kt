package com.fgogotran.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val ARC_REFERENCE_DENSITY = 3f
private const val FAN_RADIUS_BASE = 92f
private const val PILL_WIDTH_BASE = 64f
private const val PILL_HEIGHT_BASE = 54f
private const val NAV_SIZE_BASE = 46f
private const val LABEL_SIZE_BASE = 11f
private const val SCRIM_ALPHA = 0.18f

@Composable
fun FloatingArcMenu(
    translationMode: TranslationMode,
    battleModeActive: Boolean,
    gameServer: String,
    liveVoiceTranslationEnabled: Boolean,
    buttonCenterX: Int,
    buttonCenterY: Int,
    viewportScale: Float,
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
        val screenW = maxWidth
        val screenH = maxHeight
        val centerX = with(density) { buttonCenterX.toDp() }
        val centerY = with(density) { buttonCenterY.toDp() }

        val pillWidth = scaledArcDp(PILL_WIDTH_BASE, viewportScale, density)
        val pillHeight = scaledArcDp(PILL_HEIGHT_BASE, viewportScale, density)
        val navSize = scaledArcDp(NAV_SIZE_BASE, viewportScale, density)

        val fan = computeFan(centerX, centerY, screenW, screenH, viewportScale, density)

        var page by remember { mutableStateOf(ArcPage.FEATURES) }

        val contentSlots = when (page) {
            ArcPage.FEATURES -> featureSlots(
                battleModeActive = battleModeActive,
                isJapaneseServer = isJapaneseServer,
                liveVoiceTranslationEnabled = liveVoiceTranslationEnabled,
                onHistory = onHistoryClick,
                onCrop = onCropTranslateClick,
                onBattleSelect = onBattleModeSelect,
                onLiveVoiceToggle = onLiveVoiceTranslationToggle
            )
            ArcPage.MODES -> modeSlots(
                translationMode = translationMode,
                battleModeActive = battleModeActive,
                onModeChange = onTranslationModeChange,
                onClose = onCloseClick
            )
        }

        contentSlots.forEachIndexed { index, slot ->
            val center = slotCenter(fan, fan.anglesDeg[index])
            ArcSector(
                slot = slot,
                viewportScale = viewportScale,
                modifier = Modifier
                    .offset(
                        x = center.x - pillWidth / 2,
                        y = center.y - pillHeight / 2
                    )
                    .size(pillWidth, pillHeight)
            )
        }

        val navCenter = slotCenter(fan, fan.anglesDeg.last())
        NavButton(
            label = if (page == ArcPage.FEATURES) "›" else "‹",
            viewportScale = viewportScale,
            onClick = {
                page = if (page == ArcPage.FEATURES) ArcPage.MODES else ArcPage.FEATURES
            },
            modifier = Modifier
                .offset(
                    x = navCenter.x - navSize / 2,
                    y = navCenter.y - navSize / 2
                )
                .size(navSize)
        )
    }
}

private enum class ArcPage { FEATURES, MODES }

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
        label = "翻译LOG",
        enabled = isJapaneseServer,
        onClick = onHistory
    ),
    ArcSlot(
        icon = FloatingActionIcon.CROP,
        label = "区域翻译",
        enabled = isJapaneseServer,
        onClick = onCrop
    ),
    ArcSlot(
        icon = FloatingActionIcon.BATTLE,
        label = "BATTLE",
        selected = battleModeActive,
        enabled = isJapaneseServer,
        onClick = onBattleSelect
    ),
    ArcSlot(
        icon = null,
        label = "实时字幕",
        toggle = true,
        toggleOn = liveVoiceTranslationEnabled,
        onClick = { onLiveVoiceToggle(!liveVoiceTranslationEnabled) }
    )
)

private fun modeSlots(
    translationMode: TranslationMode,
    battleModeActive: Boolean,
    onModeChange: (TranslationMode) -> Unit,
    onClose: () -> Unit
): List<ArcSlot> = listOf(
    ArcSlot(
        icon = FloatingActionIcon.GO,
        label = "手动",
        selected = !battleModeActive && translationMode == TranslationMode.MANUAL,
        onClick = { onModeChange(TranslationMode.MANUAL) }
    ),
    ArcSlot(
        icon = FloatingActionIcon.SEMI,
        label = "半自动",
        selected = !battleModeActive && translationMode == TranslationMode.SEMI_AUTO,
        onClick = { onModeChange(TranslationMode.SEMI_AUTO) }
    ),
    ArcSlot(
        icon = FloatingActionIcon.AUTO,
        label = "全自动",
        selected = !battleModeActive && translationMode == TranslationMode.AUTO,
        onClick = { onModeChange(TranslationMode.AUTO) }
    ),
    ArcSlot(
        icon = FloatingActionIcon.CLOSE_CIRCLE,
        label = "关闭服务",
        accentDanger = true,
        onClick = onClose
    )
)

@Composable
private fun ArcSector(
    slot: ArcSlot,
    viewportScale: Float,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val background = when {
        slot.accentDanger -> Color(0xFFFFF1F0)
        slot.selected -> Color(0xFF075F66)
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
            .clip(RoundedCornerShape(scaledArcDp(16f, viewportScale, density)))
            .background(background)
            .clickable(enabled = slot.enabled, onClick = slot.onClick)
            .padding(
                horizontal = scaledArcDp(4f, viewportScale, density),
                vertical = scaledArcDp(6f, viewportScale, density)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (slot.toggle) {
            ToggleDot(on = slot.toggleOn, viewportScale = viewportScale)
        } else if (slot.icon != null) {
            FloatingActionGlyph(
                icon = slot.icon,
                color = contentColor,
                contentScale = 1f,
                modifier = Modifier.size(scaledArcDp(20f, viewportScale, density))
            )
        }
        Spacer(modifier = Modifier.size(scaledArcDp(3f, viewportScale, density)))
        Text(
            text = slot.label,
            color = contentColor,
            fontSize = scaledArcSp(LABEL_SIZE_BASE, viewportScale, density),
            fontWeight = if (slot.selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun ToggleDot(on: Boolean, viewportScale: Float) {
    val density = LocalDensity.current
    val size = scaledArcDp(18f, viewportScale, density)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (on) Color(0xFF075F66) else Color(0xFFC9CBCE))
    )
}

@Composable
private fun NavButton(
    label: String,
    viewportScale: Float,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val density = LocalDensity.current
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
            fontSize = scaledArcSp(20f, viewportScale, density),
            fontWeight = FontWeight.Bold
        )
    }
}

private data class DpPoint(val x: Dp, val y: Dp)

private data class FanGeometry(
    val centerX: Dp,
    val centerY: Dp,
    val radius: Dp,
    val anglesDeg: List<Float>
)

private fun computeFan(
    centerX: Dp,
    centerY: Dp,
    screenW: Dp,
    screenH: Dp,
    viewportScale: Float,
    density: Density
): FanGeometry {
    val radius = scaledArcDp(FAN_RADIUS_BASE, viewportScale, density).coerceAtLeast(56.dp)
    val pillHalfWidth = scaledArcDp(PILL_WIDTH_BASE, viewportScale, density) / 2
    val pillHalfHeight = scaledArcDp(PILL_HEIGHT_BASE, viewportScale, density) / 2
    val edgePad = scaledArcDp(8f, viewportScale, density)

    val freeLeft = centerX
    val freeRight = screenW - centerX
    var openRight = freeRight >= freeLeft
    var chosenRadius = radius

    fun sideClearance(openRight: Boolean): Dp =
        if (openRight) screenW - centerX else centerX

    val neededSide = radius + pillHalfWidth + edgePad
    if (sideClearance(openRight) < neededSide) {
        openRight = !openRight
        if (sideClearance(openRight) < neededSide) {
            chosenRadius = (maxOf(freeLeft, freeRight) - pillHalfWidth - edgePad)
                .coerceAtLeast(44.dp)
        }
    }

    val verticalPad = chosenRadius + pillHalfHeight + edgePad
    val clampedCenterY =
        if (screenH > verticalPad * 2) centerY.coerceIn(verticalPad, screenH - verticalPad)
        else screenH / 2

    val angles = if (openRight) {
        listOf(-90f, -45f, 0f, 45f, 90f)
    } else {
        listOf(90f, 135f, 180f, 225f, 270f)
    }

    return FanGeometry(centerX, clampedCenterY, chosenRadius, angles)
}

private fun slotCenter(fan: FanGeometry, angleDeg: Float): DpPoint {
    val radians = angleDeg * PI.toFloat() / 180f
    return DpPoint(
        x = fan.centerX + fan.radius * cos(radians),
        y = fan.centerY + fan.radius * sin(radians)
    )
}

private fun scaledArcDp(baseDp: Float, viewportScale: Float, density: Density): Dp {
    val safeScale = viewportScale.coerceIn(0.75f, 1.4f)
    val target = with(density) { (baseDp * ARC_REFERENCE_DENSITY * safeScale).toDp() }
    return if (target.value > baseDp) target else baseDp.dp
}

private fun scaledArcSp(baseSp: Float, viewportScale: Float, density: Density): TextUnit {
    val safeScale = viewportScale.coerceIn(0.75f, 1.4f)
    val target = with(density) { (baseSp * ARC_REFERENCE_DENSITY * safeScale).toSp() }
    return maxOf(baseSp, target.value).sp
}
