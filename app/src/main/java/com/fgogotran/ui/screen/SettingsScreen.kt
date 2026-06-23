package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.ui.component.BackendProviderLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings form allowing configuration of:
 * - Translation backend and model via the API settings screen
 * - API key (password-masked text field with save button)
 * - Player Master name (text field with save button)
 * - Translation cache toggle
 *
 * All changes are saved immediately to [SettingsRepository] via DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    glossaryUpdateManager: GlossaryUpdateManager,
    onClearTranslationCache: suspend () -> Int,
    onApiSettings: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dbContentVersion by settingsRepository.dbContentVersion.collectAsState(initial = "")
    val dbUpdateStatus by glossaryUpdateManager.updateStatus.collectAsState()
    val translationBackend by settingsRepository.translationBackend.collectAsState(
        initial = SettingsRepository.BACKEND_DEEPSEEK
    )
    val apiModel by settingsRepository.apiModel.collectAsState(
        initial = SettingsRepository.DEFAULT_DEEPSEEK_MODEL
    )

    // Form state — initialized from DataStore via LaunchedEffect
    var playerName by remember { mutableStateOf("") }
    var playerNameSaveMessage by remember { mutableStateOf("") }
    var cacheEnabled by remember { mutableStateOf(true) }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheClearMessage by remember { mutableStateOf("") }

    // Load persisted settings on first composition
    LaunchedEffect(Unit) {
        playerName = settingsRepository.playerName.first()
        cacheEnabled = settingsRepository.cacheEnabled.first()
    }

    fun savePlayerName() {
        scope.launch {
            settingsRepository.setPlayerName(playerName)
            playerNameSaveMessage = "已保存"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("翻译接口", style = MaterialTheme.typography.titleMedium)
                    SettingsInfoRow(
                        label = "服务商",
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
                        label = "模型",
                        value = apiModel.ifBlank { SettingsRepository.defaultApiModel(translationBackend) }
                    )
                    Button(
                        onClick = onApiSettings,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("管理翻译接口")
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("术语库", style = MaterialTheme.typography.titleMedium)
                    SettingsInfoRow(label = "版本", value = dbContentVersion.ifBlank { "等待自动更新" })
                    SettingsInfoRow(
                        label = "状态",
                        value = when {
                            dbUpdateStatus.visible && dbUpdateStatus.message.isNotBlank() -> dbUpdateStatus.message
                            else -> "打开应用时自动检查"
                        }
                    )
                    if (dbUpdateStatus.visible && dbUpdateStatus.detail.isNotBlank()) {
                        Text(
                            dbUpdateStatus.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                glossaryUpdateManager.updateIfNeeded(force = true)
                            }
                        },
                        enabled = !dbUpdateStatus.isChecking,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (dbUpdateStatus.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (dbUpdateStatus.isChecking) "检查中" else "检查更新")
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
                    Text("御主名称", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "输入您在 FGO 中的御主名称，翻译时会正确处理对话中的御主名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = playerName,
                        onValueChange = {
                            playerName = it
                            playerNameSaveMessage = ""
                        },
                        label = { Text("御主名称") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例：藤丸立香") },
                        singleLine = true
                    )
                    Button(onClick = { savePlayerName() }, modifier = Modifier.align(Alignment.End)) {
                        Text("保存御主名称")
                    }
                    if (playerNameSaveMessage.isNotBlank()) {
                        Text(
                            playerNameSaveMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("翻译缓存", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "开启后相同日文会直接使用上次翻译，速度更快。关闭后会重新请求翻译，适合测试模型、提示词或检查翻译改善；关闭不会删除已有缓存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = cacheEnabled,
                        onCheckedChange = {
                            cacheEnabled = it
                            scope.launch { settingsRepository.setCacheEnabled(it) }
                        }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("清除翻译缓存", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "如果某句一直显示旧翻译或错误翻译，或刚更新术语库、模型、API 设置、御主名称，请使用此按钮。只会删除已保存的翻译结果，不会删除术语库、API 设置或御主名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Button(
                        onClick = {
                            clearingCache = true
                            cacheClearMessage = ""
                            scope.launch {
                                runCatching { onClearTranslationCache() }
                                    .onSuccess { count ->
                                        cacheClearMessage = "已清除 $count 条翻译缓存"
                                    }
                                    .onFailure {
                                        cacheClearMessage = "清除翻译缓存失败"
                                    }
                                clearingCache = false
                            }
                        },
                        enabled = !clearingCache,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (clearingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (clearingCache) "清除中" else "清除翻译缓存")
                    }
                    if (cacheClearMessage.isNotBlank()) {
                        Text(
                            cacheClearMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String = "",
    valueContent: (@Composable () -> Unit)? = null
) {
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
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}
