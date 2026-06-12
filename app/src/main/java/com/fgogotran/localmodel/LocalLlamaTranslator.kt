package com.fgogotran.localmodel

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.fgogotran.translation.ChatMessage
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-facing local translation engine backed by llama.cpp's Android runtime.
 */
@Singleton
class LocalLlamaTranslator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: LocalLlamaModelManager
) {
    companion object {
        const val RUNTIME_AVAILABLE = true

        private const val ENGINE_INIT_TIMEOUT_MS = 60_000L
        private const val MODEL_LOAD_TIMEOUT_MS = 120_000L
        private const val TRANSLATION_TIMEOUT_MS = 90_000L
        private const val MAX_LOCAL_GLOSSARY_CHARS = 520
        private const val MAX_LOCAL_TASK_CHARS = 760
    }

    private val tag = "LocalLlamaTranslator"
    private val mutex = Mutex()
    private var engine: InferenceEngine? = null
    private var loadedModelPath: String? = null

    suspend fun translate(messages: List<ChatMessage>): String = mutex.withLock {
        val installed = modelManager.installedModel()
            ?: throw IllegalStateException("请先在翻译接口页面下载或导入本地模型")
        val modelFile = File(installed.filePath)
        require(modelFile.exists() && modelFile.canRead()) {
            "本地模型文件不可读取，请重新下载或导入模型"
        }

        val modelSpec = modelManager.modelSpec(installed.id)
            ?: LocalLlamaModelManager.BUILT_IN_MODELS.first()
        val localEngine = getEngine()

        FgoLogger.info(
            tag,
            "Local llama translate requested: model=${installed.id}, chars=${messages.sumOf { it.content.length }}"
        )

        ensureModelLoaded(localEngine, modelFile.absolutePath)
        resetSession(localEngine)

        val prompt = buildLocalUserPrompt(messages)
        val maxTokens = computeMaxTokens(modelSpec, prompt)
        FgoLogger.debug(
            tag,
            "Local llama prompt prepared: chars=${prompt.length}, sourceChars=${localSourceChars(prompt)}, maxTokens=$maxTokens"
        )
        val raw = try {
            withTimeout(TRANSLATION_TIMEOUT_MS) {
                localEngine.sendUserPrompt(prompt, maxTokens)
                    .toList()
                    .joinToString("")
            }
        } catch (e: TimeoutCancellationException) {
            FgoLogger.warn(tag, "Local llama translation timed out", e)
            throw IllegalStateException("本地模型翻译超时，请尝试更小的模型或使用云端翻译")
        }

        val cleaned = cleanGeneratedText(raw)
        require(cleaned.isNotBlank()) { "本地模型返回空结果" }
        FgoLogger.info(tag, "Local llama translation complete: chars=${cleaned.length}")
        cleaned
    }

    private fun getEngine(): InferenceEngine {
        engine?.let { return it }
        return AiChat.getInferenceEngine(context).also {
            engine = it
        }
    }

    private suspend fun ensureModelLoaded(localEngine: InferenceEngine, modelPath: String) {
        waitForReadyOrError(localEngine)
        if (loadedModelPath == modelPath && localEngine.state.value.isModelLoaded) {
            return
        }

        resetLoadedState(localEngine)
        try {
            withTimeout(MODEL_LOAD_TIMEOUT_MS) {
                FgoLogger.info(tag, "Loading local llama model: $modelPath")
                localEngine.loadModel(modelPath)
            }
            loadedModelPath = modelPath
            FgoLogger.info(tag, "Local llama model loaded")
        } catch (e: TimeoutCancellationException) {
            loadedModelPath = null
            throw IllegalStateException("本地模型加载超时，请尝试更小的模型")
        } catch (e: Exception) {
            loadedModelPath = null
            FgoLogger.warn(tag, "Local llama model load failed", e)
            throw e
        }
    }

    private suspend fun waitForReadyOrError(localEngine: InferenceEngine) {
        val state = withTimeout(ENGINE_INIT_TIMEOUT_MS) {
            localEngine.state.first {
                it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
            }
        }
        if (state is InferenceEngine.State.Error) {
            throw state.exception
        }
    }

    private fun resetLoadedState(localEngine: InferenceEngine) {
        val state = localEngine.state.value
        if (state.isModelLoaded || state is InferenceEngine.State.Error) {
            runCatching { localEngine.cleanUp() }
                .onFailure { FgoLogger.warn(tag, "Local llama cleanup failed", it) }
        }
    }

    private suspend fun resetSession(localEngine: InferenceEngine) {
        try {
            localEngine.resetConversation()
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Local llama session reset failed", e)
            throw e
        }
    }

    private fun buildLocalUserPrompt(messages: List<ChatMessage>): String {
        val glossary = messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }
            .let(::extractLocalGlossary)

        val userTask = messages
            .filterNot { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }
            .ifBlank { messages.joinToString("\n\n") { it.content } }
            .let(::trimLocalTask)

        return buildString {
            appendLine("JP->Simplified Chinese for Fate/Grand Order.")
            appendLine("Output only the translation. Do not explain or ask for text.")
            appendLine("Keep __FGOTERM_n__ and __FGOPLAYER_n__ unchanged. Person suffix さん => 桑.")
            if (glossary.isNotBlank()) {
                appendLine()
                appendLine("Glossary:")
                appendLine(glossary)
            }
            appendLine()
            appendLine("Source:")
            append(userTask)
        }
    }

    private fun extractLocalGlossary(systemRules: String): String {
        if (systemRules.isBlank()) return ""
        val glossaryIndex = systemRules.indexOf("=== OFFICIAL TERMINOLOGY ===")
        if (glossaryIndex < 0) return ""
        return systemRules
            .substring(glossaryIndex)
            .lineSequence()
            .drop(1)
            .map(String::trim)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_LOCAL_GLOSSARY_CHARS)
    }

    private fun trimLocalTask(task: String): String {
        val trimmed = task
            .replace("Keep __FGOTERM_n__ and __FGOPLAYER_n__ placeholders unchanged exactly.", "")
            .trim()
        if (trimmed.length <= MAX_LOCAL_TASK_CHARS) return trimmed
        return trimmed.take(MAX_LOCAL_TASK_CHARS).trimEnd()
    }

    private fun computeMaxTokens(modelSpec: LocalLlamaModelSpec, prompt: String): Int {
        val promptNeedsJson = prompt.contains("JSON", ignoreCase = true)
        val sourceChars = localSourceChars(prompt)
        val estimated = if (promptNeedsJson) {
            sourceChars * 2 + 40
        } else {
            sourceChars * 2 + 20
        }
        val lowerBound = if (promptNeedsJson) 48 else 32
        val upperBound = if (promptNeedsJson) 128 else 72
        return estimated
            .coerceIn(lowerBound, upperBound)
            .coerceAtMost(modelSpec.maxTokens)
    }

    private fun localSourceChars(prompt: String): Int {
        return prompt
            .substringAfter("Source:", prompt)
            .count { !it.isWhitespace() }
    }

    private fun cleanGeneratedText(text: String): String {
        return text
            .substringBefore("<|im_end|>")
            .substringBefore("<|endoftext|>")
            .substringBefore("<|end|>")
            .replace("<|im_start|>assistant", "")
            .replace("<|im_start|>", "")
            .replace("<|assistant|>", "")
            .trim()
            .removePrefix("assistant")
            .trimStart(':', '：', '\n', '\r', ' ')
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
