package com.fgogotran.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.ui.component.BackendProviderLabel
import com.fgogotran.update.AppVersionCheckResult
import com.fgogotran.update.AppVersionInfo
import com.fgogotran.update.AppVersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings page for user-facing configuration and maintenance actions.
 *
 * The page keeps frequent choices near the top and groups rare maintenance
 * actions together so the screen stays scannable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    glossaryUpdateManager: GlossaryUpdateManager,
    appVersionManager: AppVersionManager,
    onClearTranslationCache: suspend () -> Int,
    onApiSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val appVersionStatus by appVersionManager.status.collectAsState()
    val dbContentVersion by settingsRepository.dbContentVersion.collectAsState(initial = "")
    val dbUpdateStatus by glossaryUpdateManager.updateStatus.collectAsState()
    val translationBackend by settingsRepository.translationBackend.collectAsState(
        initial = SettingsRepository.BACKEND_DEEPSEEK
    )
    val apiModel by settingsRepository.apiModel.collectAsState(
        initial = SettingsRepository.DEFAULT_DEEPSEEK_MODEL
    )
    val currentVersionName = remember(appVersionManager) { appVersionManager.currentVersionName() }
    val currentVersionCode = remember(appVersionManager) { appVersionManager.currentVersionCode() }

    var playerName by remember { mutableStateOf("") }
    var playerNameSaveMessage by remember { mutableStateOf("") }
    var cacheEnabled by remember { mutableStateOf(true) }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheClearMessage by remember { mutableStateOf("") }
    var pendingUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }

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

    fun checkAppVersion() {
        scope.launch {
            when (val result = appVersionManager.checkNow()) {
                is AppVersionCheckResult.UpdateAvailable -> pendingUpdate = result.info
                AppVersionCheckResult.UpToDate -> Unit
                is AppVersionCheckResult.Failed -> Unit
            }
        }
    }

    fun clearTranslationCache() {
        clearingCache = true
        cacheClearMessage = ""
        scope.launch {
            runCatching { onClearTranslationCache() }
                .onSuccess { count ->
                    cacheClearMessage = "已清除 $count 条缓存"
                }
                .onFailure {
                    cacheClearMessage = "清除缓存失败"
                }
            clearingCache = false
        }
    }

    fun openDownloadPage() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(AppVersionManager.DOWNLOAD_PAGE_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    pendingUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { pendingUpdate = null },
            title = { Text("发现新版本") },
            text = {
                Text("当前版本 $currentVersionName，最新版本 ${update.versionName}。")
            },
            dismissButton = {
                TextButton(onClick = { pendingUpdate = null }) {
                    Text("暂不更新")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingUpdate = null
                        openDownloadPage()
                    }
                ) {
                    Text("立即更新")
                }
            }
        )
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard(title = "翻译接口") {
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
                    Text("管理接口")
                }
            }

            SettingsCard(title = "御主名称") {
                Text(
                    "用于翻译对话里的御主称呼。",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (playerNameSaveMessage.isNotBlank()) {
                        Text(
                            playerNameSaveMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Button(onClick = { savePlayerName() }) {
                        Text("保存")
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("翻译缓存", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "相同原文可直接使用上次翻译，速度更快。",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (cacheClearMessage.isNotBlank()) {
                            Text(
                                cacheClearMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        OutlinedButton(
                            onClick = { clearTranslationCache() },
                            enabled = !clearingCache
                        ) {
                            if (clearingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (clearingCache) "清除中" else "清除缓存")
                        }
                    }
                }
            }

            SettingsCard(title = "维护") {
                Text("术语库", style = MaterialTheme.typography.titleSmall)
                SettingsInfoRow(label = "版本", value = dbContentVersion.ifBlank { "等待自动更新" })
                SettingsInfoRow(
                    label = "状态",
                    value = when {
                        dbUpdateStatus.visible && dbUpdateStatus.message.isNotBlank() -> dbUpdateStatus.message
                        else -> "打开应用时自动检查"
                    }
                )
                StatusDetailText(
                    text = dbUpdateStatus.detail.takeIf {
                        dbUpdateStatus.visible && dbUpdateStatus.detail.isNotBlank()
                    }.orEmpty()
                )
                OutlinedButton(
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
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (dbUpdateStatus.isChecking) "检查中" else "检查术语库")
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )

                Text("应用版本", style = MaterialTheme.typography.titleSmall)
                SettingsInfoRow(
                    label = "当前版本",
                    value = "$currentVersionName ($currentVersionCode)"
                )
                SettingsInfoRow(
                    label = "状态",
                    value = appVersionStatus.message.ifBlank { "手动检查新版本" }
                )
                StatusDetailText(
                    text = appVersionStatus.detail.takeIf {
                        appVersionStatus.isChecking ||
                            appVersionStatus.isError ||
                            appVersionStatus.message != "当前版本 $currentVersionName"
                    }.orEmpty(),
                    isError = appVersionStatus.isError
                )
                OutlinedButton(
                    onClick = { checkAppVersion() },
                    enabled = !appVersionStatus.isChecking,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (appVersionStatus.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (appVersionStatus.isChecking) "检查中" else "检查版本")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
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
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusDetailText(
    text: String,
    isError: Boolean = false
) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        }
    )
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
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
