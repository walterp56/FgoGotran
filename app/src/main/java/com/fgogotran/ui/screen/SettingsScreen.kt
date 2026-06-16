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
    var cacheEnabled by remember { mutableStateOf(true) }

    // Load persisted settings on first composition
    LaunchedEffect(Unit) {
        playerName = settingsRepository.playerName.first()
        cacheEnabled = settingsRepository.cacheEnabled.first()
    }

    fun savePlayerName() {
        scope.launch { settingsRepository.setPlayerName(playerName) }
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("玩家名称", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "输入您在 FGO 中的玩家名称，翻译时会正确处理对话中的玩家名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = playerName,
                        onValueChange = { playerName = it },
                        label = { Text("玩家名称") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例：藤丸立香") },
                        singleLine = true
                    )
                    Button(onClick = { savePlayerName() }, modifier = Modifier.align(Alignment.End)) {
                        Text("保存玩家名称")
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
                    SettingsInfoRow(label = "版本", value = dbContentVersion.ifBlank { "内置术语库" })
                    SettingsInfoRow(
                        label = "状态",
                        value = dbUpdateStatus.message.ifBlank { "空闲" }
                    )
                    Text(
                        "用于角色名和游戏专有名词翻译。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Button(
                        onClick = { scope.launch { glossaryUpdateManager.updateIfNeeded(force = true) } },
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
                        Text(if (dbUpdateStatus.isChecking) "检查中" else "检查术语库更新")
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
                    Column {
                        Text("翻译缓存", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "缓存过的翻译可快速显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = cacheEnabled,
                        onCheckedChange = {
                            cacheEnabled = it
                            scope.launch { settingsRepository.setCacheEnabled(it) }
                        }
                    )
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
