package com.fgogotran.story

import kotlin.math.roundToInt

/**
 * Cheap pre-OCR change detector for FGO's fixed name and dialogue regions.
 *
 * Only a signature that reached a successful render may become the baseline.
 * OCR or translation failures therefore keep retrying instead of being hidden by
 * this gate. Any sampled text-mask change triggers OCR immediately, and a short
 * periodic verification bounds the delay if a very small change falls between samples.
 */
internal class StoryOcrVisualGate {
    private var committedSignature: Signature? = null
    private var pendingRecognition: PendingRecognition? = null
    private var nextToken = 1L
    private var unchangedObservedAt = Long.MIN_VALUE

    fun observe(
        scope: StoryOcrVisualScope,
        width: Int,
        height: Int,
        nameBounds: StoryOcrVisualBounds,
        dialogueBounds: StoryOcrVisualBounds,
        pixel: (Int, Int) -> Int,
        now: Long
    ): StoryOcrVisualDecision {
        val signature = sample(
            scope = scope,
            width = width,
            height = height,
            nameBounds = nameBounds,
            dialogueBounds = dialogueBounds,
            pixel = pixel
        )
        val committed = committedSignature

        if (!signature.hasUsableTextMask) {
            unchangedObservedAt = Long.MIN_VALUE
            return scheduleRecognition(signature, "text mask unavailable")
        }
        if (committed == null) {
            unchangedObservedAt = Long.MIN_VALUE
            return scheduleRecognition(signature, "no rendered visual baseline")
        }
        if (!committed.sameGeometry(signature) || !committed.sameTextMask(signature)) {
            unchangedObservedAt = Long.MIN_VALUE
            return scheduleRecognition(signature, "fixed story text regions changed")
        }

        if (unchangedObservedAt == Long.MIN_VALUE) {
            unchangedObservedAt = now
        }
        if (now - unchangedObservedAt >= MAX_UNCHANGED_SKIP_MS) {
            unchangedObservedAt = now
            return scheduleRecognition(signature, "periodic unchanged-scene verification")
        }

        pendingRecognition = null
        return StoryOcrVisualDecision(
            action = StoryOcrVisualAction.SKIP_UNCHANGED,
            recognitionToken = null,
            reason = "fixed story text regions match rendered scene"
        )
    }

    /** Accept the sampled frame only after OCR text was already rendered or a new render succeeded. */
    fun completeRecognition(recognitionToken: Long?, accepted: Boolean) {
        val pending = pendingRecognition ?: return
        if (recognitionToken == null || pending.token != recognitionToken) return
        pendingRecognition = null

        if (accepted && pending.signature.hasUsableTextMask) {
            committedSignature = pending.signature
            unchangedObservedAt = Long.MIN_VALUE
        }
    }

    fun reset() {
        committedSignature = null
        pendingRecognition = null
        unchangedObservedAt = Long.MIN_VALUE
    }

    private fun scheduleRecognition(
        signature: Signature,
        reason: String
    ): StoryOcrVisualDecision {
        val token = nextToken++
        pendingRecognition = PendingRecognition(token, signature)
        return StoryOcrVisualDecision(
            action = StoryOcrVisualAction.RECOGNIZE,
            recognitionToken = token,
            reason = reason
        )
    }

    private fun sample(
        scope: StoryOcrVisualScope,
        width: Int,
        height: Int,
        nameBounds: StoryOcrVisualBounds,
        dialogueBounds: StoryOcrVisualBounds,
        pixel: (Int, Int) -> Int
    ): Signature {
        val step = (height / 1080f * SAMPLE_STEP_REFERENCE_PX)
            .roundToInt()
            .coerceIn(1, MAX_SAMPLE_STEP_PX)
        val normalizedNameBounds = nameBounds.clamp(width, height)
        val normalizedDialogueBounds = dialogueBounds.clamp(width, height)
        val nameMask = sampleRegion(normalizedNameBounds, step, pixel)
        val dialogueMask = sampleRegion(normalizedDialogueBounds, step, pixel)

        return Signature(
            scope = scope,
            width = width,
            height = height,
            step = step,
            nameBounds = normalizedNameBounds,
            dialogueBounds = normalizedDialogueBounds,
            nameMask = nameMask,
            dialogueMask = dialogueMask
        )
    }

