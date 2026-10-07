package com.fgogotran.ui.screen

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.AppThemeMode
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.translation.Translator
import com.fgogotran.ui.component.AppUpdateDialog
import com.fgogotran.ui.component.BackendProviderLabel
import com.fgogotran.ui.component.LanguagePickerDialog
import com.fgogotran.ui.component.appLanguageLabel
import com.fgogotran.ui.component.ThemePickerDialog
import com.fgogotran.ui.component.themeLabelRes
import com.fgogotran.ui.component.openAppDownloadPage
import com.fgogotran.ui.theme.FgoUiColors
import com.fgogotran.ui.theme.FgoUiStyle
import com.fgogotran.ui.theme.LocalFgoDarkTheme
import com.fgogotran.update.AppVersionCheckResult
import com.fgogotran.update.AppVersionInfo
import com.fgogotran.update.AppVersionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val HIDDEN_TOGGLE_TAP_THRESHOLD = 10
private const val HIDDEN_TOGGLE_TAP_WINDOW_MS = 5_000L
private const val FLOATING_BUTTON_SIZE_STEP_DP = 2

private data class OcrEngineOption(
    val engine: String,
    @DrawableRes val iconRes: Int,
    val title: String,
    @StringRes val descriptionRes: Int
)

private val ocrEngineOptions = listOf(
    OcrEngineOption(
        SettingsRepository.OCR_ENGINE_MLKIT,
        R.drawable.ic_mlkit_japanese_mark,
        "ML Kit OCR",
        R.string.settings_auto_3
    ),
    OcrEngineOption(
        SettingsRepository.OCR_ENGINE_PADDLE,
        R.drawable.ic_paddleocr_mark,
        "PaddleOCR",
        R.string.settings_auto_5
    )
)

