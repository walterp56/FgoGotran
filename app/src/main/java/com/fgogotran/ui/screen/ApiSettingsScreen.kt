package com.fgogotran.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.fgogotran.localmodel.LocalLlamaModelManager
import com.fgogotran.localmodel.LocalLlamaModelSpec
import com.fgogotran.localmodel.LocalLlamaTranslator
import com.fgogotran.ui.component.BackendProviderLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class BackendOption(
    val value: String,
    val label: String,
    val note: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    settingsRepository: SettingsRepository,
    localLlamaModelManager: LocalLlamaModelManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val localModelStatus by localLlamaModelManager.modelStatus.collectAsState()
    val installedLocalModelId by settingsRepository.localLlamaModelId.collectAsState(initial = "")
    val installedLocalModelVersion by settingsRepository.localLlamaModelVersion.collectAsState(initial = "")
    val installedLocalModelSize by settingsRepository.localLlamaModelSize.collectAsState(initial = 0L)
    val backendOptions = remember {
        listOf(
            BackendOption(
                SettingsRepository.BACKEND_FGOGOTRAN,
                "FgoGotran 后端",
                "应用只连接你的后端，模型由后端管理"
            ),
            BackendOption(
                SettingsRepository.BACKEND_LOCAL_LLAMA,
                "手机本地模型",
                if (LocalLlamaTranslator.RUNTIME_AVAILABLE) {
                    "使用 llama.cpp 运行已下载的 GGUF 模型"
                } else {
                    "当前仅支持下载和管理 GGUF 模型"
                }
            ),
            BackendOption(
                SettingsRepository.BACKEND_DEEPSEEK,
                "DeepSeek",
                "默认使用 deepseek-v4-flash"
            ),
            BackendOption(
                SettingsRepository.BACKEND_GPT,
                "OpenAI",
                "OpenAI Chat Completions"
            ),
            BackendOption(
                SettingsRepository.BACKEND_CLAUDE,
                "Claude",
                "Anthropic Messages API"
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
        selectedBackend = settingsRepository.translationBackend.first()
        apiBaseUrl = settingsRepository.apiBaseUrl.first()
            .ifBlank { SettingsRepository.defaultApiBaseUrl(selectedBackend) }
        apiModel = settingsRepository.apiModel.first()
            .ifBlank { SettingsRepository.defaultApiModel(selectedBackend) }
        apiKey = settingsRepository.apiKey.first()
        localLlamaModelManager.refreshAvailableModels()
    }

    val isLocalLlama = selectedBackend == SettingsRepository.BACKEND_LOCAL_LLAMA

    fun applyBackendDefaults(backend: String) {
        selectedBackend = backend
        apiBaseUrl = SettingsRepository.defaultApiBaseUrl(backend)
        apiModel = SettingsRepository.defaultApiModel(backend)
        saveMessage = ""
    }

    suspend fun persistSettings() {
        settingsRepository.saveApiSettings(
            backend = selectedBackend,
            apiKey = apiKey,
            apiBaseUrl = apiBaseUrl,
            apiModel = apiModel
        )
    }

    val localModelImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            localLlamaModelManager.importModelFromUri(
                modelId = apiModel.ifBlank { SettingsRepository.DEFAULT_LOCAL_LLAMA_MODEL },
                sourceUri = uri
            )
        }
    }

    fun saveSettings() {
        scope.launch {
            if (selectedBackend == SettingsRepository.BACKEND_LOCAL_LLAMA &&
                installedLocalModelId.isBlank()
            ) {
                saveMessage = "请先下载或导入本地模型"
                return@launch
            }
            if (selectedBackend == SettingsRepository.BACKEND_LOCAL_LLAMA &&
                !LocalLlamaTranslator.RUNTIME_AVAILABLE
            ) {
                saveMessage = "本地运行库尚未接入，当前只保存模型文件，不作为翻译服务"
                return@launch
            }
            persistSettings()
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
                                onClick = { applyBackendDefaults(option.value) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                BackendProviderLabel(
                                    backend = option.value,
                                    label = option.label,
                                    textStyle = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    option.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            if (isLocalLlama) {
                LocalLlamaModelCard(
                    models = localModelStatus.availableModels,
                    selectedModelId = apiModel.ifBlank { SettingsRepository.DEFAULT_LOCAL_LLAMA_MODEL },
                    installedModelId = installedLocalModelId,
                    installedVersion = installedLocalModelVersion,
                    installedSizeBytes = installedLocalModelSize,
                    isBusy = localModelStatus.isBusy,
                    statusMessage = localModelStatus.message,
                    statusDetail = localModelStatus.detail,
                    saveMessage = saveMessage,
                    onSelectModel = {
                        apiModel = it
                        saveMessage = ""
                    },
                    onRefreshManifest = {
                        scope.launch { localLlamaModelManager.refreshAvailableModels() }
                    },
                    onDownloadModel = {
                        scope.launch {
                            localLlamaModelManager.downloadModel(apiModel.ifBlank { SettingsRepository.DEFAULT_LOCAL_LLAMA_MODEL })
                        }
                    },
                    onImportModel = {
                        localModelImportLauncher.launch(arrayOf("*/*"))
                    },
                    onDeleteModel = {
                        scope.launch {
                            localLlamaModelManager.deleteInstalledModel()
                        }
                    },
                    onSave = { saveSettings() }
                )
            } else {
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
                                Text(
                                    if (SettingsRepository.requiresApiKey(selectedBackend)) {
                                        "当前服务商需要 API Key"
                                    } else {
                                        "FgoGotran 后端可留空"
                                    }
                                )
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
                                onClick = { applyBackendDefaults(selectedBackend) }
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
}

@Composable
private fun LocalLlamaModelCard(
    models: List<LocalLlamaModelSpec>,
    selectedModelId: String,
    installedModelId: String,
    installedVersion: String,
    installedSizeBytes: Long,
    isBusy: Boolean,
    statusMessage: String,
    statusDetail: String,
    saveMessage: String,
    onSelectModel: (String) -> Unit,
    onRefreshManifest: () -> Unit,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onSave: () -> Unit
) {
    val installedLabel = if (installedModelId.isBlank()) {
        "未下载"
    } else {
        val versionText = installedVersion.ifBlank { "未知版本" }
        "$installedModelId $versionText · ${formatModelSize(installedSizeBytes)}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("本地模型", style = MaterialTheme.typography.titleMedium)
            Text(
                "模型会下载到应用私有目录，不会打包进 APK。删除应用会同时删除模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            SettingsInfoLine(label = "已安装", value = installedLabel)
            SettingsInfoLine(
                label = "运行库",
                value = if (LocalLlamaTranslator.RUNTIME_AVAILABLE) {
                    "llama.cpp native runtime 已接入"
                } else {
                    "尚未接入，当前仅可下载和管理模型"
                }
            )

            models.forEach { model ->
                val isInstalled = installedModelId == model.id
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(
                        selected = selectedModelId == model.id,
                        onClick = { onSelectModel(model.id) }
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            "大小 ${formatModelSize(model.sizeBytes)} · 建议 ${model.recommendedRamGb}GB 内存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            modelSourceLabel(model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (isInstalled) {
                        AssistChip(
                            onClick = {},
                            label = { Text("已下载") }
                        )
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column {
                        Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                        if (statusDetail.isNotBlank()) {
                            Text(
                                statusDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            if (saveMessage.isNotBlank()) {
                Text(
                    saveMessage,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onRefreshManifest,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("同步清单")
                }
                OutlinedButton(
                    onClick = onImportModel,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入 GGUF")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDeleteModel,
                    enabled = !isBusy && installedModelId.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("删除模型")
                }
                Button(
                    onClick = onDownloadModel,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("下载选择")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onSave,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存设置")
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "清单同步后显示"
    val mib = bytes / 1024.0 / 1024.0
    return if (mib >= 1024.0) {
        "%.2f GB".format(mib / 1024.0)
    } else {
        "%.1f MB".format(mib)
    }
}

private fun modelSourceLabel(model: LocalLlamaModelSpec): String {
    return if (model.downloadUrls.isEmpty()) {
        "可同步清单后下载，也可以手动导入 GGUF"
    } else {
        "下载源 ${model.downloadUrls.size} 个，也支持手动导入"
    }
}
