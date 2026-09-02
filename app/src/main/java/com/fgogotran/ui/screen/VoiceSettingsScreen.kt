package com.fgogotran.ui.screen

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
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
    val scrollState = rememberScrollState()

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
    var aiVoiceMasterVoice by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_MASTER_VOICE)
    }
    var azureSpeechKey by remember { mutableStateOf("") }
    var azureSpeechRegion by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AZURE_SPEECH_REGION)
    }
    var azureSpeechSaveMessage by remember { mutableStateOf("") }
    var azureSpeechTestMessage by remember { mutableStateOf("") }
    var azureSpeechTestIsError by remember { mutableStateOf(false) }
    var azureSpeechTesting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        aiVoiceEnabled = settingsRepository.aiVoiceEnabled.first()
        aiVoiceApiHintsEnabled = settingsRepository.aiVoiceApiHintsEnabled.first()
        aiVoiceSpeedPercent = settingsRepository.aiVoiceSpeedPercent.first()
        aiVoiceVolumePercent = settingsRepository.aiVoiceVolumePercent.first()
        aiVoiceNamedDialogueEnabled = settingsRepository.aiVoiceNamedDialogueEnabled.first()
        aiVoiceNoSpeakerDialogueEnabled = settingsRepository.aiVoiceNoSpeakerDialogueEnabled.first()
        aiVoiceChoiceTextEnabled = settingsRepository.aiVoiceChoiceTextEnabled.first()
        aiVoiceMasterVoice = settingsRepository.aiVoiceMasterVoice.first()
        azureSpeechKey = settingsRepository.azureSpeechKey.first()
        azureSpeechRegion = settingsRepository.azureSpeechRegion.first()
    }

    fun saveAzureSpeechSettings() {
        scope.launch {
            settingsRepository.saveAzureSpeechSettings(azureSpeechKey, azureSpeechRegion)
            azureSpeechSaveMessage = "已保存"
        }
    }

    fun testAzureVoice() {
        if (azureSpeechTesting) return
        scope.launch {
            azureSpeechTesting = true
            azureSpeechSaveMessage = ""
            azureSpeechTestIsError = false
            azureSpeechTestMessage = "正在生成测试语音..."
            try {
                if (azureSpeechKey.trim().isBlank()) {
                    throw IllegalArgumentException("Azure Speech key is blank")
                }
                settingsRepository.saveAzureSpeechSettings(azureSpeechKey, azureSpeechRegion)
                val sample = azureVoiceTestSample(settingsRepository.targetChineseLocale.first())
                var voiceHint: VoiceLineHint? = null
                var voiceHintError: Throwable? = null
                if (aiVoiceApiHintsEnabled) {
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
                    result = result,
                    apiHintsEnabled = aiVoiceApiHintsEnabled,
                    apiHintError = voiceHintError
                )
            } catch (e: Throwable) {
                azureSpeechTestIsError = true
                azureSpeechTestMessage = voiceTestErrorMessage(e)
            } finally {
                azureSpeechTesting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.primary)
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
                title = "AI 语音朗读",
                body = "按角色朗读剧情台词。"
            ) {
                VoiceSwitchRow(
                    title = "启用语音",
                    body = "",
                    checked = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceEnabled = it
                        scope.launch { settingsRepository.setAiVoiceEnabled(it) }
                    }
                )
            }

            VoiceSettingsCard(
                title = "朗读范围",
                body = ""
            ) {
                VoiceCheckboxRow(
                    title = "有角色名对话",
                    body = "使用对应角色语音。",
                    checked = aiVoiceNamedDialogueEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceNamedDialogueEnabled = it
                        scope.launch { settingsRepository.setAiVoiceNamedDialogueEnabled(it) }
                    }
                )
                VoiceCheckboxRow(
                    title = "旁白／无名对白",
                    body = "使用旁白语音。",
                    checked = aiVoiceNoSpeakerDialogueEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceNoSpeakerDialogueEnabled = it
                        scope.launch { settingsRepository.setAiVoiceNoSpeakerDialogueEnabled(it) }
                    }
                )
                VoiceCheckboxRow(
                    title = "御主选项",
                    body = "使用御主（男）/（女）语音。",
                    checked = aiVoiceChoiceTextEnabled,
                    enabled = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceChoiceTextEnabled = it
                        scope.launch { settingsRepository.setAiVoiceChoiceTextEnabled(it) }
                    }
                )
                Text(
                    "选项文字声音",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (aiVoiceEnabled && aiVoiceChoiceTextEnabled) 0.82f else 0.48f
                    )
                )
                VoiceMasterVoiceOption(
                    title = "御主（男）",
                    selected = aiVoiceMasterVoice == SettingsRepository.AI_VOICE_MASTER_MALE,
                    enabled = aiVoiceEnabled && aiVoiceChoiceTextEnabled,
                    onClick = {
                        aiVoiceMasterVoice = SettingsRepository.AI_VOICE_MASTER_MALE
                        scope.launch {
                            settingsRepository.setAiVoiceMasterVoice(SettingsRepository.AI_VOICE_MASTER_MALE)
                        }
                    }
                )
                VoiceMasterVoiceOption(
                    title = "御主（女）",
                    selected = aiVoiceMasterVoice == SettingsRepository.AI_VOICE_MASTER_FEMALE,
                    enabled = aiVoiceEnabled && aiVoiceChoiceTextEnabled,
                    onClick = {
                        aiVoiceMasterVoice = SettingsRepository.AI_VOICE_MASTER_FEMALE
                        scope.launch {
                            settingsRepository.setAiVoiceMasterVoice(SettingsRepository.AI_VOICE_MASTER_FEMALE)
                        }
                    }
                )
            }

            VoiceSettingsCard(
                title = "朗读文本",
                body = ""
            ) {
                VoiceReadTextOption(
                    title = "中文",
                    selected = true
                )
            }

            VoiceSettingsCard(
                title = "表现调节",
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
                    title = "AI 语气增强",
                    body = "开启：调用 API 分析本句情绪、语速、音高，并临时匹配语音；新角色也可尝试播放。\n关闭：只用本机规则和已收录语音；更快、更稳定，但新角色需等数据库更新后才有语音。",
                    checked = aiVoiceApiHintsEnabled,
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
                    "Azure 区域",
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
                    "提示：可选全球 Azure；中国 Azure 需要组织/工作/学校账号，个人 Microsoft 账号不能登录使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
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
                        Text("仅保存在本机，用于 AI 语音朗读。")
                    },
                    singleLine = true
                )
                Text(
                    "测试例句：玛修・基列莱特，在此。御主……战斗准备完成，请下达指示。",
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
                        Text(if (azureSpeechTesting) "测试中..." else "测试语音")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { saveAzureSpeechSettings() }) {
                        Text("保存语音设置")
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
    val title: String,
    val subtitle: String
)