/**
 * Settings page for user-facing configuration and maintenance actions.
 *
 * The page keeps frequent choices near the top and groups rare maintenance
 * actions together so the screen stays scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    appVersionManager: AppVersionManager,
    themeMode: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    onClearTranslationCache: suspend () -> Int,
    onApiSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    onDiagnosticLog: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val appVersionStatus by appVersionManager.status.collectAsState()
    val translationBackend by settingsRepository.translationBackend.collectAsState(
        initial = SettingsRepository.BACKEND_DEEPSEEK
    )
    val apiModel by settingsRepository.apiModel.collectAsState(
        initial = SettingsRepository.DEFAULT_DEEPSEEK_MODEL
    )
    val aiVoiceEnabled by settingsRepository.aiVoiceEnabled.collectAsState(initial = false)
    val aiVoiceLanguage by settingsRepository.aiVoiceLanguage.collectAsState(
        initial = SettingsRepository.DEFAULT_AI_VOICE_LANGUAGE
    )
    val foregroundTestOverrideEnabled by settingsRepository.foregroundTestOverrideEnabled.collectAsState(
        initial = false
    )
    val liveVoiceTranslationEnabled by settingsRepository.liveVoiceTranslationEnabled.collectAsState(
        initial = false
    )
    val aiVoiceApiHintsEnabled by settingsRepository.aiVoiceApiHintsEnabled.collectAsState(
        initial = SettingsRepository.DEFAULT_AI_VOICE_API_HINTS_ENABLED
    )
    val apiVoiceHintsSupported = Translator.supportsApiVoiceHintsForModel(apiModel)
    val targetLanguage by settingsRepository.targetLanguage.collectAsState(
        initial = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    )
    val currentVersionName = remember(appVersionManager) { appVersionManager.currentVersionName() }

    var playerName by rememberSaveable { mutableStateOf("") }
    var playerGender by rememberSaveable {
        mutableStateOf(SettingsRepository.DEFAULT_PLAYER_GENDER)
    }
    var playerProfileLoaded by rememberSaveable { mutableStateOf(false) }
    var playerNameSaveMessage by remember { mutableStateOf("") }
    var floatingButtonSizeDp by remember {
        mutableStateOf(SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP)
    }
    var showOriginalGameText by remember { mutableStateOf(false) }
    var ocrEngine by remember { mutableStateOf(SettingsRepository.DEFAULT_OCR_ENGINE) }
    var cacheEnabled by remember { mutableStateOf(true) }
    var translationContextEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_ENABLED)
    }
    var translationContextSceneCount by remember {
        mutableStateOf(SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_SCENE_COUNT)
    }
    var translationIncludeRuby by remember {
        mutableStateOf(SettingsRepository.DEFAULT_TRANSLATION_INCLUDE_RUBY)
    }
    var debugLoggingEnabled by remember { mutableStateOf(false) }
    var debugLogTapCount by remember { mutableStateOf(0) }
    var debugLogTapWindowStartedAt by remember { mutableStateOf(0L) }
    var debugLogMessage by remember { mutableStateOf("") }
    var foregroundTestTapCount by remember { mutableStateOf(0) }
    var foregroundTestTapWindowStartedAt by remember { mutableStateOf(0L) }
    var foregroundTestMessage by remember { mutableStateOf("") }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheClearMessage by remember { mutableStateOf("") }
    var pendingUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showOcrPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var appLanguage by remember { mutableStateOf(AppLanguageManager.getLanguage(context)) }

    LaunchedEffect(Unit) {
        // A language refresh must not overwrite a restored, unsaved player-profile edit.
        if (!playerProfileLoaded) {
            playerName = settingsRepository.playerName.first()
            playerGender = settingsRepository.playerGender.first()
            playerProfileLoaded = true
        }
        floatingButtonSizeDp = settingsRepository.getFloatingButtonSizeDp()
        showOriginalGameText = settingsRepository.showOriginalGameText.first()
        ocrEngine = settingsRepository.getOcrEngine()
        cacheEnabled = settingsRepository.cacheEnabled.first()
        translationContextEnabled = settingsRepository.translationContextEnabled.first()
        translationContextSceneCount = settingsRepository.translationContextSceneCount.first()
        translationIncludeRuby = settingsRepository.translationIncludeRuby.first()
        debugLoggingEnabled = settingsRepository.debugLoggingEnabled.first()
    }

    fun savePlayerName() {
        scope.launch {
            settingsRepository.setPlayerProfile(playerName, playerGender)
            playerNameSaveMessage = AppLanguageManager.localizedString(context, R.string.settings_auto_42)
        }
    }

    fun checkAppVersion() {
        scope.launch {
            when (val result = appVersionManager.checkNow()) {
                is AppVersionCheckResult.UpdateAvailable -> pendingUpdate = result.info
                AppVersionCheckResult.UpToDate -> Unit
                is AppVersionCheckResult.Failed -> Unit
            }
        }
    }

    fun clearTranslationCache() {
        clearingCache = true
        cacheClearMessage = ""
        scope.launch {
            runCatching { onClearTranslationCache() }
                .onSuccess { count ->
                    cacheClearMessage = context.getString(R.string.settings_clear_cache_result, count)
                }
                .onFailure {
                    cacheClearMessage = AppLanguageManager.localizedString(context, R.string.settings_auto_17)
                }
            clearingCache = false
        }
    }

    fun handleVersionRowTap() {
        val now = SystemClock.elapsedRealtime()
        val nextCount = if (
            debugLogTapWindowStartedAt == 0L ||
            now - debugLogTapWindowStartedAt > HIDDEN_TOGGLE_TAP_WINDOW_MS
        ) {
            debugLogTapWindowStartedAt = now
            1
        } else {
            debugLogTapCount + 1
        }

        if (nextCount >= HIDDEN_TOGGLE_TAP_THRESHOLD) {
            val enabled = !debugLoggingEnabled
            debugLoggingEnabled = enabled
            debugLogTapCount = 0
            debugLogTapWindowStartedAt = 0L
            debugLogMessage = if (enabled) AppLanguageManager.localizedString(context, R.string.settings_auto_13) else AppLanguageManager.localizedString(context, R.string.settings_auto_14)
            scope.launch { settingsRepository.setDebugLoggingEnabled(enabled) }
        } else {
            debugLogTapCount = nextCount
            debugLogMessage = ""
        }
    }

    fun handleStatusRowTap() {
        val now = SystemClock.elapsedRealtime()
        val nextCount = if (
            foregroundTestTapWindowStartedAt == 0L ||
            now - foregroundTestTapWindowStartedAt > HIDDEN_TOGGLE_TAP_WINDOW_MS
        ) {
            foregroundTestTapWindowStartedAt = now
            1
        } else {
            foregroundTestTapCount + 1
        }

        if (nextCount >= HIDDEN_TOGGLE_TAP_THRESHOLD) {
            val enabled = !foregroundTestOverrideEnabled
            foregroundTestTapCount = 0
            foregroundTestTapWindowStartedAt = 0L
            foregroundTestMessage = if (enabled) {
                AppLanguageManager.localizedString(context, R.string.settings_auto_15)
            } else {
                AppLanguageManager.localizedString(context, R.string.settings_auto_10)
            }
            scope.launch { settingsRepository.setForegroundTestOverrideEnabled(enabled) }
        } else {
            foregroundTestTapCount = nextCount
            foregroundTestMessage = ""
        }
    }

    fun openDownloadPage() {
        openAppDownloadPage(context)
    }

    pendingUpdate?.let { update ->
        AppUpdateDialog(
            currentVersionName = currentVersionName,
            update = update,
            onDismiss = { pendingUpdate = null },
            onUpdateNow = {
                pendingUpdate = null
                openDownloadPage()
            }
        )
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            selectedLanguage = appLanguage,
            onDismiss = { showLanguagePicker = false },
            onSelect = { language ->
                showLanguagePicker = false
                if (language != appLanguage) {
                    appLanguage = language
                    AppLanguageManager.setLanguage(context, language)
                    AppLanguageManager.recreateActivity(context)
                    FgoRunnerService.refreshOverlayLanguage()
                }
            }
        )
    }

    if (showThemePicker) {
        ThemePickerDialog(
            selectedMode = themeMode,
            onDismiss = { showThemePicker = false },
            onSelect = { mode ->
                showThemePicker = false
                if (mode != themeMode) onThemeChange(mode)
            }
        )
    }

    if (showOcrPicker) {
        AlertDialog(
            onDismissRequest = { showOcrPicker = false },
            title = { Text(stringResource(R.string.settings_auto_18)) },
            text = {
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ocrEngineOptions.forEach { option ->
                        val selected = option.engine == ocrEngine
                        OcrEngineRow(
                            option = option,
                            selected = selected,
                            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
                                showOcrPicker = false
                                if (!selected) {
                                    ocrEngine = option.engine
                                    scope.launch { settingsRepository.setOcrEngine(option.engine) }
                                }
                            }
                        ) {
                            RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    colors = FgoUiColors.textButtonColors(),
                    onClick = { showOcrPicker = false }
                ) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = FgoUiColors.topAppBarColors(),
                title = { Text(stringResource(R.string.settings_auto_48)) },
                navigationIcon = {
                    TextButton(colors = FgoUiColors.textButtonColors(), onClick = onBack) {
                        Text(stringResource(R.string.settings_auto_49), color = FgoUiColors.blueText)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard(
                iconRes = R.drawable.ic_translate,
                title = stringResource(R.string.settings_auto_24),
                body = ""
            ) {
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_43),
                    valueContent = {
                        BackendProviderLabel(
                            backend = translationBackend,
                            label = SettingsRepository.backendDisplayName(translationBackend),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            horizontalArrangement = Arrangement.End
                        )
                    }
                )
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_50),
                    value = apiModel.ifBlank { SettingsRepository.defaultApiModel(translationBackend) }
                )
                Button(
                    shape = FgoUiStyle.buttonShape,
                    onClick = onApiSettings,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.settings_auto_26))
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_document_scanner,
                title = stringResource(R.string.settings_auto_18),
                body = stringResource(R.string.settings_auto_6)
            ) {
                val selectedOption = ocrEngineOptions.first {
                    it.engine == SettingsRepository.normalizeOcrEngine(ocrEngine)
                }
                OcrEngineRow(
                    option = selectedOption,
                    modifier = Modifier.clickable(role = Role.Button) { showOcrPicker = true }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
                    )
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_tune,
                title = stringResource(R.string.settings_auto_27),
                body = ""
            ) {
                TranslationLanguageSelector(
                    selectedLocale = targetLanguage,
                    onSelect = { locale ->
                        scope.launch {
                            settingsRepository.setTargetLanguage(locale)
                        }
                    }
                )
                PreferenceSwitchRow(
                    title = stringResource(R.string.settings_auto_29),
                    subtitle = stringResource(R.string.settings_auto_12),
                    checked = showOriginalGameText,
                    onCheckedChange = {
                        showOriginalGameText = it
                        scope.launch { settingsRepository.setShowOriginalGameText(it) }
                    }
                )
                HorizontalDivider()
                PreferenceSwitchRow(
                    title = stringResource(R.string.settings_auto_11),
                    subtitle = stringResource(R.string.settings_auto_1),
                    checked = translationContextEnabled,
                    onCheckedChange = { enabled ->
                        translationContextEnabled = enabled
                        scope.launch {
                            settingsRepository.setTranslationContextEnabled(enabled)
                        }
                    }
                )
                TranslationContextSceneCountSelector(
                    sceneCount = translationContextSceneCount,
                    enabled = translationContextEnabled,
                    onSceneCountChange = { sceneCount ->
                        translationContextSceneCount = sceneCount
                        scope.launch {
                            settingsRepository.setTranslationContextSceneCount(sceneCount)
                        }
                    }
                )
                HorizontalDivider()
                PreferenceSwitchRow(
                    title = stringResource(R.string.settings_auto_7),
                    subtitle = stringResource(R.string.settings_auto_2),
                    checked = translationIncludeRuby,
                    onCheckedChange = { enabled ->
                        translationIncludeRuby = enabled
                        scope.launch { settingsRepository.setTranslationIncludeRuby(enabled) }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_ruby_example_label),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // These are Japanese game text, not Chinese UI labels.
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.settings_ruby_example_annotation),
                            style = MaterialTheme.typography.labelSmall,
                            color = FgoUiColors.blueText
                        )
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.settings_ruby_example_base),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_master_profile_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    colors = FgoUiColors.outlinedTextFieldColors(),
                    value = playerName,
                    onValueChange = {
                        playerName = it
                        playerNameSaveMessage = ""
                    },
                    label = { Text(stringResource(R.string.settings_auto_28)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.settings_auto_19)) },
                    singleLine = true
                )
                PlayerGenderSelector(
                    selectedGender = playerGender,
                    onSelect = { gender ->
                        playerGender = gender
                        playerNameSaveMessage = ""
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (playerNameSaveMessage.isNotBlank()) {
                        Text(
                            playerNameSaveMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = FgoUiColors.blueText
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Button(shape = FgoUiStyle.buttonShape, onClick = { savePlayerName() }) {
                        Text(stringResource(R.string.settings_auto_51))
                    }
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_voice,
                title = stringResource(R.string.settings_auto_30),
                body = ""
            ) {
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_20),
                    value = if (liveVoiceTranslationEnabled) stringResource(R.string.settings_auto_44) else stringResource(R.string.settings_auto_45),
                    valueColor = if (liveVoiceTranslationEnabled) FgoUiColors.success else FgoUiColors.warning
                )
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_31),
                    value = if (aiVoiceEnabled) stringResource(R.string.settings_auto_44) else stringResource(R.string.settings_auto_45),
                    valueColor = if (aiVoiceEnabled) FgoUiColors.success else FgoUiColors.warning
                )
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_32),
                    value = when (
                        SettingsRepository.resolveReadTextLanguage(
                            targetLanguage,
                            aiVoiceLanguage
                        )
                    ) {
                        SettingsRepository.AI_VOICE_LANGUAGE_EN_TRANSLATION ->
                            stringResource(R.string.voice_read_english)
                        else -> stringResource(R.string.voice_read_chinese)
                    }
                )
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_33),
                    value = when {
                        !apiVoiceHintsSupported -> stringResource(R.string.settings_auto_8)
                        aiVoiceApiHintsEnabled -> stringResource(R.string.settings_auto_53)
                        else -> stringResource(R.string.settings_auto_54)
                    },
                    valueColor = if (apiVoiceHintsSupported && aiVoiceApiHintsEnabled) {
                        FgoUiColors.success
                    } else {
                        FgoUiColors.warning
                    }
                )
                Button(
                    shape = FgoUiStyle.buttonShape,
                    onClick = onVoiceSettings,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.settings_auto_21))
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_palette,
                title = stringResource(R.string.app_appearance_title),
                body = ""
            ) {
                SettingsInfoRow(
                    label = stringResource(R.string.ui_language_title),
                    valueContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Keep each language name in its own script, as in the picker.
                            androidx.compose.material3.Text(
                                text = appLanguageLabel(appLanguage, stringResource(R.string.ui_language_system)),
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FgoUiColors.text(darkAlpha = 0.82f),
                                textAlign = TextAlign.End
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_chevron_right),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
                            )
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { showLanguagePicker = true }
                )
                HorizontalDivider()
                SettingsInfoRow(
                    label = stringResource(R.string.app_theme_title),
                    valueContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(themeLabelRes(themeMode)),
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyMedium,
                                color = FgoUiColors.text(darkAlpha = 0.82f),
                                textAlign = TextAlign.End
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_settings_chevron_right),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
                            )
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { showThemePicker = true }
                )
                HorizontalDivider()
                SettingsInfoRow(
                    label = stringResource(R.string.settings_floating_button_size),
                    valueContent = {
                        Text(
                            text = floatingButtonSizeLabel(floatingButtonSizeDp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (floatingButtonSizeDp == SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                FgoUiColors.blueText
                            },
                            textAlign = TextAlign.End
                        )
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_auto_62),
                        style = MaterialTheme.typography.bodySmall,
                        color = FgoUiColors.blueText
                    )
                    Slider(
                        value = floatingButtonSizeDp.toFloat(),
                        onValueChange = { rawValue ->
                            val roundedSize = roundFloatingButtonSize(rawValue)
                            if (roundedSize != floatingButtonSizeDp) {
                                floatingButtonSizeDp = roundedSize
                                scope.launch {
                                    settingsRepository.setFloatingButtonSizeDp(roundedSize)
                                }
                            }
                        },
                        valueRange = SettingsRepository.MIN_FLOATING_BUTTON_SIZE_DP.toFloat()..
                            SettingsRepository.MAX_FLOATING_BUTTON_SIZE_DP.toFloat(),
                        steps = floatingButtonSizeSteps(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        stringResource(R.string.settings_auto_63),
                        style = MaterialTheme.typography.bodySmall,
                        color = FgoUiColors.blueText
                    )
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_build,
                title = stringResource(R.string.settings_auto_56),
                body = ""
            ) {
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_39),
                    value = currentVersionName,
                    modifier = Modifier.clickable { handleVersionRowTap() }
                )
                HiddenToggleNotice(
                    text = debugLogMessage,
                    enabled = debugLoggingEnabled
                )
                SettingsInfoRow(
                    label = stringResource(R.string.settings_auto_57),
                    value = appVersionStatus.message.ifBlank { stringResource(R.string.settings_auto_16) },
                    modifier = Modifier.clickable { handleStatusRowTap() }
                )
                HiddenToggleNotice(
                    text = foregroundTestMessage,
                    enabled = foregroundTestOverrideEnabled
                )
                OutlinedButton(
                    colors = FgoUiColors.outlinedButtonColors(),
                    shape = FgoUiStyle.buttonShape,
                    onClick = { checkAppVersion() },
                    enabled = !appVersionStatus.isChecking,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (appVersionStatus.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (appVersionStatus.isChecking) stringResource(R.string.settings_auto_47) else stringResource(R.string.settings_auto_40))
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_auto_35),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.settings_auto_4),
                    style = MaterialTheme.typography.bodySmall,
                    color = FgoUiColors.text(darkAlpha = 0.65f, secondary = true)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_auto_36),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FgoUiColors.text(darkAlpha = 0.82f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = cacheEnabled,
                        onCheckedChange = {
                            cacheEnabled = it
                            scope.launch { settingsRepository.setCacheEnabled(it) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cacheClearMessage.isNotBlank()) {
                        Text(
                            cacheClearMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    OutlinedButton(
                        colors = FgoUiColors.outlinedButtonColors(),
                        shape = FgoUiStyle.buttonShape,
                        onClick = { clearTranslationCache() },
                        enabled = !clearingCache
                    ) {
                        if (clearingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (clearingCache) stringResource(R.string.settings_auto_46) else stringResource(R.string.settings_auto_37))
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_auto_38),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.settings_auto_9),
                    style = MaterialTheme.typography.bodySmall,
                    color = FgoUiColors.text(darkAlpha = 0.65f, secondary = true)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(shape = FgoUiStyle.buttonShape, onClick = onDiagnosticLog) {
                        Text(stringResource(R.string.settings_auto_22))
                    }
                }
            }
        }
    }
}

private val targetLanguageOptions = listOf(
    TargetLanguageOption(
        locale = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED,
        labelRes = R.string.settings_target_simplified
    ),
    TargetLanguageOption(
        locale = SettingsRepository.TARGET_LANGUAGE_TRADITIONAL,
        labelRes = R.string.settings_target_traditional
    ),
    TargetLanguageOption(
        locale = SettingsRepository.TARGET_LANGUAGE_ENGLISH,
        labelRes = R.string.settings_target_english
    )
)

private data class TargetLanguageOption(
    val locale: String,
    @StringRes val labelRes: Int
)

private val playerGenderOptions = listOf(
    PlayerGenderOption(
        gender = SettingsRepository.PLAYER_GENDER_MALE,
        labelRes = R.string.settings_gender_male
    ),
    PlayerGenderOption(
        gender = SettingsRepository.PLAYER_GENDER_FEMALE,
        labelRes = R.string.settings_gender_female
    )
)

private data class PlayerGenderOption(
    val gender: String,
    @StringRes val labelRes: Int
)

@Composable
private fun TranslationLanguageSelector(
    selectedLocale: String,
    onSelect: (String) -> Unit
) {
    val normalizedLocale = SettingsRepository.normalizeTargetLanguage(selectedLocale)
    val selectedOption = targetLanguageOptions.firstOrNull { it.locale == normalizedLocale }
        ?: targetLanguageOptions.first()
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.settings_auto_23),
            style = MaterialTheme.typography.bodyMedium,
            color = FgoUiColors.text(darkAlpha = 0.72f, secondary = true),
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.weight(0.34f),
                shape = MaterialTheme.shapes.small,
                color = FgoUiColors.optionContainer(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
                border = BorderStroke(
                    width = 1.dp,
                    color = FgoUiColors.controlOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.settings_source_japanese),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.weight(0.66f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = FgoUiColors.optionContainer(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
                    border = BorderStroke(
                        width = 1.dp,
                        color = FgoUiColors.controlOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.34f))
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(selectedOption.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "▾",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    targetLanguageOptions.forEach { option ->
                        val selected = option.locale == normalizedLocale
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(option.labelRes),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) {
                                        FgoUiColors.blueText
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(option.locale)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerGenderSelector(
    selectedGender: String,
    onSelect: (String) -> Unit
) {
    val normalizedGender = SettingsRepository.normalizePlayerGender(selectedGender)
    val selectedOption = playerGenderOptions.first { it.gender == normalizedGender }
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { showPicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.settings_auto_41),
            style = MaterialTheme.typography.bodyMedium,
            color = FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Surface(
                modifier = Modifier.widthIn(min = 96.dp).heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
                color = FgoUiColors.optionContainer(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
                border = BorderStroke(
                    width = 1.dp,
                    color = FgoUiColors.controlOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.34f))
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = stringResource(selectedOption.labelRes),
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.settings_auto_41)) },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    playerGenderOptions.forEach { option ->
                        val selected = option.gender == normalizedGender
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    showPicker = false
                                    onSelect(option.gender)
                                }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                text = stringResource(option.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    colors = FgoUiColors.textButtonColors(),
                    onClick = { showPicker = false }
                ) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }
}

@Composable
private fun OcrEngineRow(
    option: OcrEngineOption,
    modifier: Modifier,
    selected: Boolean = false,
    trailingContent: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            FgoUiColors.optionContainer(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f), selected = true)
        } else {
            FgoUiColors.optionContainer(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f))
        },
        border = if (LocalFgoDarkTheme.current) null else BorderStroke(
            1.dp, FgoUiColors.controlOutline(MaterialTheme.colorScheme.outline, selected = selected)
        )
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(id = option.iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(option.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = FgoUiColors.text(darkAlpha = 0.62f, secondary = true)
                )
            }
            trailingContent()
        }
    }
}

private fun roundFloatingButtonSize(rawValue: Float): Int {
    val minSize = SettingsRepository.MIN_FLOATING_BUTTON_SIZE_DP
    val roundedSize = minSize +
        ((rawValue - minSize) / FLOATING_BUTTON_SIZE_STEP_DP).roundToInt() *
            FLOATING_BUTTON_SIZE_STEP_DP
    return SettingsRepository.normalizeFloatingButtonSizeDp(roundedSize)
}

private fun floatingButtonSizeSteps(): Int {
    val intervals = (
        SettingsRepository.MAX_FLOATING_BUTTON_SIZE_DP -
            SettingsRepository.MIN_FLOATING_BUTTON_SIZE_DP
        ) / FLOATING_BUTTON_SIZE_STEP_DP
    return (intervals - 1).coerceAtLeast(0)
}

@Composable
private fun floatingButtonSizeLabel(sizeDp: Int): String {
    val safeSize = SettingsRepository.normalizeFloatingButtonSizeDp(sizeDp)
    return when {
        safeSize < SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP -> stringResource(R.string.settings_auto_58)
        safeSize == SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP -> stringResource(R.string.settings_auto_59)
        safeSize < SettingsRepository.MAX_FLOATING_BUTTON_SIZE_DP -> stringResource(R.string.settings_auto_60)
        else -> stringResource(R.string.settings_auto_61)
    }
}

@Composable
private fun TranslationContextSceneCountSelector(
    sceneCount: Int,
    enabled: Boolean,
    onSceneCountChange: (Int) -> Unit
) {
    val safeCount = SettingsRepository.normalizeTranslationContextSceneCount(sceneCount)
    val contentColor = FgoUiColors.text(
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (enabled) 0.82f else 0.38f
        ),
        enabled = enabled
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.settings_auto_25),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
            Text(
                stringResource(R.string.settings_context_scenes, safeCount),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) FgoUiColors.blueText else contentColor
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                SettingsRepository.MIN_TRANSLATION_CONTEXT_SCENE_COUNT.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            Slider(
                value = safeCount.toFloat(),
                onValueChange = { rawValue ->
                    val normalizedCount = SettingsRepository.normalizeTranslationContextSceneCount(
                        rawValue.roundToInt()
                    )
                    if (normalizedCount != safeCount) {
                        onSceneCountChange(normalizedCount)
                    }
                },
                enabled = enabled,
                valueRange = SettingsRepository.MIN_TRANSLATION_CONTEXT_SCENE_COUNT.toFloat()..
                    SettingsRepository.MAX_TRANSLATION_CONTEXT_SCENE_COUNT.toFloat(),
                steps = SettingsRepository.MAX_TRANSLATION_CONTEXT_SCENE_COUNT -
                    SettingsRepository.MIN_TRANSLATION_CONTEXT_SCENE_COUNT - 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                SettingsRepository.MAX_TRANSLATION_CONTEXT_SCENE_COUNT.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = FgoUiColors.text(darkAlpha = 0.82f)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsCard(
    @DrawableRes iconRes: Int,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = FgoUiStyle.cardBorder,
        colors = FgoUiColors.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconBadge(iconRes)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = FgoUiColors.text(darkAlpha = 0.65f, secondary = true)
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsIconBadge(@DrawableRes iconRes: Int) {
    Surface(
        modifier = Modifier.size(36.dp),
        color = FgoUiColors.accentContainer(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
        shape = MaterialTheme.shapes.small
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = FgoUiColors.accentContent(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun HiddenToggleNotice(
    text: String,
    enabled: Boolean
) {
    if (text.isBlank()) return

    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}


@Composable
private fun SettingsInfoRow(
    label: String,
    value: String = "",
    valueContent: (@Composable () -> Unit)? = null,
    valueColor: Color = FgoUiColors.text(darkAlpha = 0.82f),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.TopEnd
        ) {
            if (valueContent != null) {
                valueContent()
            } else {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}


