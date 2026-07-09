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
    private val choiceSlotLayouts = listOf(
        listOf(choiceSlot(343f, 478f)),
        listOf(choiceSlot(250f, 385f), choiceSlot(437f, 572f)),
        listOf(choiceSlot(156f, 291f), choiceSlot(343f, 478f), choiceSlot(531f, 666f)),
        listOf(choiceSlot(36f, 171f), choiceSlot(214f, 349f), choiceSlot(392f, 527f), choiceSlot(571f, 706f)),
        listOf(choiceSlot(14f, 149f), choiceSlot(158f, 293f), choiceSlot(302f, 437f), choiceSlot(447f, 582f), choiceSlot(591f, 726f))
    )
    // OCR starts inside the nameplate arrow; rendering keeps the original plate alignment.
    private val nameOcrRegion = RectF(32f, 739f, 1172f, 821f)
    private val nameRenderRegion = RectF(0f, 735f, 1085f, 828f)
    private val dialogueRegion = RectF(106f, 833f, 1805f, 1052f)
    private val dialogueRenderRegion = RectF(35f, 830f, 1810f, 1055f)
    private val dialogueCompleteRegion = RectF(1810f, 945f, 1888f, 1078f)
    private val skipConfirmationNoButtonRegion = RectF(430f, 605f, 785f, 685f)
    private val skipConfirmationYesButtonRegion = RectF(925f, 605f, 1280f, 685f)
    private val skipRegion = RectF(1690f, 10f, 1915f, 112f)

    fun regionsForScreen(screenWidth: Int, screenHeight: Int): FgoScreenRegions {
        val viewport = calculateViewport(screenWidth, screenHeight)
        return FgoScreenRegions(
            viewport = viewport.toRect(),
            dialogue = mapToScreen(dialogueRegion, viewport),
            dialogueRender = mapToScreen(dialogueRenderRegion, viewport),
            dialogueComplete = mapToScreen(dialogueCompleteRegion, viewport),
            skipConfirmationNoButton = mapToScreen(skipConfirmationNoButtonRegion, viewport),
            skipConfirmationYesButton = mapToScreen(skipConfirmationYesButtonRegion, viewport),
            name = mapToScreen(nameOcrRegion, viewport),
            nameRender = mapToScreen(nameRenderRegion, viewport),
            choiceSearch = mapToScreen(choiceSearchRegion, viewport),
            choiceSlotLayouts = choiceSlotLayouts.map { layout ->
                layout.map { slot -> mapToScreen(slot, viewport) }
            },
            skip = mapToScreen(skipRegion, viewport)
        )
    }

    fun viewportForScreen(screenWidth: Int, screenHeight: Int): Rect {
        return calculateViewport(screenWidth, screenHeight).toRect()
    }

    fun viewportScaleForScreen(screenWidth: Int, screenHeight: Int): Float {
        val viewport = calculateViewport(screenWidth, screenHeight)
        return (viewport.height() / REFERENCE_HEIGHT).coerceIn(0.6f, 1.4f)
    }

    private fun choiceSlot(top: Float, bottom: Float): RectF {
        return RectF(choiceSearchRegion.left, top, choiceSearchRegion.right, bottom)
    }

    private fun calculateViewport(screenWidth: Int, screenHeight: Int): RectF {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return RectF(
                0f,
                0f,
                screenWidth.coerceAtLeast(0).toFloat(),
                screenHeight.coerceAtLeast(0).toFloat()
            )
        }
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
    val dialogueRender: Rect,
    val dialogueComplete: Rect,
    val skipConfirmationNoButton: Rect,
    val skipConfirmationYesButton: Rect,
    val name: Rect,
    val nameRender: Rect,
    val choiceSearch: Rect,
    val choiceSlotLayouts: List<List<Rect>>,
    val skip: Rect
)
