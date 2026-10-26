package com.fgogotran.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Layout/callback wiring only. Actual scroll reachability and wrapping need native device QA. */
class SettingsDialogOverflowWorkflowTest {
    @Test
    fun `language options scroll independently of the fixed title and close action`() {
        val picker = source("ui/component/LanguagePickerDialog.kt")
        assertScrollableBody(picker)
        assertTrue(picker.substringBefore("text = {").contains("R.string.ui_language_title"))
        assertTrue(picker.substringAfter("confirmButton = {").contains("onClick = onDismiss"))
        assertTrue(picker.substringAfter("confirmButton = {").contains("R.string.ui_language_close"))
        assertFalse(dialogBody(picker).contains("R.string.ui_language_close"))
        assertTrue(picker.contains(".heightIn(min = 48.dp)"))
        assertTrue(picker.contains(".padding(horizontal = 4.dp, vertical = 12.dp)"))
        assertTrue(picker.contains("onSelect(option.language)"))
        assertOrdered(picker.substringAfter("val options = listOf("),
            "AppLanguageManager.LANGUAGE_SYSTEM", "AppLanguageManager.LANGUAGE_ENGLISH",
            "AppLanguageManager.LANGUAGE_TRADITIONAL", "AppLanguageManager.LANGUAGE_SIMPLIFIED")
    }

    @Test
    fun `theme options scroll without moving their title or close action`() {
        val picker = source("ui/component/ThemePickerDialog.kt")
        assertScrollableBody(picker)
        assertTrue(picker.substringBefore("text = {").contains("R.string.app_theme_title"))
        assertTrue(picker.substringAfter("confirmButton = {").contains("onClick = onDismiss"))
        assertFalse(dialogBody(picker).contains("R.string.app_theme_close"))
        assertTrue(picker.contains("AppThemeMode.entries.forEach"))
        assertTrue(picker.contains("onSelect(mode)"))
        assertTrue(picker.contains("R.string.app_theme_system_description"))
        assertTrue(picker.contains(".heightIn(min = 48.dp)"))
        assertTrue(picker.contains(".padding(horizontal = 4.dp, vertical = 10.dp)"))
    }

    @Test
    fun `OCR descriptions scroll while selection persistence and cancel remain unchanged`() {
        val settings = source("ui/screen/SettingsScreen.kt")
        val picker = settings.substringAfter("if (showOcrPicker) {").substringBefore("\n    Scaffold(")
        assertScrollableBody(picker)
        assertTrue(picker.substringBefore("text = {").contains("R.string.settings_auto_18"))
        assertTrue(dialogBody(picker).contains("settingsRepository.setOcrEngine(option.engine)"))
        assertTrue(dialogBody(picker).contains("showOcrPicker = false"))
        assertTrue(picker.substringAfter("confirmButton = {").contains("showOcrPicker = false"))
        assertFalse(dialogBody(picker).contains("R.string.home_cancel"))
        assertTrue(dialogBody(picker).contains("Arrangement.spacedBy(10.dp)"))
        assertOrdered(settings.substringAfter("private val ocrEngineOptions = listOf("),
            "SettingsRepository.OCR_ENGINE_MLKIT", "SettingsRepository.OCR_ENGINE_PADDLE")
        val row = function(settings, "OcrEngineRow")
        assertTrue(row.contains("stringResource(option.descriptionRes)"))
        assertTrue(row.contains(".heightIn(min = 48.dp)"))
    }

    @Test
    fun `gender picker has its own scroll state and keeps both selection callbacks`() {
        val settings = source("ui/screen/TranslationPreferencesScreen.kt")
        val picker = function(settings, "PlayerGenderSelector")
        assertScrollableBody(picker)
        assertTrue(dialogBody(picker).contains("showPicker = false"))
        assertTrue(dialogBody(picker).contains("onSelect(option.gender)"))
        assertTrue(picker.substringAfter("confirmButton = {").contains("showPicker = false"))
        assertFalse(dialogBody(picker).contains("R.string.home_cancel"))
        assertTrue(dialogBody(picker).contains(".heightIn(min = 48.dp)"))
        assertOrdered(settings.substringAfter("private val playerGenderOptions = listOf("),
            "SettingsRepository.PLAYER_GENDER_MALE", "SettingsRepository.PLAYER_GENDER_FEMALE")
    }

