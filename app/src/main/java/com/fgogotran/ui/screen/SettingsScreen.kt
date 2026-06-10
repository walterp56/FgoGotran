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
import com.fgogotran.terminology.GlossaryUpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings form allowing configuration of:
 * - Translation backend (DeepSeek / Claude / GPT via FilterChip row)
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
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val timeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val dbContentVersion by settingsRepository.dbContentVersion.collectAsState(initial = "")
    val dbLastCheckAt by settingsRepository.dbLastCheckAt.collectAsState(initial = 0L)
    val dbLastUpdateAt by settingsRepository.dbLastUpdateAt.collectAsState(initial = 0L)
    val dbUpdateStatus by glossaryUpdateManager.updateStatus.collectAsState()

    // Form state — initialized from DataStore via LaunchedEffect
    var apiKey by remember { mutableStateOf("") }
    var playerName by remember { mutableStateOf("") }
    var selectedBackend by remember { mutableStateOf(SettingsRepository.BACKEND_DEEPSEEK) }
    var cacheEnabled by remember { mutableStateOf(true) }

    // Load persisted settings on first composition
    LaunchedEffect(Unit) {
        apiKey = settingsRepository.apiKey.first()
        playerName = settingsRepository.playerName.first()
        selectedBackend = settingsRepository.translationBackend.first()
        cacheEnabled = settingsRepository.cacheEnabled.first()
    }

    fun saveApiKey() {
        scope.launch { settingsRepository.setApiKey(apiKey) }
    }

    fun savePlayerName() {
        scope.launch { settingsRepository.setPlayerName(playerName) }
    }

    fun formatTime(epochMillis: Long): String {
        return if (epochMillis <= 0L) "从未" else timeFormatter.format(Date(epochMillis))
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
                    Text("数据库", style = MaterialTheme.typography.titleMedium)
                    SettingsInfoRow(label = "版本", value = dbContentVersion.ifBlank { "内置数据库" })
                    SettingsInfoRow(label = "上次检查", value = formatTime(dbLastCheckAt))
                    SettingsInfoRow(label = "上次更新", value = formatTime(dbLastUpdateAt))
                    SettingsInfoRow(
                        label = "状态",
                        value = listOf(dbUpdateStatus.message, dbUpdateStatus.detail)
                            .filter { it.isNotBlank() }
                            .joinToString(" - ")
                    )
                    Text(
                        "每天首次启动悬浮按钮服务时自动检查一次。",
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
                        Text(if (dbUpdateStatus.isChecking) "检查中" else "检查更新")
                    }
                }
            }

            HorizontalDivider()
            // ── Translation Backend ──
            Text("翻译后端", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SettingsRepository.BACKEND_DEEPSEEK to "DeepSeek",
                    SettingsRepository.BACKEND_CLAUDE to "Claude",
                    SettingsRepository.BACKEND_GPT to "GPT"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = selectedBackend == value,
                        onClick = {
                            selectedBackend = value
                            scope.launch { settingsRepository.setTranslationBackend(value) }
                        },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider()

            // ── API Key ──
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                // Mask the key — it's sensitive like a password
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("用于翻译 API 的密钥") }
            )
            Button(onClick = { saveApiKey() }, modifier = Modifier.align(Alignment.End)) {
                Text("保存 API Key")
            }

            HorizontalDivider()

            // ── Player Name ──
            Text("玩家名称（Master名）", style = MaterialTheme.typography.titleMedium)
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
                placeholder = { Text("例：藤丸立香") }
            )
            Button(onClick = { savePlayerName() }, modifier = Modifier.align(Alignment.End)) {
                Text("保存玩家名称")
            }

            HorizontalDivider()

            // ── Translation Cache ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("翻译缓存")
                    Text(
                        "缓存过的翻译可快速显示",
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

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
