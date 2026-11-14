package com.fgogotran.data

import com.fgogotran.network.ApiEndpointPolicy
import kotlinx.serialization.Serializable

@Serializable
enum class TranslationApiFormat { CHAT_COMPLETIONS, ANTHROPIC_MESSAGES }

enum class TranslationRequestMode { AUTO, MANUAL }

@Serializable
enum class TranslationOutputLimit(val field: String) {
    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens")
}

@Serializable
enum class TranslationReasoning(val value: String?) {
    PROVIDER_DEFAULT(null),
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max")
}

@Serializable
enum class TranslationThinking { RECOMMENDED, PROVIDER_DEFAULT, DISABLED }

@Serializable
data class TranslationRequestOptions(
    val format: TranslationApiFormat = TranslationApiFormat.CHAT_COMPLETIONS,
    val outputLimit: TranslationOutputLimit = TranslationOutputLimit.MAX_TOKENS,
    val outputBudget: Int? = null,
    val reasoning: TranslationReasoning = TranslationReasoning.PROVIDER_DEFAULT,
    val thinking: TranslationThinking = TranslationThinking.RECOMMENDED
) {
    fun validated(): TranslationRequestOptions {
        require(outputBudget == null || outputBudget in 1..MAX_BUDGET) { "API output budget is out of range" }
        return when (format) {
            TranslationApiFormat.CHAT_COMPLETIONS -> this
            TranslationApiFormat.ANTHROPIC_MESSAGES -> copy(
                outputLimit = TranslationOutputLimit.MAX_TOKENS,
                reasoning = TranslationReasoning.PROVIDER_DEFAULT,
                thinking = TranslationThinking.PROVIDER_DEFAULT
            )
        }
    }

    fun disablesThinking(backend: String, model: String): Boolean = when (thinking) {
        TranslationThinking.DISABLED -> true
        TranslationThinking.PROVIDER_DEFAULT -> false
        TranslationThinking.RECOMMENDED -> when (backend) {
            SettingsRepository.BACKEND_ZHIPU -> glmSupportsThinkingToggle(model)
            SettingsRepository.BACKEND_DEEPSEEK -> isDeepSeekV4(model)
            else -> false
        }
    }

    companion object {
        const val MAX_BUDGET = 128_000

        fun isDeepSeekV4(model: String): Boolean =
            model == "deepseek-flash" || model.startsWith("deepseek-v4")

        fun glmSupportsThinkingToggle(model: String): Boolean =
            model.startsWith("glm-4.5") || model.startsWith("glm-4.6") ||
                model == "glm-5" || model.startsWith("glm-5.1") || model.startsWith("glm-5.2")

        fun recommended(backend: String): TranslationRequestOptions = when (backend) {
            SettingsRepository.BACKEND_GPT -> TranslationRequestOptions(
                outputLimit = TranslationOutputLimit.MAX_COMPLETION_TOKENS
            )
            SettingsRepository.BACKEND_GEMINI -> TranslationRequestOptions(reasoning = TranslationReasoning.LOW)
            SettingsRepository.BACKEND_CLAUDE -> TranslationRequestOptions(
                format = TranslationApiFormat.ANTHROPIC_MESSAGES,
                thinking = TranslationThinking.PROVIDER_DEFAULT
            )
            else -> TranslationRequestOptions()
        }
    }
}

