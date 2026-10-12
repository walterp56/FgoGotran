package com.fgogotran.ui.overlay

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Menu Android-sizing and history game-content wiring, alongside pure geometry/sizing tests. */
class OverlayUiSizingWorkflowTest {
    @Test
    fun `arc measures localized sp captions instead of multiplying by the FGO scale`() {
        val menu = source("ui/overlay/FloatingArcMenu.kt")
        for (oldScale in listOf("viewportScale", "scaledArcDp", "scaledArcSp", "REFERENCE_DENSITY")) {
            assertFalse(menu.contains(oldScale))
        }
        assertTrue(menu.contains("rememberTextMeasurer()"))
        assertTrue(menu.contains("fontSize = 11.sp"))
        assertTrue(menu.contains("lineHeight = 14.sp"))
        assertTrue(menu.contains("maxLines = 2"))
        assertTrue(menu.contains("results.none { it.hasVisualOverflow }"))
        assertTrue(menu.contains("WindowInsets.safeDrawing"))
        assertTrue(menu.contains("ArcMenuGeometry.layout("))
        assertTrue(menu.contains("ArcMenuGeometry.pageIndices(layout.capacity)"))
        assertTrue(menu.contains("page = (page + 1) % pages.size"))
        assertFalse(menu.contains("fontScale *"))
    }

    @Test
    fun `menu keeps callbacks selected states and Japanese feature availability`() {
        val menu = source("ui/overlay/FloatingArcMenu.kt")
        for (callback in listOf("onClick = onHistory", "onClick = onCrop", "onClick = onBattleSelect",
            "onLiveVoiceToggle(!liveVoiceTranslationEnabled)", "onModeChange(TranslationMode.MANUAL)",
            "onModeChange(TranslationMode.SEMI_AUTO)", "onModeChange(TranslationMode.AUTO)", "onClick = onClose")) {
            assertTrue(menu.contains(callback), callback)
        }
        assertTrue(menu.contains("enabled = isJapaneseServer"))
        assertTrue(menu.contains("selected = !battleModeActive && translationMode == TranslationMode.MANUAL"))
        assertTrue(menu.contains("selected = battleModeActive"))
        assertTrue(menu.contains("toggleOn = liveVoiceTranslationEnabled"))
        assertFalse(menu.contains("TranslationTrigger"))
    }

    @Test
    fun `history content follows the game viewport while empty text and touch targets remain Android UI`() {
        val history = source("ui/overlay/HistoryOverlayPanel.kt")
        for (oldScale in listOf("scaledHistoryDp", "scaledHistoryTextPx", "REFERENCE_DENSITY")) {
            assertFalse(history.contains(oldScale))
        }
        assertTrue(history.contains("HistoryContentSizing.forScreen(screenWidthPx, screenHeightPx)"))
        assertTrue(history.contains("setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)"))
        assertTrue(history.contains("val displayedTextSizePx = this.textSize"))
        assertTrue(history.contains("textSize = contentSizing.translationTextSizePx"))
        assertTrue(history.contains("textSizePx = contentSizing.originalTextSizePx"))
        assertTrue(history.contains("contentSizing.spacingPx(HISTORY_LINE_SPACING_EXTRA_BASE_DP)"))
        assertTrue(history.contains("row.contentSizing.spacingPx(12)"))
        assertTrue(history.contains("emptyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, HISTORY_EMPTY_TEXT_SIZE_SP)"))
        assertTrue(history.contains("dp(view.context, HISTORY_SCROLLBAR_TOUCH_SLOP_DP)"))
        assertTrue(history.contains("TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP"))
        assertTrue(history.contains("FgoViewportLayout.viewportForScreen(screenWidthPx, screenHeightPx)"))
        val sizing = source("ui/overlay/HistoryContentSizing.kt")
        assertTrue(sizing.contains("FgoViewportGeometry.referenceScale(width, height)"))
        assertFalse(sizing.contains("displayMetrics"))
        assertFalse(sizing.contains("fontScale"))
        assertFalse(sizing.contains("coerceIn"))
    }

    @Test
    fun `history configuration rebind retains scroll anchor and user interaction guards`() {
        val history = source("ui/overlay/HistoryOverlayPanel.kt")
        assertTrue(history.contains("LocalConfiguration.current"))
        assertTrue(history.contains("configuration.densityDpi, configuration.fontScale"))
        assertTrue(history.contains("configuration.locales.toLanguageTags()"))
        assertTrue(history.contains("state.configuration != configuration"))
        assertTrue(history.contains("state.contentSizing != contentSizing"))
        assertTrue(history.contains("adapter.submitList(entries.map { HistoryRow(it, configuration, contentSizing) })"))
        assertTrue(history.contains("scrollVersion == state.userScrollVersion"))
        assertTrue(history.contains("updateVersion == state.updateVersion"))
        assertTrue(history.contains("entries.indexOfFirst { it.historyId == anchorId }"))
        assertTrue(history.contains("manager.scrollToPositionWithOffset(anchorPosition, anchorOffset)"))
        assertTrue(history.indexOf("val anchorOffset =") < history.indexOf("list.setPadding("))
    }

    @Test
    fun `floating button glyphs fit the dp button rather than enlarge as captions`() {
        val button = source("ui/overlay/FloatingButton.kt")
        assertTrue(button.contains("glyphSizeDp(icon, prominent, contentScale).dp.toPx()"))
        assertTrue(button.contains("baseSizePx.toSp()"))
        assertTrue(button.contains("measurer.measure(label, baseStyle, maxLines = 1, softWrap = false)"))
        assertTrue(button.contains("maxWidth.toPx() / measured.size.width.coerceAtLeast(1)"))
        assertTrue(button.contains("maxHeight.toPx() / measured.size.height.coerceAtLeast(1)"))
        assertFalse(button.contains("scaledGlyphFontSize"))
        assertFalse(button.contains("fontScale"))
        val runner = source("runner/FgoRunnerOverlay.kt")
        assertFalse(runner.contains("currentViewportScale"))
        assertTrue(runner.contains("private fun currentButtonSizePx()"))
    }

    @Test
    fun `large app page labels can wrap without a page redesign`() {
        val settings = source("ui/screen/SettingsScreen.kt").substringAfter("private fun TranslationLanguageSelector(")
            .substringBefore("\n@Composable")
        assertFalse(settings.contains("maxLines = 1"))
        val home = source("ui/screen/HomeScreen.kt").substringAfter("private fun StatusActionCard(")
            .substringBefore("\n@Composable")
        assertTrue(home.contains("Modifier.widthIn(max = 160.dp)"))
        assertTrue(home.contains("Text(actionText)"))
    }

    private fun source(relativePath: String): String = listOf(
        File("src/main/java/com/fgogotran/$relativePath"),
        File("app/src/main/java/com/fgogotran/$relativePath")
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
