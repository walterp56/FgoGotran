package com.fgogotran.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single recognized text line with spatial position and confidence.
 * @property boundingBox pixel coordinates of the line in the input bitmap
 * @property confidence OCR recognition confidence (0.0 – 1.0)
 */
data class OcrTextLine(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float
)

enum class OcrEngineId {
    ML_KIT,
    ML_KIT_CHINESE,
    PADDLE_OCR,
    UNKNOWN
}

/**
 * Complete OCR result for one screenshot.
 * @property lines individual text lines with bounding boxes (for region classification)
 * @property fullText concatenated all-text result (for hashing/dedup and crop fallback)
 * @property engine OCR provider that produced this result
 */
data class OcrResult(
    val lines: List<OcrTextLine>,
    val fullText: String,
    val engine: OcrEngineId
)

internal interface OcrProvider {
    suspend fun warmUp()
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun close()
}

/**
 * User-selectable OCR facade.
 *
 * The accessibility pipeline depends on this class only. Individual engines are
 * swapped behind the same [warmUp] and [recognize] contract so mode-specific OCR
 * handling keeps using the existing [OcrResult] shape.
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "OCR"
    private val providerMutex = Mutex()
    private var activeEngine = ""
    private var activeProvider: OcrProvider? = null

    suspend fun warmUp() {
        try {
            providerMutex.withLock {
                selectedProviderLocked().warmUp()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordOcrFailure(
                level = DiagnosticEventStore.LEVEL_WARNING,
                eventId = "ocr_warmup_failed",
                title = "OCR 预载失败",
                error = e
            )
            throw e
        }
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        return try {
            providerMutex.withLock {
                selectedProviderLocked().recognize(bitmap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordOcrFailure(
                level = DiagnosticEventStore.LEVEL_ERROR,
                eventId = "ocr_recognize_failed",
                title = "OCR 识别失败",
                error = e
            )
            throw e
        }
    }

    private suspend fun selectedProviderLocked(): OcrProvider {
        val requestedEngine = settingsRepository.getOcrEngine()
        val mlKitScript = if (requestedEngine == SettingsRepository.OCR_ENGINE_MLKIT) {
            val gameServer = SettingsRepository.normalizeGameServer(settingsRepository.getGameServer())
            if (gameServer == SettingsRepository.GAME_SERVER_JP) {
                MlKitOcrScript.JAPANESE
            } else {
                MlKitOcrScript.CHINESE
            }
        } else {
            null
        }
        val providerKey = mlKitScript
            ?.let { "${SettingsRepository.OCR_ENGINE_MLKIT}:${it.name}" }
            ?: requestedEngine
        val existingProvider = activeProvider
        if (existingProvider != null && providerKey == activeEngine) {
            return existingProvider
        }

        existingProvider?.close()
        val nextProvider = when (requestedEngine) {
            SettingsRepository.OCR_ENGINE_PADDLE -> PaddleOcrProvider(appContext)
            else -> MlKitOcrProvider(mlKitScript ?: MlKitOcrScript.JAPANESE)
        }
        activeEngine = providerKey
        activeProvider = nextProvider
        val displayName = mlKitScript
            ?.let { "${SettingsRepository.ocrEngineDisplayName(requestedEngine)} (${it.modelLabel})" }
            ?: SettingsRepository.ocrEngineDisplayName(requestedEngine)
        FgoLogger.info(
            tag,
            "OCR engine selected: $displayName"
        )
        return nextProvider
    }

    private fun recordOcrFailure(
        level: String,
        eventId: String,
        title: String,
        error: Exception
    ) {
        diagnosticEventStore.record(
            level = level,
            category = DiagnosticEventStore.CATEGORY_OCR,
            eventId = eventId,
            title = title,
            message = error.message.orEmpty().ifBlank { error::class.java.simpleName },
            mode = activeEngine.ifBlank { "unknown" },
            errorCode = error::class.java.simpleName
        )
    }
}
