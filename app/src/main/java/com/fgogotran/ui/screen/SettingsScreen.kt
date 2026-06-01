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
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
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
            // ── Translation Backend ──
            Text("翻譯後端", style = MaterialTheme.typography.titleMedium)
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
                supportingText = { Text("用於翻譯 API 的密鑰") }
            )
            Button(onClick = { saveApiKey() }, modifier = Modifier.align(Alignment.End)) {
                Text("保存 API Key")
            }

            HorizontalDivider()

            // ── Player Name ──
            Text("玩家名稱 (Master名)", style = MaterialTheme.typography.titleMedium)
            Text(
                "輸入您在FGO中的玩家名稱，翻譯時會正確處理對話中的玩家名稱。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            OutlinedTextField(
                value = playerName,
                onValueChange = { playerName = it },
                label = { Text("玩家名稱") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例：藤丸立香") }
            )
            Button(onClick = { savePlayerName() }, modifier = Modifier.align(Alignment.End)) {
                Text("保存玩家名稱")
            }

            HorizontalDivider()

            // ── Translation Cache ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("翻譯緩存")
                    Text(
                        "快取過的翻譯可瞬間顯示",
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