data class TranslationApiProfile(
    val backend: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val qwenSite: String,
    val sampling: ApiSamplingSettings,
    val requestOptions: TranslationRequestOptions,
    val requestMode: TranslationRequestMode = TranslationRequestMode.MANUAL
) {
    override fun toString(): String = "TranslationApiProfile(backend=$backend, model=$model)"

    fun validated(): TranslationApiProfile {
        val normalizedBackend = SettingsRepository.normalizeBackend(backend)
        val options = requestOptions.validated()
        val normalizedEndpoint = ApiEndpointPolicy.validateTranslationEndpoint(
            rawUrl = endpoint,
            allowPrivateLanHttp = normalizedBackend == SettingsRepository.BACKEND_CUSTOM_OPENAI &&
                options.format == TranslationApiFormat.CHAT_COMPLETIONS
        ).url
        val normalizedModel = model.trim()
        require(normalizedModel.isNotEmpty()) { "模型名称不能为空" }
        return copy(
            backend = normalizedBackend,
            endpoint = normalizedEndpoint,
            model = normalizedModel,
            apiKey = apiKey.trim(),
            qwenSite = SettingsRepository.normalizeQwenSite(qwenSite),
            sampling = SettingsRepository.normalizeApiSamplingSettings(
                normalizedBackend, normalizedModel, sampling, options.format
            ),
            requestOptions = options
        )
    }

    fun usesProviderEndpoint(): Boolean =
        backend != SettingsRepository.BACKEND_CUSTOM_OPENAI &&
            endpoint.trim().trimEnd('/') == recommendedEndpoint(backend, qwenSite).trimEnd('/')

    fun resolved(): TranslationApiProfile {
        if (requestMode == TranslationRequestMode.MANUAL) {
            if (usesProviderEndpoint()) return validated()
            return copy(
                requestOptions = if (requestOptions.thinking == TranslationThinking.RECOMMENDED) {
                    requestOptions.copy(thinking = TranslationThinking.PROVIDER_DEFAULT)
                } else {
                    requestOptions
                },
                sampling = if (sampling.mode == SettingsRepository.API_SAMPLING_MODE_AUTO) {
                    sampling.copy(mode = SettingsRepository.API_SAMPLING_MODE_PROVIDER_DEFAULT)
                } else {
                    sampling
                }
            ).validated()
        }
        require(backend == SettingsRepository.BACKEND_CUSTOM_OPENAI || usesProviderEndpoint()) {
            "Use Manual request settings for a custom provider endpoint"
        }
        val options = automaticRequestOptions()
        val modelId = model.trim().lowercase()
        val automaticSampling = SettingsRepository.defaultApiSamplingSettings(backend)
        val applySamplingPreset = when (backend) {
            SettingsRepository.BACKEND_DEEPSEEK -> TranslationRequestOptions.isDeepSeekV4(modelId)
            SettingsRepository.BACKEND_ZHIPU -> TranslationRequestOptions.glmSupportsThinkingToggle(modelId)
            SettingsRepository.BACKEND_GPT ->
                SettingsRepository.apiSamplingCapabilities(backend, modelId, options.format).supportsCustomization
            else -> false
        }
        return copy(
            requestOptions = options,
            sampling = if (applySamplingPreset) automaticSampling else automaticSampling.copy(
                mode = SettingsRepository.API_SAMPLING_MODE_PROVIDER_DEFAULT
            )
        ).validated()
    }

    fun automaticRequestOptions(): TranslationRequestOptions {
        val modelId = model.trim().lowercase()
        val defaults = TranslationRequestOptions.recommended(backend)
        return when (backend) {
            SettingsRepository.BACKEND_GPT -> defaults.copy(
                reasoning = if (modelId == "gpt-6-luna" || modelId.startsWith("gpt-6-luna-")) {
                    TranslationReasoning.LOW
                } else {
                    TranslationReasoning.PROVIDER_DEFAULT
                }
            )
            SettingsRepository.BACKEND_GEMINI -> defaults.copy(
                reasoning = if (modelId.startsWith("gemini-3")) TranslationReasoning.LOW
                    else TranslationReasoning.PROVIDER_DEFAULT
            )
            SettingsRepository.BACKEND_ZHIPU -> defaults.copy(
                reasoning = if (modelId.startsWith("glm-5.3")) TranslationReasoning.LOW
                    else TranslationReasoning.PROVIDER_DEFAULT
            )
            else -> defaults
        }
    }

    /** null means no verified restriction for this model or endpoint. */
    fun manualReasoningChoices(): List<TranslationReasoning>? {
        if (!usesProviderEndpoint()) return null
        val modelId = model.trim().lowercase()
        val default = TranslationReasoning.PROVIDER_DEFAULT
        return when {
            backend == SettingsRepository.BACKEND_GPT && modelId.startsWith("gpt-6-luna") ->
                listOf(default, TranslationReasoning.NONE, TranslationReasoning.LOW,
                    TranslationReasoning.MEDIUM, TranslationReasoning.HIGH, TranslationReasoning.XHIGH,
                    TranslationReasoning.MAX)
            backend == SettingsRepository.BACKEND_GPT &&
                (modelId.startsWith("gpt-4") || modelId.startsWith("gpt-3.5")) -> listOf(default)
            backend == SettingsRepository.BACKEND_GEMINI && modelId.startsWith("gemini-3") ->
                listOf(default, TranslationReasoning.MINIMAL, TranslationReasoning.LOW,
                    TranslationReasoning.MEDIUM, TranslationReasoning.HIGH)
            backend == SettingsRepository.BACKEND_DEEPSEEK && TranslationRequestOptions.isDeepSeekV4(modelId) ->
                listOf(default, TranslationReasoning.NONE, TranslationReasoning.LOW,
                    TranslationReasoning.HIGH, TranslationReasoning.MAX)
            backend == SettingsRepository.BACKEND_ZHIPU && modelId.startsWith("glm-4.5") -> listOf(default)
            backend == SettingsRepository.BACKEND_QWEN && modelId == "qwen-flash" -> listOf(default)
            backend == SettingsRepository.BACKEND_ZHIPU && modelId.startsWith("glm-5.3") ->
                listOf(default, TranslationReasoning.LOW, TranslationReasoning.HIGH, TranslationReasoning.MAX)
            else -> null
        }
    }

    fun manualThinkingChoices(): List<TranslationThinking> {
        if (!usesProviderEndpoint()) return TranslationThinking.entries
        val modelId = model.trim().lowercase()
        return when (backend) {
            SettingsRepository.BACKEND_GPT, SettingsRepository.BACKEND_GEMINI,
            SettingsRepository.BACKEND_QWEN -> listOf(TranslationThinking.PROVIDER_DEFAULT)
            SettingsRepository.BACKEND_ZHIPU -> if (modelId.startsWith("glm-5.3")) {
                listOf(TranslationThinking.PROVIDER_DEFAULT)
            } else {
                TranslationThinking.entries
            }
            else -> TranslationThinking.entries
        }
    }

    fun needsCredentialConfirmation(saved: TranslationApiProfile): Boolean =
        apiKey.isNotBlank() && apiKey == saved.apiKey &&
            ApiEndpointPolicy.origin(endpoint) != ApiEndpointPolicy.origin(saved.endpoint)

    fun requireCredentialConfirmation(saved: TranslationApiProfile, confirmedOrigin: String?) {
        require(!needsCredentialConfirmation(saved) || confirmedOrigin == ApiEndpointPolicy.origin(endpoint)) {
            "API key destination changed"
        }
    }

    companion object {
        fun recommendedEndpoint(backend: String, qwenSite: String): String =
            if (backend == SettingsRepository.BACKEND_QWEN) SettingsRepository.defaultQwenBaseUrl(qwenSite)
            else SettingsRepository.defaultApiBaseUrl(backend)
    }
}
