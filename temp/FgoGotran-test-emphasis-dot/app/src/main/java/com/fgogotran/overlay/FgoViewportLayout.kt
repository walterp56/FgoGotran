package com.fgogotran.overlay

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Maps FGO's centered 16:9 story layout onto the physical screenshot.
 *
 * FGO keeps story controls inside a centered 1920x1080 canvas on wider screens
 * and fills the remaining horizontal area with extended artwork.
 */
object FgoViewportLayout {
    private const val REFERENCE_WIDTH = 1920f
    private const val REFERENCE_HEIGHT = 1080f
    private const val REFERENCE_ASPECT = REFERENCE_WIDTH / REFERENCE_HEIGHT

    // Reference-space bounds taken from marked 2340x1080 FGO story screenshots.
    // Common 1-2 choice screens start in the middle; rare tall lists expand upward on demand.
    private val choiceSearchRegion = RectF(220f, 220f, 1690f, 730f)
    // OCR starts inside the nameplate arrow; rendering keeps the original plate alignment.
    private val nameOcrRegion = RectF(32f, 739f, 1172f, 821f)
    private val nameRenderRegion = RectF(0f, 735f, 1085f, 825f)
    private val dialogueRegion = RectF(35f, 830f, 1810f, 1055f)
    private val dialogueCompleteRegion = RectF(1810f, 965f, 1888f, 1078f)
    private val skipConfirmationNoButtonRegion = RectF(430f, 605f, 785f, 685f)
    private val skipConfirmationYesButtonRegion = RectF(925f, 605f, 1280f, 685f)
    private val skipRegion = RectF(1690f, 10f, 1915f, 112f)

    fun regionsForScreen(screenWidth: Int, screenHeight: Int): FgoScreenRegions {
        val viewport = calculateViewport(screenWidth, screenHeight)
        return FgoScreenRegions(
            viewport = viewport.toRect(),
            dialogue = mapToScreen(dialogueRegion, viewport),
            dialogueComplete = mapToScreen(dialogueCompleteRegion, viewport),
            skipConfirmationNoButton = mapToScreen(skipConfirmationNoButtonRegion, viewport),
            skipConfirmationYesButton = mapToScreen(skipConfirmationYesButtonRegion, viewport),
            name = mapToScreen(nameOcrRegion, viewport),
            nameRender = mapToScreen(nameRenderRegion, viewport),
            choiceSearch = mapToScreen(choiceSearchRegion, viewport),
            skip = mapToScreen(skipRegion, viewport)
        )
    }

    private fun calculateViewport(screenWidth: Int, screenHeight: Int): RectF {
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
        return if (screenAspect >= REFERENCE_ASPECT) {
            val viewportWidth = screenHeight * REFERENCE_ASPECT
            val left = (screenWidth - viewportWidth) / 2f
            RectF(left, 0f, left + viewportWidth, screenHeight.toFloat())
        } else {
            val viewportHeight = screenWidth / REFERENCE_ASPECT
            val top = (screenHeight - viewportHeight) / 2f
            RectF(0f, top, screenWidth.toFloat(), top + viewportHeight)
        }
    }

    private fun mapToScreen(referenceRect: RectF, viewport: RectF): Rect {
        val scaleX = viewport.width() / REFERENCE_WIDTH
        val scaleY = viewport.height() / REFERENCE_HEIGHT
        return Rect(
            (viewport.left + referenceRect.left * scaleX).roundToInt(),
            (viewport.top + referenceRect.top * scaleY).roundToInt(),
            (viewport.left + referenceRect.right * scaleX).roundToInt(),
            (viewport.top + referenceRect.bottom * scaleY).roundToInt()
        )
    }

    private fun RectF.toRect(): Rect {
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt(),
            bottom.roundToInt()
        )
    }
}

data class FgoScreenRegions(
    val viewport: Rect,
    val dialogue: Rect,
    val dialogueComplete: Rect,
    val skipConfirmationNoButton: Rect,
    val skipConfirmationYesButton: Rect,
    val name: Rect,
    val nameRender: Rect,
    val choiceSearch: Rect,
    val skip: Rect
)
