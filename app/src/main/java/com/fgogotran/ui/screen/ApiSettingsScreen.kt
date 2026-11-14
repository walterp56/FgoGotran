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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.ui.theme.FgoUiColors
import com.fgogotran.ui.theme.FgoSettingsColors
import com.fgogotran.ui.theme.FgoUiStyle
import com.fgogotran.data.ApiSamplingSettings
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.TranslationApiFormat
import com.fgogotran.data.TranslationApiProfile
import com.fgogotran.data.TranslationOutputLimit
import com.fgogotran.data.TranslationReasoning
import com.fgogotran.data.TranslationRequestMode
import com.fgogotran.data.TranslationRequestOptions
import com.fgogotran.data.TranslationThinking
import com.fgogotran.translation.ApiOutputLimitException
import com.fgogotran.network.ApiEndpointPolicy
import com.fgogotran.translation.Translator
import com.fgogotran.ui.component.BackendProviderLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        error is ApiOutputLimitException -> context.getString(R.string.api_error_output_limit)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApiSettingsScreen(
    settingsRepository: SettingsRepository,
    translator: Translator,
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
    var requestMode by remember { mutableStateOf(TranslationRequestMode.AUTO) }
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
    var savingApi by remember { mutableStateOf(false) }
    var savedProfile by remember { mutableStateOf<TranslationApiProfile?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var requestOptions by remember {
        mutableStateOf(TranslationRequestOptions.recommended(SettingsRepository.BACKEND_DEEPSEEK))
    }
    var outputBudgetText by remember { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val isCustomBackend = selectedBackend == SettingsRepository.BACKEND_CUSTOM_OPENAI
    val isQwenBackend = selectedBackend == SettingsRepository.BACKEND_QWEN
    val samplingCapabilities = SettingsRepository.apiSamplingCapabilities(
        selectedBackend,
        apiModel,
        requestOptions.format
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
        val effectiveMode = if (supportsSamplingCustomization ||
            samplingMode == SettingsRepository.API_SAMPLING_MODE_PROVIDER_DEFAULT
        ) {
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

    fun loadDraft(profile: TranslationApiProfile) {
        selectedBackend = profile.backend
        qwenSite = profile.qwenSite
        apiBaseUrl = profile.endpoint
        apiModel = profile.model
        apiKey = profile.apiKey
        requestOptions = profile.requestOptions
        requestMode = profile.requestMode
        advancedSamplingExpanded = false
        outputBudgetText = profile.requestOptions.outputBudget?.toString().orEmpty()
        applySamplingSettings(profile.sampling)
    }

    LaunchedEffect(Unit) {
        val profile = settingsRepository.apiProfile.first()
        loadDraft(profile)
        savedProfile = profile
        initialized = true
    }

    fun selectBackend(backend: String) {
        if (savingApi || selectedBackend == backend) return
        savedProfile = null
        selectedBackend = backend
        scope.launch {
            val profile = settingsRepository.getApiProfile(backend)
            if (selectedBackend == backend && savedProfile == null) {
                loadDraft(profile)
                savedProfile = profile
            }
        }
    }

    fun selectQwenSite(site: String) {
        qwenSite = SettingsRepository.normalizeQwenSite(site)
        apiBaseUrl = SettingsRepository.defaultQwenBaseUrl(qwenSite)
    }

    fun restoreBackendDefaults() {
        val recommended = settingsRepository.recommendedApiProfile(selectedBackend, qwenSite)
        if (!isCustomBackend) {
            apiBaseUrl = recommended.endpoint
            apiModel = recommended.model
        }
        requestMode = TranslationRequestMode.AUTO
        advancedSamplingExpanded = false
    }

    fun profileDraft(sampling: ApiSamplingSettings, options: TranslationRequestOptions) = TranslationApiProfile(
        backend = selectedBackend,
        endpoint = apiBaseUrl,
        model = apiModel,
        apiKey = apiKey,
        qwenSite = qwenSite,
        sampling = sampling,
        requestOptions = options,
        requestMode = requestMode
    )

    val isManual = requestMode == TranslationRequestMode.MANUAL
    val canUseAuto = isCustomBackend ||
        profileDraft(SettingsRepository.defaultApiSamplingSettings(selectedBackend), requestOptions).usesProviderEndpoint()

    fun draftSignature() = listOf(
        selectedBackend, qwenSite, apiBaseUrl, apiModel, apiKey, requestOptions, outputBudgetText,
        samplingMode, temperatureEnabled, temperatureText, topPEnabled, topPText, requestMode
    )

    val signature = draftSignature()
    LaunchedEffect(signature) {
        saveMessage = ""
        saveMessageIsError = false
    }

    fun withValidatedDraft(onReady: (TranslationApiProfile, String?) -> Unit) {
        val saved = savedProfile ?: return
        try {
            val budget = outputBudgetText.trim().takeIf { it.isNotEmpty() }?.let {
                val value = it.toIntOrNull()
                if (isManual) {
                    require(value != null && value in 1..TranslationRequestOptions.MAX_BUDGET) {
                        context.getString(R.string.api_error_output_budget, TranslationRequestOptions.MAX_BUDGET)
                    }
                }
                value?.takeIf { it in 1..TranslationRequestOptions.MAX_BUDGET }
            }
            val sampling = if (isManual) currentSamplingSettings()
                else runCatching { currentSamplingSettings() }.getOrElse { saved.sampling }
            val draft = profileDraft(sampling, requestOptions.copy(outputBudget = budget))
            val effective = draft.resolved()
            if (effective.needsCredentialConfirmation(saved)) {
                val origin = checkNotNull(ApiEndpointPolicy.origin(effective.endpoint))
                val checkedSignature = draftSignature()
                pendingConfirmation = origin to {
                    if (draftSignature() == checkedSignature) onReady(draft, origin)
                }
            } else {
                onReady(draft, null)
            }
        } catch (e: IllegalArgumentException) {
            saveMessage = localizedApiError(context, e.message.orEmpty())
            saveMessageIsError = true
        }
    }

    fun saveSettings() = withValidatedDraft { profile, confirmedOrigin ->
        val submitted = draftSignature()
        savingApi = true
        scope.launch {
            try {
                settingsRepository.saveApiSettings(profile, confirmedOrigin)
                savedProfile = profile
                if (draftSignature() == submitted) {
                    saveMessage = context.getString(R.string.api_saved)
                    saveMessageIsError = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (draftSignature() == submitted) {
                    saveMessage = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.api_save_failed)
                    saveMessageIsError = true
                }
            } finally {
                savingApi = false
            }
        }
    }

    var testJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(signature) { testJob?.cancel() }

    fun testApi() = withValidatedDraft { profile, confirmedOrigin ->
        val submitted = draftSignature()
        testingApi = true
        saveMessage = ""
        saveMessageIsError = false
        testJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val testResult = translator.testApiSettings(profile, confirmedOrigin)
                if (draftSignature() != submitted) return@launch
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val result = if (testResult.ignoredSamplingParameters.isEmpty()) {
                    context.getString(R.string.api_ready)
                } else {
                    val ignored = testResult.ignoredSamplingParameters.joinToString(", ")
                    context.getString(R.string.api_ignored_params, ignored)
                }
                saveMessage = apiTestMessage(context, context.getString(R.string.api_success), elapsedMs, result)
                saveMessageIsError = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (draftSignature() != submitted) return@launch
                saveMessage = apiTestMessage(
                    context,
                    context.getString(R.string.api_failed),
                    SystemClock.elapsedRealtime() - startedAt,
                    apiTestFailureResult(context, e)
                )
                saveMessageIsError = true
            } finally {
                testingApi = false
            }
        }
    }

    pendingConfirmation?.let { (origin, confirm) ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(stringResource(R.string.api_confirm_destination_title)) },
            text = { Text(stringResource(R.string.api_confirm_destination_body, origin)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirmation = null
                    confirm()
                }) { Text(stringResource(R.string.api_confirm_destination_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = FgoUiColors.topAppBarColors(),
                title = { Text(stringResource(R.string.api_title)) },
                navigationIcon = {
                    TextButton(colors = FgoUiColors.textButtonColors(), onClick = onBack) {
                        Text(stringResource(R.string.api_back), color = FgoUiColors.blueText)
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
                border = FgoUiStyle.cardBorder,
                colors = FgoUiColors.cardColors()
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
                                onClick = { selectBackend(option.value) },
                                enabled = initialized && !savingApi
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
                                        color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (savedProfile == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                border = FgoUiStyle.cardBorder,
                colors = FgoUiColors.cardColors()
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
                                    colors = FgoUiColors.filterChipColors(),
                                    selected = qwenSite == SettingsRepository.QWEN_SITE_CHINA,
                                    onClick = { selectQwenSite(SettingsRepository.QWEN_SITE_CHINA) },
                                    label = { Text(stringResource(R.string.api_site_china)) }
                                )
                                FilterChip(
                                    colors = FgoUiColors.filterChipColors(),
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
                                color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
                            )
                        }
                    }
                    OutlinedTextField(
                        colors = FgoUiColors.outlinedTextFieldColors(),
                        value = apiBaseUrl,
                        onValueChange = {
                            apiBaseUrl = it
                            saveMessage = ""
                            saveMessageIsError = false
                        },
                        label = { Text(stringResource(R.string.api_address)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        readOnly = !isCustomBackend && !isManual,
                        supportingText = {
                            if (isCustomBackend || isManual) Text(stringResource(R.string.api_address_profile_help))
                        },
                        singleLine = isCustomBackend || isManual
                    )
                    if (apiBaseUrl.trim().startsWith("http://", ignoreCase = true)) {
                        val format = if (isManual) requestOptions.format else TranslationApiFormat.CHAT_COMPLETIONS
                        val localEndpointValid = remember(apiBaseUrl, selectedBackend, format) {
                            runCatching {
                                ApiEndpointPolicy.validateTranslationEndpoint(
                                    apiBaseUrl,
                                    isCustomBackend && format == TranslationApiFormat.CHAT_COMPLETIONS
                                )
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
                                FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    OutlinedTextField(
                        colors = FgoUiColors.outlinedTextFieldColors(),
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
                        colors = FgoUiColors.outlinedTextFieldColors(),
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isManual,
                            enabled = canUseAuto,
                            onClick = {
                                requestMode = TranslationRequestMode.AUTO
                                advancedSamplingExpanded = false
                            },
                            label = { Text(stringResource(R.string.api_sampling_auto_recommended)) }
                        )
                        FilterChip(
                            selected = isManual,
                            onClick = {
                                requestMode = TranslationRequestMode.MANUAL
                                advancedSamplingExpanded = true
                            },
                            label = { Text(stringResource(R.string.api_request_manual)) }
                        )
                    }
                    if (!canUseAuto) ApiFieldHelp(stringResource(R.string.api_external_endpoint_manual))
                    TextButton(
                        colors = FgoUiColors.textButtonColors(),
                        onClick = { advancedSamplingExpanded = !advancedSamplingExpanded },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(stringResource(
                            if (advancedSamplingExpanded) R.string.api_advanced_hide
                            else if (isManual) R.string.api_advanced_custom
                            else R.string.api_view_request
                        ))
                    }
                    if (advancedSamplingExpanded && !isManual) {
                        val preview = runCatching {
                            profileDraft(SettingsRepository.defaultApiSamplingSettings(selectedBackend), requestOptions)
                                .resolved()
                        }.getOrNull()
                        preview?.let { profile ->
                            val options = profile.requestOptions
                            val format = if (options.format == TranslationApiFormat.CHAT_COMPLETIONS) {
                                "Chat Completions"
                            } else {
                                "Anthropic Messages"
                            }
                            ApiFieldHelp("$format · ${options.outputLimit.field}", options.outputLimit.field)
                            ApiFieldHelp(stringResource(R.string.api_task_budget_auto))
                            if (options.disablesThinking(profile.backend, profile.model)) {
                                ApiFieldHelp(stringResource(R.string.api_preview_thinking_off))
                            } else if (options.reasoning.value != null) {
                                ApiFieldHelp(
                                    stringResource(R.string.api_preview_reasoning, options.reasoning.value)
                                )
                            }
                            ApiFieldHelp(
                                if (profile.sampling.mode == SettingsRepository.API_SAMPLING_MODE_PROVIDER_DEFAULT) {
                                    stringResource(R.string.api_preview_sampling_default)
                                } else {
                                    samplingRecommendation(context, profile.backend, profile.model)
                                }
                            )
                        } ?: ApiFieldHelp(stringResource(R.string.api_preview_needs_connection))
                    }
                    if (advancedSamplingExpanded && isManual) {
                        ApiOptionPicker(
                            label = stringResource(R.string.api_format),
                            help = stringResource(R.string.api_format_help),
                            selected = requestOptions.format,
                            options = TranslationApiFormat.entries,
                            optionLabel = { if (it == TranslationApiFormat.CHAT_COMPLETIONS) "Chat Completions" else "Anthropic Messages" },
                            onSelect = { requestOptions = requestOptions.copy(format = it) }
                        )
                        if (requestOptions.format == TranslationApiFormat.CHAT_COMPLETIONS) {
                            ApiOptionPicker(
                                label = stringResource(R.string.api_output_field),
                                help = stringResource(R.string.api_output_field_help),
                                code = TranslationOutputLimit.MAX_COMPLETION_TOKENS.field,
                                selected = requestOptions.outputLimit,
                                options = TranslationOutputLimit.entries,
                                optionLabel = { it.field },
                                onSelect = { requestOptions = requestOptions.copy(outputLimit = it) }
                            )
                            val providerDefault = stringResource(R.string.api_provider_default)
                            val manualProfile = profileDraft(
                                SettingsRepository.defaultApiSamplingSettings(selectedBackend), requestOptions
                            )
                            val reasoningChoices = manualProfile.manualReasoningChoices() ?: TranslationReasoning.entries
                            if (reasoningChoices.size > 1 || requestOptions.reasoning !in reasoningChoices) {
                                ApiOptionPicker(
                                    label = stringResource(R.string.api_reasoning_effort),
                                    help = stringResource(R.string.api_reasoning_effort_help),
                                    selected = requestOptions.reasoning,
                                    options = reasoningChoices,
                                    optionLabel = { it.value ?: providerDefault },
                                    onSelect = { requestOptions = requestOptions.copy(reasoning = it) }
                                )
                            }
                            val thinkingChoices = manualProfile.manualThinkingChoices()
                            if (thinkingChoices.size > 1 || requestOptions.disablesThinking(selectedBackend, apiModel)) {
                                val disabled = stringResource(R.string.api_thinking_disabled)
                                val presetLabel = stringResource(R.string.api_request_preset)
                                ApiOptionPicker(
                                    label = stringResource(R.string.api_thinking_setting),
                                    help = stringResource(R.string.api_thinking_help),
                                    selected = requestOptions.thinking,
                                    options = thinkingChoices,
                                    optionLabel = {
                                        when (it) {
                                            TranslationThinking.RECOMMENDED -> presetLabel
                                            TranslationThinking.PROVIDER_DEFAULT -> providerDefault
                                            TranslationThinking.DISABLED -> disabled
                                        }
                                    },
                                    onSelect = { requestOptions = requestOptions.copy(thinking = it) }
                                )
                            }
                        }
                        OutlinedTextField(
                            colors = FgoUiColors.outlinedTextFieldColors(),
                            value = outputBudgetText,
                            onValueChange = { outputBudgetText = it },
                            label = { Text(stringResource(R.string.api_output_budget)) },
                            supportingText = { Text(stringResource(R.string.api_output_budget_help)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        if (requestOptions.format == TranslationApiFormat.CHAT_COMPLETIONS) {
                            HorizontalDivider()
                            val defaultLabel = stringResource(R.string.api_provider_default)
                            val recommendedLabel = stringResource(R.string.api_request_preset)
                            val customLabel = stringResource(R.string.api_sampling_custom)
                            val modes = listOf(
                                SettingsRepository.API_SAMPLING_MODE_AUTO,
                                SettingsRepository.API_SAMPLING_MODE_PROVIDER_DEFAULT
                            ) + if (supportsSamplingCustomization) {
                                listOf(SettingsRepository.API_SAMPLING_MODE_CUSTOM)
                            } else {
                                emptyList()
                            }
                            ApiOptionPicker(
                                label = stringResource(R.string.api_sampling_options),
                                help = stringResource(R.string.api_sampling_help),
                                selected = SettingsRepository.normalizeApiSamplingMode(samplingMode)
                                    .takeIf { it in modes } ?: SettingsRepository.API_SAMPLING_MODE_AUTO,
                                options = modes,
                                optionLabel = {
                                    when (it) {
                                        SettingsRepository.API_SAMPLING_MODE_CUSTOM -> customLabel
                                        SettingsRepository.API_SAMPLING_MODE_PROVIDER_DEFAULT -> defaultLabel
                                        else -> recommendedLabel
                                    }
                                },
                                onSelect = { mode ->
                                    samplingMode = mode
                                    if (mode == SettingsRepository.API_SAMPLING_MODE_CUSTOM &&
                                        !temperatureEnabled && !topPEnabled
                                    ) {
                                        temperatureEnabled = samplingCapabilities.supportsTemperature
                                        topPEnabled = !temperatureEnabled && samplingCapabilities.supportsTopP
                                    }
                                }
                            )
                        }
                        if (supportsSamplingCustomization && samplingMode == SettingsRepository.API_SAMPLING_MODE_CUSTOM) {
                            Text(
                                if (samplingCapabilities.allowsCombinedParameters) {
                                    stringResource(R.string.api_sampling_local_note)
                                } else {
                                    stringResource(R.string.api_sampling_cloud_note)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
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
                                        color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
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
                                colors = FgoUiColors.outlinedTextFieldColors(),
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
                                        color = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)
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
                                colors = FgoUiColors.outlinedTextFieldColors(),
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
                                FgoSettingsColors.value
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
                            colors = FgoUiColors.outlinedButtonColors(),
                            shape = FgoUiStyle.buttonShape,
                            onClick = { testApi() },
                            enabled = !testingApi && !savingApi && savedProfile != null
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
                            colors = FgoUiColors.outlinedButtonColors(),
                            shape = FgoUiStyle.buttonShape,
                            onClick = { restoreBackendDefaults() },
                            enabled = !savingApi && savedProfile != null
                        ) {
                            Text(stringResource(R.string.api_restore_defaults))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            shape = FgoUiStyle.buttonShape,
                            onClick = { saveSettings() },
                            enabled = !testingApi && !savingApi && savedProfile != null
                        ) {
                            Text(stringResource(R.string.api_apply))
                        }
                    }
                }
            }
        }
    }
}



@Composable
private fun <T> ApiOptionPicker(
    label: String,
    help: String,
    selected: T,
    options: List<T>,
    code: String? = null,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(optionLabel(selected)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
        ApiFieldHelp(help, code)
    }
}

@Composable
private fun ApiFieldHelp(text: String, code: String? = null) {
    val styledText = remember(text, code) {
        buildAnnotatedString {
            append(text)
            if (code != null) {
                val start = text.indexOf(code)
                if (start >= 0) {
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, start + code.length)
                }
            }
        }
    }
    androidx.compose.material3.Text(
        text = styledText,
        style = MaterialTheme.typography.bodySmall,
        color = FgoUiColors.text(darkAlpha = 0.7f, secondary = true)
    )
}
