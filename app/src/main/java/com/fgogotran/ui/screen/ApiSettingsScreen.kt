package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.ui.component.BackendProviderLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class BackendOption(
    val value: String,
    val label: String,
    val note: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val backendOptions = remember {
        listOf(
            BackendOption(
                SettingsRepository.BACKEND_DEEPSEEK,
                "DeepSeek"
            ),
            BackendOption(
                SettingsRepository.BACKEND_ZHIPU,
                "智谱 GLM"
            ),
            BackendOption(
                SettingsRepository.BACKEND_QWEN,
                "阿里云 Qwen"
            ),
            BackendOption(
                SettingsRepository.BACKEND_GPT,
                "OpenAI"
            ),
            BackendOption(
                SettingsRepository.BACKEND_CLAUDE,
                "Claude"
            ),
            BackendOption(
                SettingsRepository.BACKEND_CUSTOM_OPENAI,
                "自定义接口",
                "兼容 OpenAI Chat Completions 的接口"
            )
        )
    }

    var selectedBackend by remember { mutableStateOf(SettingsRepository.BACKEND_DEEPSEEK) }
    var apiBaseUrl by remember { mutableStateOf("") }
    var apiModel by remember { mutableStateOf(SettingsRepository.DEFAULT_DEEPSEEK_MODEL) }
    var apiKey by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        selectedBackend = SettingsRepository.normalizeBackend(settingsRepository.translationBackend.first())
        apiBaseUrl = settingsRepository.getApiBaseUrlForBackend(selectedBackend)
            .ifBlank { SettingsRepository.defaultApiBaseUrl(selectedBackend) }
        apiModel = settingsRepository.getApiModelForBackend(selectedBackend)
            .ifBlank { SettingsRepository.defaultApiModel(selectedBackend) }
        apiKey = settingsRepository.getApiKeyForBackend(selectedBackend)
    }

    fun selectBackend(backend: String) {
        selectedBackend = backend
        apiBaseUrl = SettingsRepository.defaultApiBaseUrl(backend)
        apiModel = SettingsRepository.defaultApiModel(backend)
        apiKey = ""
        saveMessage = ""
        scope.launch {
            val savedBaseUrl = settingsRepository.getApiBaseUrlForBackend(backend)
                .ifBlank { SettingsRepository.defaultApiBaseUrl(backend) }
            val savedModel = settingsRepository.getApiModelForBackend(backend)
                .ifBlank { SettingsRepository.defaultApiModel(backend) }
            val savedKey = settingsRepository.getApiKeyForBackend(backend)
            if (selectedBackend == backend) {
                apiBaseUrl = savedBaseUrl
                apiModel = savedModel
                apiKey = savedKey
            }
        }
    }

    fun restoreBackendDefaults() {
        apiBaseUrl = SettingsRepository.defaultApiBaseUrl(selectedBackend)
        apiModel = SettingsRepository.defaultApiModel(selectedBackend)
        saveMessage = ""
    }

    fun saveSettings() {
        scope.launch {
            settingsRepository.saveApiSettings(
                backend = selectedBackend,
                apiKey = apiKey,
                apiBaseUrl = apiBaseUrl,
                apiModel = apiModel
            )
            saveMessage = "已保存"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻译接口") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("服务商", style = MaterialTheme.typography.titleMedium)
                    backendOptions.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedBackend == option.value,
                                onClick = { selectBackend(option.value) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                BackendProviderLabel(
                                    backend = option.value,
                                    label = option.label,
                                    textStyle = MaterialTheme.typography.bodyLarge
                                )
                                option.note?.let { note ->
                                    Text(
                                        note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("请求设置", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = {
                            apiBaseUrl = it
                            saveMessage = ""
                        },
                        label = { Text("API 地址") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiModel,
                        onValueChange = {
                            apiModel = it
                            saveMessage = ""
                        },
                        label = { Text("模型") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            saveMessage = ""
                        },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            Text("当前服务商需要 API Key")
                        },
                        singleLine = true
                    )
                    if (saveMessage.isNotBlank()) {
                        Text(
                            saveMessage,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { restoreBackendDefaults() }
                        ) {
                            Text("恢复默认")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { saveSettings() }) {
                            Text("保存设置")
                        }
                    }
                }
            }
        }
    }
}
