package com.fgogotran.ui.screen

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.translation.Translator
import com.fgogotran.translation.VoiceLineHint
import com.fgogotran.voice.AiVoiceService
import com.fgogotran.voice.AzureVoiceTestResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    settingsRepository: SettingsRepository,
    translator: Translator,
    aiVoiceService: AiVoiceService,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val apiModel by settingsRepository.apiModel.collectAsState(
        initial = SettingsRepository.DEFAULT_DEEPSEEK_MODEL
    )
    val playerGender by settingsRepository.playerGender.collectAsState(
        initial = SettingsRepository.DEFAULT_PLAYER_GENDER
    )
    val apiVoiceHintsSupported = Translator.supportsApiVoiceHintsForModel(apiModel)

    var aiVoiceEnabled by remember { mutableStateOf(false) }
    var aiVoiceApiHintsEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_API_HINTS_ENABLED)
    }
    var aiVoiceSpeedPercent by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_SPEED_PERCENT)
    }
    var aiVoiceVolumePercent by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_VOLUME_PERCENT)
    }
    var aiVoiceNamedDialogueEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_NAMED_DIALOGUE_ENABLED)
    }
    var aiVoiceNoSpeakerDialogueEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_NO_SPEAKER_DIALOGUE_ENABLED)
    }
    var aiVoiceChoiceTextEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_CHOICE_TEXT_ENABLED)
    }
    var azureSpeechKey by remember { mutableStateOf("") }
    var azureSpeechRegion by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AZURE_SPEECH_REGION)
    }
    var azureSpeechEndpoint by remember { mutableStateOf("") }
    var liveVoiceTranslationEnabled by remember { mutableStateOf(false) }
    var liveVoiceSubtitleFontSizeSp by remember {
        mutableIntStateOf(SettingsRepository.DEFAULT_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP)
    }
    var subtitlePositionResetMessage by remember { mutableStateOf("") }
    var azureSpeechSaveMessage by remember { mutableStateOf("") }
    var azureSpeechTestMessage by remember { mutableStateOf("") }
    var azureSpeechTestIsError by remember { mutableStateOf(false) }
    var azureSpeechTesting by remember { mutableStateOf(false) }
    val effectiveApiVoiceHintsEnabled = aiVoiceApiHintsEnabled && apiVoiceHintsSupported

    LaunchedEffect(Unit) {
        aiVoiceEnabled = settingsRepository.aiVoiceEnabled.first()
        aiVoiceApiHintsEnabled = settingsRepository.aiVoiceApiHintsEnabled.first()
        aiVoiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        aiVoiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        aiVoiceNamedDialogueEnabled = settingsRepository.aiVoiceNamedDialogueEnabled.first()
        aiVoiceNoSpeakerDialogueEnabled = settingsRepository.aiVoiceNoSpeakerDialogueEnabled.first()
        aiVoiceChoiceTextEnabled = settingsRepository.aiVoiceChoiceTextEnabled.first()
        azureSpeechKey = settingsRepository.azureSpeechKey.first()
        azureSpeechRegion = settingsRepository.azureSpeechRegion.first()
        azureSpeechEndpoint = settingsRepository.azureSpeechEndpoint.first()
        liveVoiceSubtitleFontSizeSp = settingsRepository.liveVoiceSubtitleFontSizeSp.first()
        settingsRepository.liveVoiceTranslationEnabled.collect { enabled ->
            liveVoiceTranslationEnabled = enabled
        }
    }

    fun saveAzureSpeechSettings() {
        scope.launch {
            settingsRepository.saveAzureSpeechSettings(
                azureSpeechKey,
                azureSpeechRegion,
                azureSpeechEndpoint
            )
            azureSpeechSaveMessage = if (
                liveVoiceTranslationEnabled &&
                azureSpeechRegion == SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3 &&
                azureSpeechEndpoint.isBlank()
            ) {
                AppLanguageManager.localizedString(context, R.string.voice_auto_11)
            } else {
                AppLanguageManager.localizedString(context, R.string.voice_auto_47)
            }
        }
    }

    fun testAzureVoice() {
        if (azureSpeechTesting) return
        scope.launch {
            azureSpeechTesting = true
            azureSpeechSaveMessage = ""
            azureSpeechTestIsError = false
            azureSpeechTestMessage = AppLanguageManager.localizedString(context, R.string.voice_auto_19)
            try {
                if (azureSpeechKey.trim().isBlank()) {
                    throw IllegalArgumentException("Azure Speech key is blank")
                }
                settingsRepository.saveAzureSpeechSettings(
                    azureSpeechKey,
                    azureSpeechRegion,
                    azureSpeechEndpoint
                )
                val sample = azureVoiceTestSample(context, settingsRepository.targetChineseLocale.first())
                var voiceHint: VoiceLineHint? = null
                var voiceHintError: Throwable? = null
                if (effectiveApiVoiceHintsEnabled) {
                    runCatching {
                        translator.testVoiceHint(sample.speakerName, sample.dialogue)
                    }.onSuccess { hint ->
                        voiceHint = hint
                    }.onFailure { error ->
                        voiceHintError = error
                    }
                }
                val result = aiVoiceService.playAzureVoiceTest(
                    speakerName = sample.speakerName,
                    dialogue = sample.dialogue,
                    voiceHint = voiceHint
                )
                azureSpeechTestMessage = voiceTestSuccessMessage(
                    context = context,
                    result = result,
                    apiHintsEnabled = effectiveApiVoiceHintsEnabled,
                    apiHintError = voiceHintError
                )
            } catch (e: Throwable) {
                azureSpeechTestIsError = true
                azureSpeechTestMessage = voiceTestErrorMessage(context, e)
            } finally {
                azureSpeechTesting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_auto_39)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.voice_auto_48), color = MaterialTheme.colorScheme.primary)
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
            VoiceSettingsCard(
                title = stringResource(R.string.voice_auto_33),
                body = stringResource(R.string.voice_auto_7)
            ) {
                VoiceSwitchRow(
                    title = stringResource(R.string.voice_auto_24),
                    body = "",
                    checked = liveVoiceTranslationEnabled,
                    onCheckedChange = {
                        liveVoiceTranslationEnabled = it
                        azureSpeechSaveMessage = ""
                        scope.launch { settingsRepository.setLiveVoiceTranslationEnabled(it) }
                    }
                )
                LiveVoiceSubtitleSizeSlider(
                    fontSizeSp = liveVoiceSubtitleFontSizeSp,
                    onFontSizeChange = { fontSizeSp ->
                        val normalizedSize =
                            SettingsRepository.normalizeLiveVoiceSubtitleFontSizeSp(fontSizeSp)
                        if (normalizedSize != liveVoiceSubtitleFontSizeSp) {
                            liveVoiceSubtitleFontSizeSp = normalizedSize
                            scope.launch {
                                settingsRepository.setLiveVoiceSubtitleFontSizeSp(normalizedSize)
                            }
                        }
                    }
                )
                LiveVoiceSubtitlePreview(fontSizeSp = liveVoiceSubtitleFontSizeSp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (subtitlePositionResetMessage.isNotBlank()) {
                        Text(
                            subtitlePositionResetMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            subtitlePositionResetMessage = ""
                            scope.launch {
                                settingsRepository.resetLiveVoiceSubtitlePosition()
                                subtitlePositionResetMessage = AppLanguageManager.localizedString(context, R.string.voice_auto_28)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.voice_auto_34))
                    }
                }
            }

            VoiceSettingsCard(
                title = stringResource(R.string.voice_auto_29),
                body = stringResource(R.string.voice_auto_21)
            ) {
                VoiceSwitchRow(
                    title = stringResource(R.string.voice_auto_40),
                    body = "",
                    checked = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceEnabled = it
                        scope.launch { settingsRepository.setAiVoiceEnabled(it) }
                    }
                )
            }

            VoiceSettingsCard(
                title = stringResource(R.string.voice_auto_41),
                body = ""
            ) {
                VoiceCheckboxRow(
                    title = stringResource(R.string.voice_auto_35),
                    body = stringResource(R.string.voice_auto_22),
                    checked = aiVoiceNamedDialogueEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceNamedDialogueEnabled = it
                        scope.launch { settingsRepository.setAiVoiceNamedDialogueEnabled(it) }
                    }
                )
                VoiceCheckboxRow(
                    title = stringResource(R.string.voice_auto_30),
                    body = stringResource(R.string.voice_auto_31),
                    checked = aiVoiceNoSpeakerDialogueEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceNoSpeakerDialogueEnabled = it
                        scope.launch { settingsRepository.setAiVoiceNoSpeakerDialogueEnabled(it) }
                    }
                )
                VoiceCheckboxRow(
                    title = stringResource(R.string.voice_auto_42),
                    body = stringResource(R.string.voice_auto_17),
                    checked = aiVoiceChoiceTextEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceChoiceTextEnabled = it
                        scope.launch { settingsRepository.setAiVoiceChoiceTextEnabled(it) }
                    }
                )
                VoiceMasterGenderIndicator(
                    playerGender = playerGender,
                    enabled = aiVoiceEnabled && aiVoiceChoiceTextEnabled
                )
            }

            VoiceSettingsCard(
                title = stringResource(R.string.voice_auto_43),
                body = ""
            ) {
                VoiceReadTextOption(
                    title = stringResource(R.string.voice_read_chinese),
                    selected = true
                )
            }

            VoiceSettingsCard(
                title = stringResource(R.string.voice_auto_44),
                body = ""
            ) {
                VoiceSpeedSlider(
                    speedPercent = aiVoiceSpeedPercent,
                    onSpeedChange = { speedPercent ->
                        val normalizedSpeed = SettingsRepository.normalizeAiVoiceSpeedPercent(speedPercent)
                        if (normalizedSpeed != aiVoiceSpeedPercent) {
                            aiVoiceSpeedPercent = normalizedSpeed
                            scope.launch { settingsRepository.setAiVoiceSpeedPercent(normalizedSpeed) }
                        }
                    }
                )
                VoiceVolumeSlider(
                    volumePercent = aiVoiceVolumePercent,
                    enabled = aiVoiceEnabled,
                    onVolumeChange = { volumePercent ->
                        val normalizedVolume = SettingsRepository.normalizeAiVoiceVolumePercent(volumePercent)
                        if (normalizedVolume != aiVoiceVolumePercent) {
                            aiVoiceVolumePercent = normalizedVolume
                            scope.launch { settingsRepository.setAiVoiceVolumePercent(normalizedVolume) }
                        }
                    }
                )
                VoiceSwitchRow(
                    title = stringResource(R.string.voice_auto_32),
                    body = if (apiVoiceHintsSupported) {
                        stringResource(R.string.voice_auto_1)
                    } else {
                        stringResource(R.string.voice_auto_4)
                    },
                    checked = effectiveApiVoiceHintsEnabled,
                    enabled = apiVoiceHintsSupported,
                    onCheckedChange = {
                        aiVoiceApiHintsEnabled = it
                        scope.launch { settingsRepository.setAiVoiceApiHintsEnabled(it) }
                    }
                )
            }

            VoiceSettingsCard(
                title = "Azure Speech",
                body = "",
                iconRes = R.drawable.ic_speech_services
            ) {
                Text(
                    stringResource(R.string.voice_auto_25),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    azureSpeechRegionOptions.forEach { option ->
                        AzureSpeechRegionOptionRow(
                            option = option,
                            selected = option.region == azureSpeechRegion,
                            enabled = !azureSpeechTesting,
                            onClick = {
                                val normalizedRegion = SettingsRepository.normalizeAzureSpeechRegion(option.region)
                                azureSpeechRegion = normalizedRegion
                                azureSpeechSaveMessage = ""
                                azureSpeechTestMessage = ""
                                azureSpeechTestIsError = false
                                scope.launch { settingsRepository.setAzureSpeechRegion(normalizedRegion) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    stringResource(R.string.voice_auto_3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
                if (azureSpeechRegion == SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3) {
                    OutlinedTextField(
                        value = azureSpeechEndpoint,
                        onValueChange = {
                            azureSpeechEndpoint = it
                            azureSpeechSaveMessage = ""
                            azureSpeechTestMessage = ""
                            azureSpeechTestIsError = false
                        },
                        label = { Text(stringResource(R.string.voice_auto_13)) },
                        placeholder = { Text(stringResource(R.string.voice_auto_5)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = {
                            Text(stringResource(R.string.voice_auto_2))
                        },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = azureSpeechKey,
                    onValueChange = {
                        azureSpeechKey = it
                        azureSpeechSaveMessage = ""
                        azureSpeechTestMessage = ""
                        azureSpeechTestIsError = false
                    },
                    label = { Text("Azure Speech Key") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(stringResource(R.string.voice_auto_8))
                    },
                    singleLine = true
                )
                Text(
                    stringResource(R.string.voice_auto_6),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
                if (azureSpeechTestMessage.isNotBlank()) {
                    Text(
                        azureSpeechTestMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (azureSpeechTestIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (azureSpeechSaveMessage.isNotBlank()) {
                        Text(
                            azureSpeechSaveMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    OutlinedButton(
                        onClick = { testAzureVoice() },
                        enabled = !azureSpeechTesting
                    ) {
                        Text(if (azureSpeechTesting) stringResource(R.string.voice_auto_36) else stringResource(R.string.voice_auto_45))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { saveAzureSpeechSettings() }) {
                        Text(stringResource(R.string.voice_auto_20))
                    }
                }
            }
        }
    }
}

private data class AzureVoiceTestSample(
    val speakerName: String,
    val dialogue: String
)

private data class AzureSpeechRegionOption(
    val region: String,
    @StringRes val titleRes: Int,
    val subtitle: String
)

private val azureSpeechRegionOptions = listOf(
    AzureSpeechRegionOption(
        region = SettingsRepository.AZURE_SPEECH_REGION_GLOBAL_SOUTHEAST_ASIA,
        titleRes = R.string.voice_auto_26,
        subtitle = "southeastasia"
    ),
    AzureSpeechRegionOption(
        region = SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3,
        titleRes = R.string.voice_auto_27,
        subtitle = "chinanorth3"
    )
)

private fun azureVoiceTestSample(context: Context, targetChineseLocale: String): AzureVoiceTestSample {
    return if (
        SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
        SettingsRepository.TARGET_LOCALE_TRADITIONAL
    ) {
        AzureVoiceTestSample(
            speakerName = AppLanguageManager.localizedString(context, R.string.voice_auto_49),
            dialogue = AppLanguageManager.localizedString(context, R.string.voice_auto_9)
        )
    } else {
        AzureVoiceTestSample(
            speakerName = AppLanguageManager.localizedString(context, R.string.voice_auto_50),
            dialogue = AppLanguageManager.localizedString(context, R.string.voice_auto_10)
        )
    }
}

private fun voiceTestSuccessMessage(
    context: Context,
    result: AzureVoiceTestResult,
    apiHintsEnabled: Boolean,
    apiHintError: Throwable?
): String {
    val apiStatus = when {
        !apiHintsEnabled -> ""
        apiHintError != null -> AppLanguageManager.localizedString(context, R.string.voice_auto_18)
        else -> AppLanguageManager.localizedString(context, R.string.voice_auto_23)
    }
    return context.getString(R.string.voice_test_voice_played, apiStatus)
}

private fun voiceTestErrorMessage(context: Context, error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("Azure Speech key is blank", ignoreCase = true) -> {
            AppLanguageManager.localizedString(context, R.string.voice_auto_14)
        }
        message.contains("HTTP 401", ignoreCase = true) ||
            message.contains("HTTP 403", ignoreCase = true) -> {
            AppLanguageManager.localizedString(context, R.string.voice_auto_12)
        }
        message.contains("Azure TTS failed", ignoreCase = true) -> {
            context.getString(R.string.voice_azure_request_failed, message.take(96))
        }
        message.contains("Mash voice profile not found", ignoreCase = true) -> {
            AppLanguageManager.localizedString(context, R.string.voice_auto_15)
        }
        message.isNotBlank() -> context.getString(R.string.voice_test_failed_detail, message.take(96))
        else -> context.getString(R.string.voice_test_failed_detail, error::class.java.simpleName)
    }
}

@Composable
private fun LiveVoiceSubtitleSizeSlider(
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit
) {
    val normalizedSize = SettingsRepository.normalizeLiveVoiceSubtitleFontSizeSp(fontSizeSp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.voice_auto_46),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                "${normalizedSize}sp",
                style = MaterialTheme.typography.bodyMedium,
                color = if (
                    normalizedSize == SettingsRepository.DEFAULT_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP
                ) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.voice_auto_53),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = normalizedSize.toFloat(),
                onValueChange = { rawValue -> onFontSizeChange(rawValue.roundToInt()) },
                valueRange = SettingsRepository.MIN_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP.toFloat()..
                    SettingsRepository.MAX_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP.toFloat(),
                steps = liveVoiceSubtitleSizeSliderSteps(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                stringResource(R.string.voice_auto_54),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LiveVoiceSubtitlePreview(fontSizeSp: Int) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xCC101010),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = stringResource(R.string.voice_auto_37),
                color = Color.White,
                fontSize = SettingsRepository.normalizeLiveVoiceSubtitleFontSizeSp(fontSizeSp).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

private fun liveVoiceSubtitleSizeSliderSteps(): Int {
    return SettingsRepository.MAX_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP -
        SettingsRepository.MIN_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP -
        1
}

@Composable
private fun VoiceSpeedSlider(
    speedPercent: Int,
    onSpeedChange: (Int) -> Unit
) {
    val normalizedSpeed = SettingsRepository.normalizeAiVoiceSpeedPercent(speedPercent)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.voice_auto_51),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                aiVoiceSpeedMultiplierLabel(normalizedSpeed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (normalizedSpeed == SettingsRepository.DEFAULT_AI_VOICE_SPEED_PERCENT) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.voice_auto_55),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Slider(
                value = normalizedSpeed.toFloat(),
                onValueChange = { rawValue ->
                    onSpeedChange(
                        SettingsRepository.normalizeAiVoiceSpeedPercent(rawValue.roundToInt())
                    )
                },
                valueRange = SettingsRepository.MIN_AI_VOICE_SPEED_PERCENT.toFloat()..
                    SettingsRepository.MAX_AI_VOICE_SPEED_PERCENT.toFloat(),
                steps = aiVoiceSpeedSliderSteps(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                stringResource(R.string.voice_auto_56),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun aiVoiceSpeedMultiplierLabel(speedPercent: Int): String {
    val normalized = SettingsRepository.normalizeAiVoiceSpeedPercent(speedPercent)
    return "${normalized / 100}.${(normalized % 100).toString().padStart(2, '0')}x"
}

private fun aiVoiceSpeedSliderSteps(): Int {
    val intervalCount = (
        SettingsRepository.MAX_AI_VOICE_SPEED_PERCENT -
            SettingsRepository.MIN_AI_VOICE_SPEED_PERCENT
        ) / SettingsRepository.AI_VOICE_SPEED_STEP_PERCENT
    return (intervalCount - 1).coerceAtLeast(0)
}

@Composable
private fun VoiceVolumeSlider(
    volumePercent: Int,
    enabled: Boolean,
    onVolumeChange: (Int) -> Unit
) {
    val normalizedVolume = SettingsRepository.normalizeAiVoiceVolumePercent(volumePercent)
    val contentAlpha = if (enabled) 0.82f else 0.48f
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.voice_auto_52),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                "$normalizedVolume%",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled && normalizedVolume != SettingsRepository.DEFAULT_AI_VOICE_VOLUME_PERCENT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                },
                textAlign = TextAlign.End
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.voice_auto_53),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.48f)
            )
            Slider(
                value = normalizedVolume.toFloat(),
                onValueChange = { rawValue ->
                    onVolumeChange(rawValue.roundToInt())
                },
                valueRange = SettingsRepository.MIN_AI_VOICE_VOLUME_PERCENT.toFloat()..
                    SettingsRepository.MAX_AI_VOICE_VOLUME_PERCENT.toFloat(),
                steps = aiVoiceVolumeSliderSteps(),
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                stringResource(R.string.voice_auto_54),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.48f)
            )
        }
    }
}

private fun aiVoiceVolumeSliderSteps(): Int {
    return SettingsRepository.MAX_AI_VOICE_VOLUME_PERCENT -
        SettingsRepository.MIN_AI_VOICE_VOLUME_PERCENT -
        1
}

@Composable
private fun AzureSpeechRegionOptionRow(
    option: AzureSpeechRegionOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    stringResource(option.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center
                )
                Text(
                    option.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun VoiceCheckboxRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.82f else 0.48f)
            )
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.38f)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun VoiceMasterGenderIndicator(
    playerGender: String,
    enabled: Boolean
) {
    val genderLabel = when (SettingsRepository.normalizePlayerGender(playerGender)) {
        SettingsRepository.PLAYER_GENDER_FEMALE -> stringResource(R.string.voice_auto_57)
        else -> stringResource(R.string.voice_auto_58)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 0.32f else 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.voice_auto_38),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.82f else 0.48f)
                )
                Text(
                    stringResource(R.string.voice_auto_16),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.38f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                genderLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.48f)
            )
        }
    }
}

@Composable
private fun VoiceReadTextOption(
    title: String,
    selected: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = false
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.82f else 0.48f)
            )
        }
    }
}

@Composable
private fun VoiceSettingsCard(
    title: String,
    body: String,
    @DrawableRes iconRes: Int? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (iconRes != null) {
                    VoiceSettingsIconBadge(iconRes)
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (body.isNotBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            content()
        }
    }
}

@Composable
private fun VoiceSettingsIconBadge(@DrawableRes iconRes: Int) {
    Surface(
        modifier = Modifier.size(36.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.small
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun VoiceSwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}