private val azureSpeechRegionOptions = listOf(
    AzureSpeechRegionOption(
        region = SettingsRepository.AZURE_SPEECH_REGION_GLOBAL_SOUTHEAST_ASIA,
        title = "全球 Azure",
        subtitle = "southeastasia"
    ),
    AzureSpeechRegionOption(
        region = SettingsRepository.AZURE_SPEECH_REGION_CHINA_NORTH3,
        title = "中国 Azure",
        subtitle = "chinanorth3"
    )
)

private fun azureVoiceTestSample(targetChineseLocale: String): AzureVoiceTestSample {
    return if (
        SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
        SettingsRepository.TARGET_LOCALE_TRADITIONAL
    ) {
        AzureVoiceTestSample(
            speakerName = "瑪修",
            dialogue = "瑪修・基列萊特，在此。御主……戰鬥準備完成，請下達指示。"
        )
    } else {
        AzureVoiceTestSample(
            speakerName = "玛修",
            dialogue = "玛修·基列莱特，在此。御主……战斗准备完成，请下达指示。"
        )
    }
}

private fun voiceTestSuccessMessage(
    result: AzureVoiceTestResult,
    apiHintsEnabled: Boolean,
    apiHintError: Throwable?
): String {
    val apiStatus = when {
        !apiHintsEnabled -> ""
        apiHintError != null -> "；语气增强 API 失败"
        else -> "（语气增强已开启）"
    }
    return "测试语音已播放$apiStatus"
}

private fun voiceTestErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("Azure Speech key is blank", ignoreCase = true) -> {
            "Azure Speech Key 为空"
        }
        message.contains("HTTP 401", ignoreCase = true) ||
            message.contains("HTTP 403", ignoreCase = true) -> {
            "Azure Key 无效，或当前区域不可用"
        }
        message.contains("Azure TTS failed", ignoreCase = true) -> {
            "Azure 语音请求失败：${message.take(96)}"
        }
        message.contains("Mash voice profile not found", ignoreCase = true) -> {
            "找不到瑪修语音档，请先更新语音资料"
        }
        message.isNotBlank() -> "测试失败：${message.take(96)}"
        else -> "测试失败：${error::class.java.simpleName}"
    }
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
                "语速",
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
                "慢",
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
                "快",
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
                "音量",
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
                "小",
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
                "大",
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
                    option.title,
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
private fun VoiceMasterVoiceOption(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 0.32f else 0.16f)
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
                onClick = if (enabled) onClick else null,
                enabled = enabled
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = when {
                        !enabled -> 0.48f
                        selected -> 0.82f
                        else -> 0.68f
                    }
                )
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
            onCheckedChange = onCheckedChange
        )
    }
}
