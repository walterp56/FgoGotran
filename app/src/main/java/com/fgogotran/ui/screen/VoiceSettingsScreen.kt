package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fgogotran.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var aiVoiceEnabled by remember { mutableStateOf(false) }
    var aiVoiceApiHintsEnabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_AI_VOICE_API_HINTS_ENABLED)
    }
    var azureSpeechKey by remember { mutableStateOf("") }
    var azureSpeechSaveMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        aiVoiceEnabled = settingsRepository.aiVoiceEnabled.first()
        aiVoiceApiHintsEnabled = settingsRepository.aiVoiceApiHintsEnabled.first()
        azureSpeechKey = settingsRepository.azureSpeechKey.first()
    }

    fun saveAzureSpeechSettings() {
        scope.launch {
            settingsRepository.saveAzureSpeechSettings(azureSpeechKey)
            azureSpeechSaveMessage = "已保存"
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
                body = "按角色语音档朗读剧情台词。非官方语音。"
            ) {
                VoiceSwitchRow(
                    title = "启用语音",
                    body = "开启后，识别到剧情台词时自动播放角色语音。",
                    checked = aiVoiceEnabled,
                    onCheckedChange = {
                        aiVoiceEnabled = it
                        scope.launch { settingsRepository.setAiVoiceEnabled(it) }
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
                VoiceSwitchRow(
                    title = "AI 语气增强",
                    body = "开启后，翻译 API 可额外返回本句情绪、速度与音高提示；关闭时只使用本机语气规则。",
                    checked = aiVoiceApiHintsEnabled,
                    onCheckedChange = {
                        aiVoiceApiHintsEnabled = it
                        scope.launch { settingsRepository.setAiVoiceApiHintsEnabled(it) }
                    }
                )
            }

            VoiceSettingsCard(
                title = "Azure Speech",
                body = ""
            ) {
                OutlinedTextField(
                    value = azureSpeechKey,
                    onValueChange = {
                        azureSpeechKey = it
                        azureSpeechSaveMessage = ""
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
                    Button(onClick = { saveAzureSpeechSettings() }) {
                        Text("保存语音设置")
                    }
                }
            }
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
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.82f else 0.48f)
            )
        }
    }
}

@Composable
private fun VoiceSettingsCard(
    title: String,
    body: String,
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
