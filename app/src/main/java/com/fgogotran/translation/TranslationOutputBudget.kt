package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.TranslationApiFormat
import com.fgogotran.data.TranslationReasoning
import com.fgogotran.data.TranslationRequestOptions

internal object TranslationOutputBudget {
    private const val GLM_STANDARD_LIMIT = 512
    private const val DENSE_CROP_SOURCE_THRESHOLD = 256
    private const val DENSE_CROP_BUDGET = 1024
    private val openAiOModel = Regex("o[1-9](?:-.*)?")
    private val openAiReasoningDefaults = Regex("gpt-5(?:-(?:mini|nano|pro|\\d{4}-\\d{2}-\\d{2}))?")

    fun resolve(
        backend: String,
        model: String,
        options: TranslationRequestOptions,
        taskBudget: Int,
        promptKind: String,
        cropSourceChars: Int?,
        isSubtitle: Boolean
    ): Int {
        options.outputBudget?.let { return it }
        if (isSubtitle) return taskBudget

        val cropBudget = if (cropSourceChars != null && cropSourceChars > DENSE_CROP_SOURCE_THRESHOLD) {
            maxOf(taskBudget, DENSE_CROP_BUDGET)
        } else {
            taskBudget
        }
        if (usesReasoning(backend, model, options)) {
            val reasoningBudget = when (promptKind) {
                "api_test", "voice_hint", "utility" -> 512
                "scene", "scene_with_voice_hint", "dialogue_with_voice_hint" -> 1536
                else -> 1024
            }
            return maxOf(cropBudget, reasoningBudget)
        }

        // Keep the existing GLM ceiling for regular requests. Crop scales with the selected text.
        return if (backend == SettingsRepository.BACKEND_ZHIPU &&
            options.format == TranslationApiFormat.CHAT_COMPLETIONS && cropSourceChars == null
        ) {
            taskBudget.coerceAtMost(GLM_STANDARD_LIMIT)
        } else {
            cropBudget
        }
    }

    private fun usesReasoning(backend: String, model: String, options: TranslationRequestOptions): Boolean {
        if (options.format != TranslationApiFormat.CHAT_COMPLETIONS ||
            options.reasoning == TranslationReasoning.NONE || options.disablesThinking(backend, model)
        ) return false
        if (options.reasoning != TranslationReasoning.PROVIDER_DEFAULT) return true

        val modelId = model.lowercase()
        return when (backend) {
            SettingsRepository.BACKEND_GPT ->
                modelId.startsWith("gpt-6") || openAiOModel.matches(modelId) ||
                    modelId.startsWith("gpt-5.5") || modelId.startsWith("gpt-5.6") ||
                    openAiReasoningDefaults.matches(modelId)
            SettingsRepository.BACKEND_GEMINI -> modelId.startsWith("gemini-3")
            else -> false
        }
    }
}
