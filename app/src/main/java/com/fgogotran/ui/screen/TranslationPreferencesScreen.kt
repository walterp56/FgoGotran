package com.fgogotran.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.localization.LocalizedText as Text
import com.fgogotran.ui.component.FgoSettingsSlider
import com.fgogotran.ui.theme.FgoUiColors
import com.fgogotran.ui.theme.FgoSettingsColors
import com.fgogotran.ui.theme.FgoUiStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationPreferencesScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val targetLanguage by settingsRepository.targetLanguage.collectAsState(
        initial = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    )
    val showOriginalGameText by settingsRepository.showOriginalGameText.collectAsState(initial = false)
    val translationContextEnabled by settingsRepository.translationContextEnabled.collectAsState(
        initial = SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_ENABLED
    )
    val translationContextSceneCount by settingsRepository.translationContextSceneCount.collectAsState(
        initial = SettingsRepository.DEFAULT_TRANSLATION_CONTEXT_SCENE_COUNT
    )
    val translationIncludeRuby by settingsRepository.translationIncludeRuby.collectAsState(
        initial = SettingsRepository.DEFAULT_TRANSLATION_INCLUDE_RUBY
    )
    var playerName by rememberSaveable { mutableStateOf("") }
    var playerGender by rememberSaveable {
        mutableStateOf(SettingsRepository.DEFAULT_PLAYER_GENDER)
    }
    var playerProfileLoaded by rememberSaveable { mutableStateOf(false) }
    var playerNameSaveMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // A configuration change must not replace an unsaved Master-profile draft.
        if (!playerProfileLoaded) {
            playerName = settingsRepository.playerName.first()
            playerGender = settingsRepository.playerGender.first()
            playerProfileLoaded = true
        }
    }

    fun savePlayerProfile() {
        val nameToSave = playerName
        val genderToSave = playerGender
        scope.launch {
            settingsRepository.setPlayerProfile(nameToSave, genderToSave)
            if (playerName == nameToSave && playerGender == genderToSave) {
                playerNameSaveMessage = AppLanguageManager.localizedString(context, R.string.settings_auto_42)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = FgoUiColors.topAppBarColors(),
                title = { Text(stringResource(R.string.settings_auto_27)) },
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
            TranslationPreferenceCard(title = stringResource(R.string.settings_translation_language_display)) {
                TranslationLanguageSelector(
                    selectedLocale = targetLanguage,
                    onSelect = { locale -> scope.launch { settingsRepository.setTargetLanguage(locale) } }
                )
                PreferenceSwitchRow(
                    title = stringResource(R.string.settings_auto_29),
                    subtitle = stringResource(R.string.settings_auto_12),
                    checked = showOriginalGameText,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.setShowOriginalGameText(enabled) }
                    }
                )
            }

            TranslationPreferenceCard(title = stringResource(R.string.settings_translation_processing)) {
                PreferenceSwitchRow(
                    title = stringResource(R.string.settings_auto_11),
                    subtitle = stringResource(R.string.settings_auto_1),
                    checked = translationContextEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.setTranslationContextEnabled(enabled) }
                    }
                )
                TranslationContextSceneCountSelector(
                    sceneCount = translationContextSceneCount,
                    enabled = translationContextEnabled,
                    onSceneCountChange = { count ->
                        scope.launch { settingsRepository.setTranslationContextSceneCount(count) }
                    }
                )
                if (!translationContextEnabled) {
                    Text(
                        stringResource(R.string.settings_translation_context_disabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
                    )
                }
                HorizontalDivider()
                PreferenceSwitchRow(
                    title = stringResource(R.string.settings_auto_7),
                    subtitle = stringResource(R.string.settings_auto_2),
                    checked = translationIncludeRuby,
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.setTranslationIncludeRuby(enabled) }
                    }
                )
                TranslationRubyExample()
            }

            TranslationPreferenceCard(title = stringResource(R.string.settings_master_profile_title)) {
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
                    singleLine = true,
                    enabled = playerProfileLoaded
                )
                if (playerProfileLoaded) {
                    PlayerGenderSelector(
                        selectedGender = playerGender,
                        onSelect = { gender ->
                            playerGender = gender
                            playerNameSaveMessage = ""
                        }
                    )
                }
                if (playerNameSaveMessage.isNotBlank()) {
                    Text(
                        playerNameSaveMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = FgoSettingsColors.value,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
                Button(
                    shape = FgoUiStyle.buttonShape,
                    enabled = playerProfileLoaded,
                    onClick = { savePlayerProfile() },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.settings_auto_51))
                }
            }
        }
    }
}

@Composable
private fun TranslationPreferenceCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = FgoUiStyle.cardBorder,
        colors = FgoUiColors.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
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
internal fun translationLanguageLabel(locale: String): String {
    val normalizedLocale = SettingsRepository.normalizeTargetLanguage(locale)
    val option = targetLanguageOptions.first { it.locale == normalizedLocale }
    return stringResource(option.labelRes)
}

@Composable
private fun TranslationLanguageSelector(
    selectedLocale: String,
    onSelect: (String) -> Unit
) {
    val normalizedLocale = SettingsRepository.normalizeTargetLanguage(selectedLocale)
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.settings_auto_23),
            style = MaterialTheme.typography.bodyMedium,
            color = FgoUiColors.text(darkAlpha = 0.82f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(0.34f)
                    .heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small,
                color = FgoUiColors.optionContainer(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
                border = BorderStroke(
                    1.dp,
                    FgoUiColors.controlOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                )
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.settings_source_japanese),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FgoSettingsColors.value,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Text(
                "→",
                style = MaterialTheme.typography.bodyLarge,
                color = FgoSettingsColors.supporting
            )
            Box(modifier = Modifier.weight(0.66f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { expanded = true },
                    shape = MaterialTheme.shapes.small,
                    color = FgoUiColors.optionContainer(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
                    border = BorderStroke(
                        1.dp,
                        FgoUiColors.controlOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.34f))
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            translationLanguageLabel(normalizedLocale),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("▾", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    targetLanguageOptions.forEach { option ->
                        val selected = option.locale == normalizedLocale
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(option.labelRes),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) FgoUiColors.blueText else MaterialTheme.colorScheme.onSurface
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
                Column(
                    modifier = Modifier
                        .selectableGroup()
                        .verticalScroll(rememberScrollState())
                ) {
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
                fontWeight = FontWeight.Medium,
                color = if (enabled) FgoUiColors.blueText else FgoSettingsColors.value(enabled = false)
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
            FgoSettingsSlider(
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
private fun TranslationRubyExample() {
    val annotationColor = FgoUiColors.blueText
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
            // Preserve the Japanese game text instead of converting it as Chinese UI text.
            androidx.compose.material3.Text(
                text = stringResource(R.string.settings_ruby_example_annotation),
                style = MaterialTheme.typography.labelSmall,
                color = annotationColor
            )
            androidx.compose.material3.Text(
                text = stringResource(R.string.settings_ruby_example_base),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_ruby_example_meaning),
            style = MaterialTheme.typography.bodySmall,
            color = FgoUiColors.text(darkAlpha = 0.82f)
        )
    }
}
