package com.fgogotran.ui.screen

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fgogotran.R
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

private fun formatApiResponseTime(context: Context, durationMs: Long): String {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val tenths = (safeDuration + 50L) / 100L
    return context.getString(R.string.api_response_seconds, "${tenths / 10}.${tenths % 10}")
}

private fun apiTestMessage(context: Context, status: String, durationMs: Long, result: String): String =
    context.getString(
        R.string.api_test_message,
        status,
        formatApiResponseTime(context, durationMs),
        result
    )

private fun formatSamplingValue(value: Double): String = value.toString()

private fun apiBackendDisplayName(context: Context, backend: String): String =
    when (SettingsRepository.normalizeBackend(backend)) {
        SettingsRepository.BACKEND_ZHIPU -> context.getString(R.string.api_backend_zhipu)
        SettingsRepository.BACKEND_QWEN -> context.getString(R.string.api_backend_qwen)
        SettingsRepository.BACKEND_CUSTOM_OPENAI -> context.getString(R.string.api_backend_custom)
        else -> SettingsRepository.backendDisplayName(backend)
    }

private fun samplingRecommendation(context: Context, backend: String, apiModel: String): String =
    when (SettingsRepository.normalizeBackend(backend)) {
        SettingsRepository.BACKEND_DEEPSEEK -> context.getString(
            R.string.api_sampling_deepseek,
            SettingsRepository.DEFAULT_DEEPSEEK_TEMPERATURE.toString()
        )
        SettingsRepository.BACKEND_ZHIPU -> context.getString(R.string.api_sampling_zhipu)
        SettingsRepository.BACKEND_QWEN -> context.getString(R.string.api_sampling_qwen)
        SettingsRepository.BACKEND_GPT -> if (
            SettingsRepository.supportsApiSamplingCustomization(backend, apiModel)
        ) {
            context.getString(R.string.api_sampling_gpt_custom)
        } else {
            context.getString(R.string.api_sampling_gpt_default)
        }
        SettingsRepository.BACKEND_GEMINI -> context.getString(R.string.api_sampling_gemini)
        SettingsRepository.BACKEND_CLAUDE -> context.getString(R.string.api_sampling_claude)
        else -> context.getString(R.string.api_sampling_local)
    }

private fun apiTestFailureResult(context: Context, error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message.startsWith("API 地址") ||
            message.startsWith("HTTP 仅允许") ||
            message.startsWith("模型名称") ->
            localizedApiError(context, message)
        message.contains("Cleartext HTTP traffic", ignoreCase = true) ->
            context.getString(R.string.api_error_local_http_disabled)
        message.contains("Role must be in [user, assistant]", ignoreCase = true) ->
            context.getString(R.string.api_error_model_format)
        message.contains("API Key", ignoreCase = true) || message.contains("401") ->
            context.getString(R.string.api_error_check_key)
        message.contains("model", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true) ||
            message.contains("invalid_request", ignoreCase = true) ||
            message.contains("400") ->
            context.getString(R.string.api_error_provider_rejected, message.take(160))
        message.contains("quota", ignoreCase = true) ||
            message.contains("insufficient", ignoreCase = true) ||
            message.contains("balance", ignoreCase = true) ||
            message.contains("402") ||
            message.contains("429") ->
            context.getString(R.string.api_error_quota, message.take(160))
        message.contains("empty", ignoreCase = true) ->
            context.getString(R.string.api_error_empty_response)
        message.contains("untranslated", ignoreCase = true) ->
            context.getString(R.string.api_error_untranslated)
        message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ->
            context.getString(R.string.api_error_timeout)
        message.contains("connect", ignoreCase = true) ||
            message.contains("unreachable", ignoreCase = true) ||
            message.contains("refused", ignoreCase = true) ->
            context.getString(R.string.api_error_connect)
        message.isNotBlank() ->
            context.getString(R.string.api_error_provider, message.take(160))
        else ->
            context.getString(R.string.api_error_test_failed)
    }
}

