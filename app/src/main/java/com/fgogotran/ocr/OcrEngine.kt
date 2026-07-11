package com.fgogotran.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * Complete OCR result for one screenshot.
 * @property lines individual text lines with bounding boxes (for region classification)
 * @property fullText concatenated all-text result (for hashing/dedup and crop fallback)
 */
data class OcrResult(
    val lines: List<OcrTextLine>,
    val fullText: String
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
    private val settingsRepository: SettingsRepository
) {
    private val tag = "OCR"
    private val providerMutex = Mutex()
    private var activeEngine = ""
    private var activeProvider: OcrProvider? = null

    suspend fun warmUp() {
        providerMutex.withLock {
            selectedProviderLocked().warmUp()
        }
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        return providerMutex.withLock {
            selectedProviderLocked().recognize(bitmap)
        }
    }

    private suspend fun selectedProviderLocked(): OcrProvider {
        val requestedEngine = settingsRepository.getOcrEngine()
        val existingProvider = activeProvider
        if (existingProvider != null && requestedEngine == activeEngine) {
            return existingProvider
        }

        existingProvider?.close()
        val nextProvider = when (requestedEngine) {
            SettingsRepository.OCR_ENGINE_PADDLE -> PaddleOcrProvider(appContext)
            else -> MlKitOcrProvider()
        }
        activeEngine = requestedEngine
        activeProvider = nextProvider
        FgoLogger.info(
            tag,
            "OCR engine selected: ${SettingsRepository.ocrEngineDisplayName(requestedEngine)}"
        )
        return nextProvider
    }
}
