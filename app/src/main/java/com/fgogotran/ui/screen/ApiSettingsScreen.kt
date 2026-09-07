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
import com.fgogotran.data.ApiSamplingSettings
import com.fgogotran.data.SettingsRepository
import com.fgogotran.network.ApiEndpointPolicy
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

private fun formatSamplingValue(value: Double): String = value.toString()

private fun samplingRecommendation(backend: String, apiModel: String): String {
    return when (SettingsRepository.normalizeBackend(backend)) {
        SettingsRepository.BACKEND_DEEPSEEK ->
            "自动：Temperature ${SettingsRepository.DEFAULT_DEEPSEEK_TEMPERATURE}；不发送 Top-p。"
        SettingsRepository.BACKEND_ZHIPU ->
            "自动：关闭随机采样；翻译输出更稳定。"
        SettingsRepository.BACKEND_QWEN ->
            "自动：使用当前 Qwen 模型的默认采样参数。"
        SettingsRepository.BACKEND_GPT -> if (
            SettingsRepository.supportsApiSamplingCustomization(backend, apiModel)
        ) {
            "自动：当前 GPT 模型使用 Temperature 0.3。"
        } else {
            "自动：当前 GPT 模型不发送采样参数，避免请求被拒绝。"
        }
        SettingsRepository.BACKEND_GEMINI ->
            "自动：Gemini 使用模型默认值，不发送采样参数。"
        SettingsRepository.BACKEND_CLAUDE ->
            "自动：Claude 使用模型默认值，不发送采样参数。"
        else ->
            "自动：使用本地模型或兼容接口的默认采样参数。"
    }
}