    @Test
    fun `server options use remaining height without stretching a normal dialog`() {
        val picker = function(source("ui/screen/HomeScreen.kt"), "ServerChoiceDialog")
        assertTrue(picker.contains(".weight(1f, fill = false)"))
        assertTrue(picker.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(picker.indexOf("R.string.home_server_title") < picker.indexOf(".verticalScroll("))
        assertTrue(picker.contains("Dialog(onDismissRequest = onDismiss)"))
        assertTrue(picker.contains("onClick = { onSelect(server) }"))
        assertTrue(picker.contains("Arrangement.spacedBy(10.dp)"))
        assertTrue(picker.contains("Arrangement.spacedBy(8.dp)"))
        assertFalse(picker.contains("confirmButton"))
        assertFalse(picker.contains(".heightIn(max ="))
        assertOrdered(picker.substringAfter("val serverOptions = listOf("),
            "SettingsRepository.GAME_SERVER_JP", "SettingsRepository.GAME_SERVER_CN",
            "SettingsRepository.GAME_SERVER_TW", "SettingsRepository.GAME_SERVER_EN")
    }

    @Test
    fun `manual and automatic update actions wrap without reordering callbacks`() {
        val updates = source("ui/component/AppUpdateDialog.kt")
        val manual = function(updates, "AppUpdateDialog").substringAfter("confirmButton = {")
        val auto = function(updates, "AutoAppUpdateDialog").substringAfter("confirmButton = {")
        for (actions in listOf(manual, auto)) {
            assertTrue(actions.contains("FlowRow("))
            assertTrue(actions.contains("Arrangement.spacedBy(4.dp, Alignment.End)"))
            assertTrue(actions.contains("verticalArrangement = Arrangement.spacedBy(4.dp)"))
            assertFalse(actions.contains("maxLines ="))
            assertFalse(actions.contains("maxItemsInEachRow ="))
        }
        assertOrdered(manual, "onClick = onDismiss", "onClick = onUpdateNow")
        assertOrdered(auto, "onClick = onIgnoreVersion", "onClick = onDismiss", "onClick = onUpdateNow")
        val body = function(updates, "AppUpdateDialogBody")
        assertTrue(body.contains(".heightIn(max = 360.dp)"))
        assertTrue(body.contains(".verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `Azure status and buttons wrap with original alignment gaps and actions`() {
        val actions = source("ui/screen/VoiceSettingsScreen.kt").substringAfter("FlowRow(")
            .substringBefore("private data class AzureSpeechRegionOption")
        assertTrue(actions.contains("Arrangement.spacedBy(8.dp, Alignment.End)"))
        assertTrue(actions.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
        // 4dp status padding plus the 8dp flow gap preserves the original 12dp gap.
        assertTrue(actions.contains("Modifier.padding(end = 4.dp).align(Alignment.CenterVertically)"))
        assertEquals(2, Regex("modifier = Modifier.align\\(Alignment.CenterVertically\\)").findAll(actions).count())
        assertFalse(actions.contains("Spacer("))
        assertFalse(actions.contains("maxLines ="))
        assertOrdered(actions, "azureSpeechSaveMessage", "onClick = { testAzureVoice() }",
            "onClick = { saveAzureSpeechSettings() }")
        assertTrue(actions.contains("enabled = !azureSpeechTesting"))
    }

    @Test
    fun `overflow handling does not force font density resolution or text truncation`() {
        val pickers = listOf(
            source("ui/component/LanguagePickerDialog.kt"),
            source("ui/component/ThemePickerDialog.kt"),
            function(source("ui/screen/HomeScreen.kt"), "ServerChoiceDialog"),
            source("ui/screen/SettingsScreen.kt").substringAfter("if (showOcrPicker) {").substringBefore("\n    Scaffold("),
            function(source("ui/screen/TranslationPreferencesScreen.kt"), "PlayerGenderSelector")
        )
        for (picker in pickers) {
            for (override in listOf("fontSize =", "fontScale", "LocalDensity", "2560", "1440", "maxLines =")) {
                assertFalse(picker.contains(override), override)
            }
        }
    }

    private fun assertScrollableBody(picker: String) {
        val body = dialogBody(picker)
        assertEquals(1, Regex("\\.verticalScroll\\(rememberScrollState\\(\\)\\)").findAll(body).count())
        assertTrue(body.contains(".selectableGroup()"))
        assertFalse(body.contains(".heightIn(max ="))
    }

    private fun dialogBody(picker: String): String =
        picker.substringAfter("text = {").substringBefore("confirmButton = {")

    private fun assertOrdered(source: String, vararg tokens: String) {
        var previous = -1
        for (token in tokens) {
            val index = source.indexOf(token)
            assertTrue(index > previous, "Missing or reordered token: $token")
            previous = index
        }
    }

    private fun function(source: String, name: String): String = source.substringAfter("fun $name(")
        .substringBefore("\n@Composable").substringBefore("\n@OptIn")

    private fun source(path: String): String = listOf(
        File("src/main/java/com/fgogotran/$path"), File("app/src/main/java/com/fgogotran/$path")
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