private fun localizedApiError(context: Context, message: String): String = when (message) {
    "API 地址不能为空" -> context.getString(R.string.api_error_url_empty)
    "API 地址格式无效" -> context.getString(R.string.api_error_url_invalid)
    "API 地址必须使用 https:// 或 http://" -> context.getString(R.string.api_error_url_scheme)
    "API 地址必须包含有效的服务器地址" -> context.getString(R.string.api_error_url_host)
    "API 地址不能包含用户名或密码" -> context.getString(R.string.api_error_url_userinfo)
    "API 地址不能包含 # 片段" -> context.getString(R.string.api_error_url_fragment)
    "API 地址端口无效" -> context.getString(R.string.api_error_url_port)
    "API 地址必须指向 Chat Completions 接口，例如 /v1/chat/completions" ->
        context.getString(R.string.api_error_url_path)
    "HTTP 仅允许数字形式的电脑私有局域网地址；公网或主机名请使用 HTTPS" ->
        context.getString(R.string.api_error_http_private)
    "模型名称不能为空" -> context.getString(R.string.api_error_model_empty)
    else -> message
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
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val customBackendNote = context.getString(R.string.api_compat_note)
    val backendOptions = remember(customBackendNote) {
        listOf(
            BackendOption(SettingsRepository.BACKEND_DEEPSEEK),
            BackendOption(SettingsRepository.BACKEND_ZHIPU),
            BackendOption(SettingsRepository.BACKEND_QWEN),
            BackendOption(SettingsRepository.BACKEND_GPT),
            BackendOption(SettingsRepository.BACKEND_GEMINI),
            BackendOption(SettingsRepository.BACKEND_CLAUDE),
            BackendOption(
                SettingsRepository.BACKEND_CUSTOM_OPENAI,
                customBackendNote
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
            ) { context.getString(R.string.api_error_temperature_range) }
        }
        if (effectiveTopPEnabled) {
            require(
                topP != null &&
                    topP in SettingsRepository.MIN_API_TOP_P..SettingsRepository.MAX_API_TOP_P
            ) { context.getString(R.string.api_error_top_p_range) }
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
                saveMessage = context.getString(R.string.api_saved)
                saveMessageIsError = false
                val savedBackend = selectedBackend
                scope.launch {
                    appAnalytics.reportBackendType(savedBackend)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveMessage = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.api_save_failed)
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
                    context = context,
                    status = context.getString(R.string.api_success),
                    durationMs = elapsedMs,
                    result = if (testResult.ignoredSamplingParameters.isNotEmpty()) {
                        val ignoredLabels = testResult.ignoredSamplingParameters.joinToString("、") {
                            when (it) {
                                "temperature" -> "Temperature"
                                "top_p" -> "Top-p"
                                "do_sample" -> context.getString(R.string.api_sampling_switches)
                                else -> it
                            }
                        }
                        context.getString(R.string.api_ignored_params, ignoredLabels)
                    } else {
                        context.getString(R.string.api_ready)
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
                    context = context,
                    status = context.getString(R.string.api_failed),
                    durationMs = elapsedMs,
                    result = apiTestFailureResult(context, e)
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
                title = { Text(stringResource(R.string.api_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.api_back), color = MaterialTheme.colorScheme.primary)
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
                    Text(stringResource(R.string.api_provider), style = MaterialTheme.typography.titleMedium)
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
                                    label = apiBackendDisplayName(context, option.value),
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
                    Text(stringResource(R.string.api_request_settings), style = MaterialTheme.typography.titleMedium)
                    if (isQwenBackend) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.api_site), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = qwenSite == SettingsRepository.QWEN_SITE_CHINA,
                                    onClick = { selectQwenSite(SettingsRepository.QWEN_SITE_CHINA) },
                                    label = { Text(stringResource(R.string.api_site_china)) }
                                )
                                FilterChip(
                                    selected = qwenSite == SettingsRepository.QWEN_SITE_INTERNATIONAL,
                                    onClick = {
                                        selectQwenSite(SettingsRepository.QWEN_SITE_INTERNATIONAL)
                                    },
                                    label = { Text(stringResource(R.string.api_site_international)) }
                                )
                            }
                            Text(
                                stringResource(R.string.api_site_key_note),
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
                            label = { Text(stringResource(R.string.api_address)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            supportingText = {
                                Text(stringResource(R.string.api_address_https_note))
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
                                    stringResource(R.string.api_local_http_warning)
                                } else {
                                    stringResource(R.string.api_http_address_hint)
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
                        label = { Text(stringResource(R.string.api_model)) },
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
                            Text(stringResource(R.string.api_api_key_required))
                        },
                        singleLine = true
                    )
                    HorizontalDivider()
                    TextButton(
                        onClick = { advancedSamplingExpanded = !advancedSamplingExpanded },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(if (advancedSamplingExpanded) stringResource(R.string.api_advanced_hide) else stringResource(R.string.api_advanced_custom))
                    }
                    if (advancedSamplingExpanded) {
                        Text(
                            samplingRecommendation(context, selectedBackend, apiModel),
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
                                label = { Text(stringResource(R.string.api_sampling_auto_recommended)) }
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
                                label = { Text(stringResource(R.string.api_sampling_custom)) }
                            )
                        }
                        if (!supportsSamplingCustomization) {
                            Text(
                                stringResource(R.string.api_sampling_auto_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else if (samplingMode == SettingsRepository.API_SAMPLING_MODE_CUSTOM) {
                            Text(
                                if (samplingCapabilities.allowsCombinedParameters) {
                                    stringResource(R.string.api_sampling_local_note)
                                } else {
                                    stringResource(R.string.api_sampling_cloud_note)
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
                                            stringResource(R.string.api_temperature_title)
                                        } else {
                                            stringResource(R.string.api_not_supported)
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
                                supportingText = { Text(stringResource(R.string.api_temperature_range)) },
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
                                            stringResource(R.string.api_top_p_title)
                                        } else {
                                            stringResource(R.string.api_not_supported)
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
                                supportingText = { Text(stringResource(R.string.api_top_p_range)) },
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
                            Text(if (testingApi) stringResource(R.string.api_testing) else stringResource(R.string.api_test))
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
                            Text(stringResource(R.string.api_restore_defaults))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { saveSettings() }) {
                            Text(stringResource(R.string.api_apply))
                        }
                    }
                }
            }
        }
    }
}