private fun apiTestFailureResult(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.startsWith("API 地址") ||
            message.startsWith("HTTP 仅允许") ||
            message.startsWith("模型名称") ->
            message
        message.contains("Cleartext HTTP traffic", ignoreCase = true) ->
            "应用未允许本地 HTTP，请安装支持本地 AI 的新版 APK"
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
        message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ->
            "连接模型超时，请确认电脑端模型已加载完成"
        message.contains("connect", ignoreCase = true) ||
            message.contains("unreachable", ignoreCase = true) ||
            message.contains("refused", ignoreCase = true) ->
            "无法连接服务器，请检查电脑 IP、端口、防火墙和 VPN 的局域网放行设置"
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
                "兼容 OpenAI Chat Completions；支持可信局域网内的本地模型"
            )
        )
    }

    var selectedBackend by remember { mutableStateOf(SettingsRepository.BACKEND_DEEPSEEK) }
    var qwenSite by remember { mutableStateOf(SettingsRepository.DEFAULT_QWEN_SITE) }
    var apiBaseUrl by remember { mutableStateOf("") }
    var apiModel by remember { mutableStateOf(SettingsRepository.DEFAULT_DEEPSEEK_MODEL) }
    var apiKey by remember { mutableStateOf("") }
    var advancedSamplingExpanded by remember { mutableStateOf(false) }
    var samplingMode by remember { mutableStateOf(SettingsRepository.API_SAMPLING_MODE_AUTO) }
    var temperatureEnabled by remember { mutableStateOf(false) }
    var temperatureText by remember {
        mutableStateOf(
            formatSamplingValue(
                SettingsRepository.defaultApiSamplingSettings(
                    SettingsRepository.BACKEND_DEEPSEEK
                ).temperature
            )
        )
    }
    var topPText by remember {
        mutableStateOf(
            formatSamplingValue(
                SettingsRepository.defaultApiSamplingSettings(
                    SettingsRepository.BACKEND_DEEPSEEK
                ).topP
            )
        )
    }
    var topPEnabled by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    var saveMessageIsError by remember { mutableStateOf(false) }
    var testingApi by remember { mutableStateOf(false) }
    val isCustomBackend = selectedBackend == SettingsRepository.BACKEND_CUSTOM_OPENAI
    val isQwenBackend = selectedBackend == SettingsRepository.BACKEND_QWEN
    val samplingCapabilities = SettingsRepository.apiSamplingCapabilities(
        selectedBackend,
        apiModel
    )
    val supportsSamplingCustomization = samplingCapabilities.supportsCustomization

    fun applySamplingSettings(settings: ApiSamplingSettings) {
        samplingMode = settings.mode
        temperatureEnabled = settings.temperatureEnabled
        temperatureText = formatSamplingValue(settings.temperature)
        topPEnabled = settings.topPEnabled
        topPText = formatSamplingValue(settings.topP)
    }

    fun currentSamplingSettings(): ApiSamplingSettings {
        val defaults = SettingsRepository.defaultApiSamplingSettings(selectedBackend)
        val effectiveMode = if (supportsSamplingCustomization) {
            SettingsRepository.normalizeApiSamplingMode(samplingMode)
        } else {
            SettingsRepository.API_SAMPLING_MODE_AUTO
        }
        var effectiveTemperatureEnabled =
            effectiveMode == SettingsRepository.API_SAMPLING_MODE_CUSTOM &&
                samplingCapabilities.supportsTemperature &&
                temperatureEnabled
        var effectiveTopPEnabled =
            effectiveMode == SettingsRepository.API_SAMPLING_MODE_CUSTOM &&
                samplingCapabilities.supportsTopP &&
                topPEnabled
        if (
            !samplingCapabilities.allowsCombinedParameters &&
            effectiveTemperatureEnabled &&
            effectiveTopPEnabled
        ) {
            effectiveTopPEnabled = false
        }
        val temperature = temperatureText.trim().replace(',', '.').toDoubleOrNull()
        val topP = topPText.trim().replace(',', '.').toDoubleOrNull()
        if (effectiveTemperatureEnabled) {
            require(
                temperature != null &&
                    temperature in SettingsRepository.MIN_API_TEMPERATURE..
                    SettingsRepository.MAX_API_TEMPERATURE
            ) { "Temperature 必须在 0.00 到 1.00 之间" }
        }
        if (effectiveTopPEnabled) {
            require(
                topP != null &&
                    topP in SettingsRepository.MIN_API_TOP_P..SettingsRepository.MAX_API_TOP_P
            ) { "Top-p 必须在 0.01 到 1.00 之间" }
        }
        return ApiSamplingSettings(
            mode = effectiveMode,
            temperatureEnabled = effectiveTemperatureEnabled,
            temperature = temperature
                ?.takeIf {
                    it.isFinite() &&
                        it in SettingsRepository.MIN_API_TEMPERATURE..
                        SettingsRepository.MAX_API_TEMPERATURE
                }
                ?: defaults.temperature,
            topPEnabled = effectiveTopPEnabled,
            topP = topP
                ?.takeIf {
                    it.isFinite() &&
                        it in SettingsRepository.MIN_API_TOP_P..SettingsRepository.MAX_API_TOP_P
                }
                ?: defaults.topP
        )
    }

    fun effectiveApiBaseUrl(): String {
        return when {
            isCustomBackend -> apiBaseUrl
            isQwenBackend -> SettingsRepository.defaultQwenBaseUrl(qwenSite)
            else -> SettingsRepository.defaultApiBaseUrl(selectedBackend)
        }
    }

    LaunchedEffect(Unit) {
        selectedBackend = SettingsRepository.normalizeBackend(settingsRepository.translationBackend.first())
        val savedQwenSite = settingsRepository.getQwenSite()
        qwenSite = savedQwenSite
        apiBaseUrl = when (selectedBackend) {
            SettingsRepository.BACKEND_CUSTOM_OPENAI -> settingsRepository.getApiBaseUrlForBackend(selectedBackend)
                .ifBlank { SettingsRepository.defaultApiBaseUrl(selectedBackend) }
            SettingsRepository.BACKEND_QWEN -> SettingsRepository.defaultQwenBaseUrl(savedQwenSite)
            else -> SettingsRepository.defaultApiBaseUrl(selectedBackend)
        }
        apiModel = settingsRepository.getApiModelForBackend(selectedBackend)
            .ifBlank { SettingsRepository.defaultApiModel(selectedBackend) }
        apiKey = settingsRepository.getApiKeyForBackend(selectedBackend)
        applySamplingSettings(
            settingsRepository.getApiSamplingSettingsForBackend(selectedBackend, apiModel)
        )
    }

    fun selectBackend(backend: String) {
        selectedBackend = backend
        apiBaseUrl = if (backend == SettingsRepository.BACKEND_QWEN) {
            SettingsRepository.defaultQwenBaseUrl(qwenSite)
        } else {
            SettingsRepository.defaultApiBaseUrl(backend)
        }
        apiModel = SettingsRepository.defaultApiModel(backend)
        apiKey = ""
        applySamplingSettings(SettingsRepository.defaultApiSamplingSettings(backend))
        saveMessage = ""
        saveMessageIsError = false
        scope.launch {
            val savedQwenSite = settingsRepository.getQwenSite()
            val savedBaseUrl = when (backend) {
                SettingsRepository.BACKEND_CUSTOM_OPENAI -> settingsRepository.getApiBaseUrlForBackend(backend)
                    .ifBlank { SettingsRepository.defaultApiBaseUrl(backend) }
                SettingsRepository.BACKEND_QWEN -> SettingsRepository.defaultQwenBaseUrl(savedQwenSite)
                else -> SettingsRepository.defaultApiBaseUrl(backend)
            }
            val savedModel = settingsRepository.getApiModelForBackend(backend)
                .ifBlank { SettingsRepository.defaultApiModel(backend) }
            val savedKey = settingsRepository.getApiKeyForBackend(backend)
            val savedSamplingSettings = settingsRepository.getApiSamplingSettingsForBackend(
                backend,
                savedModel
            )
            if (selectedBackend == backend) {
                if (backend == SettingsRepository.BACKEND_QWEN) {
                    qwenSite = savedQwenSite
                }
                apiBaseUrl = savedBaseUrl
                apiModel = savedModel
                apiKey = savedKey
                applySamplingSettings(savedSamplingSettings)
            }
        }
    }

    fun selectQwenSite(site: String) {
        qwenSite = SettingsRepository.normalizeQwenSite(site)
        apiBaseUrl = SettingsRepository.defaultQwenBaseUrl(qwenSite)
        saveMessage = ""
        saveMessageIsError = false
    }

    fun restoreBackendDefaults() {
        if (isQwenBackend) {
            qwenSite = SettingsRepository.DEFAULT_QWEN_SITE
            apiBaseUrl = SettingsRepository.defaultQwenBaseUrl(qwenSite)
        } else {
            apiBaseUrl = SettingsRepository.defaultApiBaseUrl(selectedBackend)
        }
        apiModel = SettingsRepository.defaultApiModel(selectedBackend)
        applySamplingSettings(SettingsRepository.defaultApiSamplingSettings(selectedBackend))
        saveMessage = ""
        saveMessageIsError = false
    }

    fun saveSettings() {
        scope.launch {
            try {
                settingsRepository.saveApiSettings(
                    backend = selectedBackend,
                    apiKey = apiKey,
                    apiBaseUrl = effectiveApiBaseUrl(),
                    apiModel = apiModel,
                    qwenSite = qwenSite,
                    samplingSettings = currentSamplingSettings()
                )
                saveMessage = "已保存"
                saveMessageIsError = false
                val savedBackend = selectedBackend
                scope.launch {
                    appAnalytics.reportBackendType(savedBackend)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveMessage = e.message?.takeIf { it.isNotBlank() } ?: "保存失败"
                saveMessageIsError = true
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
                val testResult = translator.testApiSettings(
                    backend = requestBackend,
                    apiKey = apiKey,
                    apiBaseUrl = requestBaseUrl,
                    apiModel = requestModel,
                    samplingSettings = currentSamplingSettings()
                )
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                FgoLogger.info(
                    API_SETTINGS_LOG_TAG,
                    "API test succeeded: backend=$requestBackend, model=$requestModel, elapsedMs=$elapsedMs"
                )
                saveMessage = apiTestMessage(
                    status = "成功",
                    durationMs = elapsedMs,
                    result = if (testResult.ignoredSamplingParameters.isNotEmpty()) {
                        val ignoredLabels = testResult.ignoredSamplingParameters.joinToString("、") {
                            when (it) {
                                "temperature" -> "Temperature"
                                "top_p" -> "Top-p"
                                "do_sample" -> "采样开关"
                                else -> it
                            }
                        }
                        "可用于 FgoGotran 翻译；接口不接受 $ignoredLabels，已自动忽略"
                    } else {
                        "可用于 FgoGotran 翻译"
                    }
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
                title = { Text("API接口") },
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
                    if (isQwenBackend) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("站点", style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = qwenSite == SettingsRepository.QWEN_SITE_CHINA,
                                    onClick = { selectQwenSite(SettingsRepository.QWEN_SITE_CHINA) },
                                    label = { Text("中国站") }
                                )
                                FilterChip(
                                    selected = qwenSite == SettingsRepository.QWEN_SITE_INTERNATIONAL,
                                    onClick = {
                                        selectQwenSite(SettingsRepository.QWEN_SITE_INTERNATIONAL)
                                    },
                                    label = { Text("国际站") }
                                )
                            }
                            Text(
                                "请使用对应站点的 API Key。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
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
                            supportingText = {
                                Text("HTTPS 可连接任意服务；HTTP 仅允许数字形式的私有局域网 IP")
                            },
                            singleLine = true
                        )
                        if (apiBaseUrl.trim().startsWith("http://", ignoreCase = true)) {
                            val localEndpointValid = remember(apiBaseUrl) {
                                runCatching {
                                    ApiEndpointPolicy.validateCustomOpenAiEndpoint(apiBaseUrl)
                                }.getOrNull()?.isPrivateLanHttp == true
                            }
                            Text(
                                if (localEndpointValid) {
                                    "本地 HTTP 不加密，仅应在可信 Wi-Fi 中使用；请保留 API Key 认证。"
                                } else {
                                    "HTTP 地址必须使用私有局域网数字 IP，并指向 /chat/completions。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (localEndpointValid) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
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
                    HorizontalDivider()
                    TextButton(
                        onClick = { advancedSamplingExpanded = !advancedSamplingExpanded },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(if (advancedSamplingExpanded) "收起高级自定义" else "高级自定义")
                    }
                    if (advancedSamplingExpanded) {
                        Text(
                            samplingRecommendation(selectedBackend, apiModel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = !supportsSamplingCustomization ||
                                    samplingMode == SettingsRepository.API_SAMPLING_MODE_AUTO,
                                onClick = {
                                    samplingMode = SettingsRepository.API_SAMPLING_MODE_AUTO
                                    saveMessage = ""
                                    saveMessageIsError = false
                                },
                                label = { Text("自动（推荐）") }
                            )
                            FilterChip(
                                selected = supportsSamplingCustomization &&
                                    samplingMode == SettingsRepository.API_SAMPLING_MODE_CUSTOM,
                                onClick = {
                                    if (supportsSamplingCustomization) {
                                        samplingMode = SettingsRepository.API_SAMPLING_MODE_CUSTOM
                                        if (!temperatureEnabled && !topPEnabled) {
                                            when {
                                                samplingCapabilities.supportsTemperature ->
                                                    temperatureEnabled = true
                                                samplingCapabilities.supportsTopP ->
                                                    topPEnabled = true
                                            }
                                        }
                                        saveMessage = ""
                                        saveMessageIsError = false
                                    }
                                },
                                enabled = supportsSamplingCustomization,
                                label = { Text("自定义") }
                            )
                        }
                        if (!supportsSamplingCustomization) {
                            Text(
                                "该服务商或模型为保证请求兼容性，仅使用自动模式。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else if (samplingMode == SettingsRepository.API_SAMPLING_MODE_CUSTOM) {
                            Text(
                                if (samplingCapabilities.allowsCombinedParameters) {
                                    "本地／自定义接口可单独启用，也可同时发送两个参数。"
                                } else {
                                    "云端接口一次只启用一个参数；开启另一项会自动关闭当前项。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Temperature", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (samplingCapabilities.supportsTemperature) {
                                            "控制随机程度"
                                        } else {
                                            "当前模型不支持"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Switch(
                                    checked = temperatureEnabled &&
                                        samplingCapabilities.supportsTemperature,
                                    onCheckedChange = { enabled ->
                                        temperatureEnabled = enabled
                                        if (enabled && !samplingCapabilities.allowsCombinedParameters) {
                                            topPEnabled = false
                                        }
                                        saveMessage = ""
                                        saveMessageIsError = false
                                    },
                                    enabled = samplingCapabilities.supportsTemperature
                                )
                            }
                            OutlinedTextField(
                                value = temperatureText,
                                onValueChange = {
                                    temperatureText = it
                                    saveMessage = ""
                                    saveMessageIsError = false
                                },
                                label = { Text("Temperature") },
                                supportingText = { Text("范围 0.00–1.00；数值越低越稳定") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                enabled = temperatureEnabled &&
                                    samplingCapabilities.supportsTemperature,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Top-p", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (samplingCapabilities.supportsTopP) {
                                            "限制候选词范围"
                                        } else {
                                            "当前模型不支持"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Switch(
                                    checked = topPEnabled && samplingCapabilities.supportsTopP,
                                    onCheckedChange = { enabled ->
                                        topPEnabled = enabled
                                        if (enabled && !samplingCapabilities.allowsCombinedParameters) {
                                            temperatureEnabled = false
                                        }
                                        saveMessage = ""
                                        saveMessageIsError = false
                                    },
                                    enabled = samplingCapabilities.supportsTopP
                                )
                            }
                            OutlinedTextField(
                                value = topPText,
                                onValueChange = {
                                    topPText = it
                                    saveMessage = ""
                                    saveMessageIsError = false
                                },
                                label = { Text("Top-p") },
                                supportingText = { Text("范围 0.01–1.00；数值越低候选范围越小") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                enabled = topPEnabled && samplingCapabilities.supportsTopP,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
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