    private fun sampleRegion(
        bounds: StoryOcrVisualBounds,
        step: Int,
        pixel: (Int, Int) -> Int
    ): TextMask {
        if (bounds.isEmpty) return TextMask(0, 0, LongArray(0))

        val columns = ((bounds.width + step - 1) / step).coerceAtLeast(1)
        val rows = ((bounds.height + step - 1) / step).coerceAtLeast(1)
        val sampleCapacity = columns * rows
        val words = LongArray((sampleCapacity + Long.SIZE_BITS - 1) / Long.SIZE_BITS)
        var sampleIndex = 0
        var textPixels = 0

        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                if (isLikelyStoryTextPixel(pixel(x, y))) {
                    words[sampleIndex / Long.SIZE_BITS] =
                        words[sampleIndex / Long.SIZE_BITS] or
                            (1L shl (sampleIndex and (Long.SIZE_BITS - 1)))
                    textPixels++
                }
                sampleIndex++
                x += step
            }
            y += step
        }

        return TextMask(sampleIndex, textPixels, words)
    }

    private fun isLikelyStoryTextPixel(color: Int): Boolean {
        val red = color shr 16 and 255
        val green = color shr 8 and 255
        val blue = color and 255
        val spread = maxOf(red, green, blue) - minOf(red, green, blue)
        val whiteText = red >= 170 && green >= 170 && blue >= 170 && spread <= 95
        val redText = red >= 165 && red - maxOf(green, blue) >= 40
        val cyanText = green >= 140 && blue >= 140 && minOf(green, blue) - red >= 35
        // Same yellow-green palette branch the service colour vote uses: without it the sampled
        // mask ignores those glyphs completely and the gate re-schedules OCR on every frame.
        val yellowGreenText = green >= 150 && green - maxOf(red, blue) >= 15
        return whiteText || redText || cyanText || yellowGreenText
    }

    private data class PendingRecognition(
        val token: Long,
        val signature: Signature
    )

    private data class Signature(
        val scope: StoryOcrVisualScope,
        val width: Int,
        val height: Int,
        val step: Int,
        val nameBounds: StoryOcrVisualBounds,
        val dialogueBounds: StoryOcrVisualBounds,
        val nameMask: TextMask,
        val dialogueMask: TextMask
    ) {
        val hasUsableTextMask: Boolean
            get() = nameMask.textPixels + dialogueMask.textPixels >= MIN_SAMPLED_TEXT_PIXELS

        fun sameGeometry(other: Signature): Boolean =
            scope == other.scope &&
                width == other.width &&
                height == other.height &&
                step == other.step &&
                nameBounds == other.nameBounds &&
                dialogueBounds == other.dialogueBounds

        fun sameTextMask(other: Signature): Boolean =
            nameMask.sameBits(other.nameMask) && dialogueMask.sameBits(other.dialogueMask)
    }

    private data class TextMask(
        val sampleCount: Int,
        val textPixels: Int,
        val words: LongArray
    ) {
        fun sameBits(other: TextMask): Boolean =
            sampleCount == other.sampleCount &&
                textPixels == other.textPixels &&
                words.contentEquals(other.words)
    }

    companion object {
        private const val SAMPLE_STEP_REFERENCE_PX = 3f
        private const val MAX_SAMPLE_STEP_PX = 4
        private const val MIN_SAMPLED_TEXT_PIXELS = 6
        private const val MAX_UNCHANGED_SKIP_MS = 1_200L
    }
}

internal enum class StoryOcrVisualScope {
    SEMI_AUTO,
    AUTO
}

internal enum class StoryOcrVisualAction {
    RECOGNIZE,
    SKIP_UNCHANGED
}

internal data class StoryOcrVisualDecision(
    val action: StoryOcrVisualAction,
    val recognitionToken: Long?,
    val reason: String
)

internal data class StoryOcrVisualBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 || height == 0

    fun clamp(screenWidth: Int, screenHeight: Int): StoryOcrVisualBounds =
        StoryOcrVisualBounds(
            left = left.coerceIn(0, screenWidth.coerceAtLeast(0)),
            top = top.coerceIn(0, screenHeight.coerceAtLeast(0)),
            right = right.coerceIn(0, screenWidth.coerceAtLeast(0)),
            bottom = bottom.coerceIn(0, screenHeight.coerceAtLeast(0))
        )
}
