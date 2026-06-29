package com.fgogotran.ui.screen

import android.os.SystemClock
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
import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.Translator
import com.fgogotran.ui.component.BackendProviderLabel
import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val API_SETTINGS_LOG_TAG = "ApiSettings"

private data class BackendOption(
    val value: String,
    val note: String? = null
)

private fun formatApiResponseTime(durationMs: Long): String {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val tenths = (safeDuration + 50L) / 100L
    return "${tenths / 10}.${tenths % 10} 秒"
}

private fun apiTestMessage(status: String, durationMs: Long, result: String): String {
    return "状态：$status\n用时：${formatApiResponseTime(durationMs)}\n结果：$result"
}

private fun apiTestFailureResult(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.contains("Role must be in [user, assistant]", ignoreCase = true) ->
            "模型不兼容 FgoGotran 翻译格式"
        message.contains("API Key", ignoreCase = true) || message.contains("401") ->
            "请检查 API Key"
        message.contains("model", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true) ||
            message.contains("invalid_request", ignoreCase = true) ||
            message.contains("400") ->
            "模型或请求参数不被服务商接受：${message.take(160)}"
        message.contains("quota", ignoreCase = true) ||
            message.contains("insufficient", ignoreCase = true) ||
            message.contains("balance", ignoreCase = true) ||
            message.contains("402") ||
            message.contains("429") ->
            "额度或频率限制异常：${message.take(160)}"
        message.contains("empty", ignoreCase = true) ->
            "模型返回为空"
        message.contains("untranslated", ignoreCase = true) ->
            "模型没有按 FgoGotran 翻译格式返回中文"
        message.isNotBlank() ->
            "服务商返回错误：${message.take(160)}"
        else ->
            "测试失败，请检查模型和网络连接"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    settingsRepository: SettingsRepository,
    translator: Translator,
    appAnalytics: AppAnalytics,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val backendOptions = remember {
        listOf(
            BackendOption(SettingsRepository.BACKEND_DEEPSEEK),
            BackendOption(SettingsRepository.BACKEND_ZHIPU),
            BackendOption(SettingsRepository.BACKEND_QWEN),
            BackendOption(SettingsRepository.BACKEND_GPT),
            BackendOption(SettingsRepository.BACKEND_GEMINI),
            BackendOption(SettingsRepository.BACKEND_CLAUDE),
            BackendOption(
                SettingsRepository.BACKEND_CUSTOM_OPENAI,
                "兼容 OpenAI Chat Completions 的接口"
            )
        )
    }

    var selectedBackend by remember { mutableStateOf(SettingsRepository.BACKEND_DEEPSEEK) }
    var apiBaseUrl by remember { mutableStateOf("") }
    var apiModel by remember { mutableStateOf(SettingsRepository.DEFAULT_DEEPSEEK_MODEL) }
    var apiKey by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf("") }
    var saveMessageIsError by remember { mutableStateOf(false) }
    var testingApi by remember { mutableStateOf(false) }
    val isCustomBackend = selectedBackend == SettingsRepository.BACKEND_CUSTOM_OPENAI

    fun effectiveApiBaseUrl(): String {
        return if (isCustomBackend) {
            apiBaseUrl
        } else {
            SettingsRepository.defaultApiBaseUrl(selectedBackend)
        }
    }

    LaunchedEffect(Unit) {
        selectedBackend = SettingsRepository.normalizeBackend(settingsRepository.translationBackend.first())
        apiBaseUrl = if (selectedBackend == SettingsRepository.BACKEND_CUSTOM_OPENAI) {
            settingsRepository.getApiBaseUrlForBackend(selectedBackend)
                .ifBlank { SettingsRepository.defaultApiBaseUrl(selectedBackend) }
        } else {
            SettingsRepository.defaultApiBaseUrl(selectedBackend)
        }
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
        saveMessageIsError = false
        scope.launch {
            val savedBaseUrl = if (backend == SettingsRepository.BACKEND_CUSTOM_OPENAI) {
                settingsRepository.getApiBaseUrlForBackend(backend)
                    .ifBlank { SettingsRepository.defaultApiBaseUrl(backend) }
            } else {
                SettingsRepository.defaultApiBaseUrl(backend)
            }
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
        saveMessageIsError = false
    }

    fun saveSettings() {
        scope.launch {
            settingsRepository.saveApiSettings(
                backend = selectedBackend,
                apiKey = apiKey,
                apiBaseUrl = effectiveApiBaseUrl(),
                apiModel = apiModel
            )
            saveMessage = "已保存"
            saveMessageIsError = false
            val savedBackend = selectedBackend
            scope.launch {
                appAnalytics.reportBackendType(savedBackend)
            }
        }
    }

    fun testApi() {
        if (testingApi) return
        testingApi = true
        saveMessage = ""
        saveMessageIsError = false
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val requestBackend = SettingsRepository.normalizeBackend(selectedBackend)
            val requestBaseUrl = effectiveApiBaseUrl()
            val requestModel = apiModel.trim().ifBlank {
                SettingsRepository.defaultApiModel(requestBackend)
            }
            FgoLogger.info(
                API_SETTINGS_LOG_TAG,
                "API test started: backend=$requestBackend, model=$requestModel, " +
                    "baseUrl=$requestBaseUrl, keyChars=${apiKey.trim().length}"
            )
            try {
                translator.testApiSettings(
                    backend = requestBackend,
                    apiKey = apiKey,
                    apiBaseUrl = requestBaseUrl,
                    apiModel = requestModel
                )
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                FgoLogger.info(
                    API_SETTINGS_LOG_TAG,
                    "API test succeeded: backend=$requestBackend, model=$requestModel, elapsedMs=$elapsedMs"
                )
                saveMessage = apiTestMessage(
                    status = "成功",
                    durationMs = elapsedMs,
                    result = "可用于 FgoGotran 翻译"
                )
                saveMessageIsError = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                FgoLogger.warn(
                    API_SETTINGS_LOG_TAG,
                    "API test failed: backend=$requestBackend, model=$requestModel, " +
                        "baseUrl=$requestBaseUrl, elapsedMs=$elapsedMs, " +
                        "error=${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                saveMessage = apiTestMessage(
                    status = "失败",
                    durationMs = elapsedMs,
                    result = apiTestFailureResult(e)
                )
                saveMessageIsError = true
            } finally {
                testingApi = false
            }
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
                                    label = SettingsRepository.backendDisplayName(option.value),
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
                    if (isCustomBackend) {
                        OutlinedTextField(
                            value = apiBaseUrl,
                            onValueChange = {
                                apiBaseUrl = it
                                saveMessage = ""
                                saveMessageIsError = false
                            },
                            label = { Text("API 地址") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = apiModel,
                        onValueChange = {
                            apiModel = it
                            saveMessage = ""
                            saveMessageIsError = false
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
                            saveMessageIsError = false
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
                            color = if (saveMessageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { testApi() },
                            enabled = !testingApi
                        ) {
                            if (testingApi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (testingApi) "测试中" else "测试 API")
                        }
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
                            Text("应用API设置")
                        }
                    }
                }
            }
        }
    }
}
