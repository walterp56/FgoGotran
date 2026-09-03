package com.fgogotran.ui.screen

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.ui.component.AppUpdateDialog
import com.fgogotran.ui.component.BackendProviderLabel
import com.fgogotran.ui.component.openAppDownloadPage
import com.fgogotran.update.AppVersionCheckResult
import com.fgogotran.update.AppVersionInfo
import com.fgogotran.update.AppVersionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DEBUG_LOG_TAP_THRESHOLD = 10
private const val DEBUG_LOG_TAP_WINDOW_MS = 5_000L
private const val FLOATING_BUTTON_SIZE_STEP_DP = 2

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
    appVersionManager: AppVersionManager,
    onClearTranslationCache: suspend () -> Int,
    onApiSettings: () -> Unit,
    onVoiceSettings: () -> Unit,
    onDiagnosticLog: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val appVersionStatus by appVersionManager.status.collectAsState()
    val translationBackend by settingsRepository.translationBackend.collectAsState(
        initial = SettingsRepository.BACKEND_DEEPSEEK
    )
    val apiModel by settingsRepository.apiModel.collectAsState(
        initial = SettingsRepository.DEFAULT_DEEPSEEK_MODEL
    )
    val aiVoiceEnabled by settingsRepository.aiVoiceEnabled.collectAsState(initial = false)
    val liveVoiceTranslationEnabled by settingsRepository.liveVoiceTranslationEnabled.collectAsState(
        initial = false
    )
    val aiVoiceApiHintsEnabled by settingsRepository.aiVoiceApiHintsEnabled.collectAsState(
        initial = SettingsRepository.DEFAULT_AI_VOICE_API_HINTS_ENABLED
    )
    val targetChineseLocale by settingsRepository.targetChineseLocale.collectAsState(
        initial = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    )
    val currentVersionName = remember(appVersionManager) { appVersionManager.currentVersionName() }

    var playerName by remember { mutableStateOf("") }
    var playerNameSaveMessage by remember { mutableStateOf("") }
    var floatingButtonSizeDp by remember {
        mutableStateOf(SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP)
    }
    var showOriginalGameText by remember { mutableStateOf(false) }
    var ocrEngine by remember { mutableStateOf(SettingsRepository.DEFAULT_OCR_ENGINE) }
    var cacheEnabled by remember { mutableStateOf(true) }
    var debugLoggingEnabled by remember { mutableStateOf(false) }
    var debugLogTapCount by remember { mutableStateOf(0) }
    var debugLogTapWindowStartedAt by remember { mutableStateOf(0L) }
    var debugLogMessage by remember { mutableStateOf("") }
    var clearingCache by remember { mutableStateOf(false) }
    var cacheClearMessage by remember { mutableStateOf("") }
    var pendingUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }

    LaunchedEffect(Unit) {
        playerName = settingsRepository.playerName.first()
        floatingButtonSizeDp = settingsRepository.getFloatingButtonSizeDp()
        showOriginalGameText = settingsRepository.showOriginalGameText.first()
        ocrEngine = settingsRepository.getOcrEngine()
        cacheEnabled = settingsRepository.cacheEnabled.first()
        debugLoggingEnabled = settingsRepository.debugLoggingEnabled.first()
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

    fun handleVersionRowTap() {
        val now = SystemClock.elapsedRealtime()
        val nextCount = if (
            debugLogTapWindowStartedAt == 0L ||
            now - debugLogTapWindowStartedAt > DEBUG_LOG_TAP_WINDOW_MS
        ) {
            debugLogTapWindowStartedAt = now
            1
        } else {
            debugLogTapCount + 1
        }

        if (nextCount >= DEBUG_LOG_TAP_THRESHOLD) {
            val enabled = !debugLoggingEnabled
            debugLoggingEnabled = enabled
            debugLogTapCount = 0
            debugLogTapWindowStartedAt = 0L
            debugLogMessage = if (enabled) "调试日志已开启" else "调试日志已关闭"
            scope.launch { settingsRepository.setDebugLoggingEnabled(enabled) }
        } else {
            debugLogTapCount = nextCount
            debugLogMessage = ""
        }
    }

    fun openDownloadPage() {
        openAppDownloadPage(context)
    }

    pendingUpdate?.let { update ->
        AppUpdateDialog(
            currentVersionName = currentVersionName,
            update = update,
            onDismiss = { pendingUpdate = null },
            onUpdateNow = {
                pendingUpdate = null
                openDownloadPage()
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
            SettingsCard(
                iconRes = R.drawable.ic_translate,
                title = "API接口",
                body = ""
            ) {
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

            SettingsCard(
                iconRes = R.drawable.ic_settings_document_scanner,
                title = "OCR 引擎",
                body = "选择适合的 OCR（光学字元识别）引擎"
            ) {
                OcrEngineOption(
                    iconRes = R.drawable.ic_mlkit_japanese_mark,
                    title = "ML Kit OCR",
                    body = "默认引擎。启动快，耗电低。由 Google ML Kit 在本机识别。",
                    selected = ocrEngine == SettingsRepository.OCR_ENGINE_MLKIT,
                    onClick = {
                        ocrEngine = SettingsRepository.OCR_ENGINE_MLKIT
                        scope.launch { settingsRepository.setOcrEngine(SettingsRepository.OCR_ENGINE_MLKIT) }
                    }
                )
                OcrEngineOption(
                    iconRes = R.drawable.ic_paddleocr_mark,
                    title = "PaddleOCR",
                    body = "进阶引擎。准确度高，识别范围广。依赖 CPU 算力。",
                    selected = ocrEngine == SettingsRepository.OCR_ENGINE_PADDLE,
                    onClick = {
                        ocrEngine = SettingsRepository.OCR_ENGINE_PADDLE
                        scope.launch { settingsRepository.setOcrEngine(SettingsRepository.OCR_ENGINE_PADDLE) }
                    }
                )
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_tune,
                title = "翻译偏好",
                body = ""
            ) {
//                Text(
//                    "用于翻译对话里的御主称呼。",
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
//                )
                TranslationLanguageSelector(
                    selectedLocale = targetChineseLocale,
                    onSelect = { locale ->
                        scope.launch {
                            settingsRepository.setTargetChineseLocale(locale)
                        }
                    }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "日文原文",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        )
                        Text(
                            "同时显示游戏原文",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = showOriginalGameText,
                        onCheckedChange = {
                            showOriginalGameText = it
                            scope.launch { settingsRepository.setShowOriginalGameText(it) }
                        }
                    )
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_voice,
                title = "语音设置",
                body = ""
            ) {
                SettingsInfoRow(
                    label = "实时语音字幕",
                    value = if (liveVoiceTranslationEnabled) "已开启" else "未开启",
                    valueColor = if (liveVoiceTranslationEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                SettingsInfoRow(
                    label = "语音朗读",
                    value = if (aiVoiceEnabled) "已开启" else "未开启",
                    valueColor = if (aiVoiceEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                SettingsInfoRow(
                    label = "朗读文本",
                    value = "中文"
                )
                SettingsInfoRow(
                    label = "语气增强",
                    value = if (aiVoiceApiHintsEnabled) "开启" else "关闭",
                    valueColor = if (aiVoiceApiHintsEnabled) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                Button(
                    onClick = onVoiceSettings,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("打开语音设置")
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_touch_app,
                title = "悬浮按钮",
                body = ""
            ) {
                SettingsInfoRow(
                    label = "大小",
                    valueContent = {
                        Text(
                            text = floatingButtonSizeLabel(floatingButtonSizeDp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (floatingButtonSizeDp == SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            textAlign = TextAlign.End
                        )
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "小",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = floatingButtonSizeDp.toFloat(),
                        onValueChange = { rawValue ->
                            val roundedSize = roundFloatingButtonSize(rawValue)
                            if (roundedSize != floatingButtonSizeDp) {
                                floatingButtonSizeDp = roundedSize
                                scope.launch {
                                    settingsRepository.setFloatingButtonSizeDp(roundedSize)
                                }
                            }
                        },
                        valueRange = SettingsRepository.MIN_FLOATING_BUTTON_SIZE_DP.toFloat()..
                            SettingsRepository.MAX_FLOATING_BUTTON_SIZE_DP.toFloat(),
                        steps = floatingButtonSizeSteps(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        "大",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_cached,
                title = "翻译缓存",
                body = "相同原文可直接使用上次翻译，速度更快；\n遇到旧译文时可以清除缓存。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "启用缓存",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                    )
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

            SettingsCard(
                iconRes = R.drawable.ic_settings_error_log,
                title = "错误纪录",
                body = "用于调试和排查问题。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onDiagnosticLog) {
                        Text("打开错误纪录")
                    }
                }
            }

            SettingsCard(
                iconRes = R.drawable.ic_settings_build,
                title = "维护",
                body = ""
            ) {
                SettingsInfoRow(
                    label = "当前版本",
                    value = currentVersionName,
                    modifier = Modifier.clickable { handleVersionRowTap() }
                )
                DebugLogNotice(
                    text = debugLogMessage,
                    enabled = debugLoggingEnabled
                )
                SettingsInfoRow(
                    label = "状态",
                    value = appVersionStatus.message.ifBlank { "手动检查新版本" }
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

private val targetChineseLocaleOptions = listOf(
    TargetChineseLocaleOption(
        locale = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,
        label = "简体中文"
    ),
    TargetChineseLocaleOption(
        locale = SettingsRepository.TARGET_LOCALE_TRADITIONAL,
        label = "繁體中文"
    )
)

private data class TargetChineseLocaleOption(
    val locale: String,
    val label: String
)

@Composable
private fun TranslationLanguageSelector(
    selectedLocale: String,
    onSelect: (String) -> Unit
) {
    val normalizedLocale = SettingsRepository.normalizeTargetChineseLocale(selectedLocale)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "日服译文语言",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            targetChineseLocaleOptions.forEach { option ->
                TranslationLanguageOption(
                    option = option,
                    selected = option.locale == normalizedLocale,
                    onClick = { onSelect(option.locale) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TranslationLanguageOption(
    option: TargetChineseLocaleOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OcrEngineOption(
    @DrawableRes iconRes: Int,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
        }
    }
}

private fun roundFloatingButtonSize(rawValue: Float): Int {
    val minSize = SettingsRepository.MIN_FLOATING_BUTTON_SIZE_DP
    val roundedSize = minSize +
        ((rawValue - minSize) / FLOATING_BUTTON_SIZE_STEP_DP).roundToInt() *
            FLOATING_BUTTON_SIZE_STEP_DP
    return SettingsRepository.normalizeFloatingButtonSizeDp(roundedSize)
}

private fun floatingButtonSizeSteps(): Int {
    val intervals = (
        SettingsRepository.MAX_FLOATING_BUTTON_SIZE_DP -
            SettingsRepository.MIN_FLOATING_BUTTON_SIZE_DP
        ) / FLOATING_BUTTON_SIZE_STEP_DP
    return (intervals - 1).coerceAtLeast(0)
}

private fun floatingButtonSizeLabel(sizeDp: Int): String {
    val safeSize = SettingsRepository.normalizeFloatingButtonSizeDp(sizeDp)
    return when {
        safeSize < SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP -> "较小"
        safeSize == SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP -> "标准"
        safeSize < SettingsRepository.MAX_FLOATING_BUTTON_SIZE_DP -> "较大"
        else -> "最大"
    }
}

@Composable
private fun SettingsCard(
    @DrawableRes iconRes: Int,
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
                SettingsIconBadge(iconRes)
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
private fun SettingsIconBadge(@DrawableRes iconRes: Int) {
    Surface(
        modifier = Modifier.size(36.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.small
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DebugLogNotice(
    text: String,
    enabled: Boolean
) {
    if (text.isBlank()) return

    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
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
    valueContent: (@Composable () -> Unit)? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                    color = valueColor,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
