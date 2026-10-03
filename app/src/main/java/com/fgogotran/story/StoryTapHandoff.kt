package com.fgogotran.story

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import com.fgogotran.ocr.FgoStoryTextPalette
import kotlin.math.roundToInt

/**
 * AUTO/semi-auto next-scene intent, not proof that dialogue or choices have finished appearing.
 * Owns only small sampled choice masks, never a bitmap, capture loop or coroutine.
 */
internal class StoryTapHandoff(
    private val baselineTimeoutMs: Long,
    private val transitionTimeoutMs: Long = 30_000L
) {
    enum class Kind { DIALOGUE, CHOICES }
    enum class ChoiceAction { WAIT_OLD, WAIT_SETTLE, RECOGNIZE }

    internal class RenderedScene(
        val fingerprint: String,
        val width: Int,
        val height: Int,
        val kind: Kind,
        val choiceHitBounds: List<FgoReferenceRect>,
        val choices: ChoiceFrame?
    )

    private var renderedScene: RenderedScene? = null
    private var pendingScene: RenderedScene? = null
    private var pendingUntil = 0L
    private var baselineUntil = 0L
    private var choicesDeparted = false
    private var absentFrames = 0
    private var choiceCandidate: ChoiceFrame? = null
    private var touchMayAdvance = false
    private var downX = 0f
    private var downY = 0f
    private var touchSlopSquared = 0f
    /** Monotonic commits, not distinct text: a new list may repeat the previous options. */
    var autoRenderGeneration = 0L
        private set

    init {
        require(baselineTimeoutMs > 0L && transitionTimeoutMs >= baselineTimeoutMs)
    }

    fun onSceneRendered(
        fingerprint: String,
        width: Int,
        height: Int,
        kind: Kind?,
        choiceHitBounds: List<FgoReferenceRect> = emptyList(),
        choices: ChoiceFrame? = null,
        autoRenderCommitted: Boolean = false
    ) {
        finishPending()
        cancelTouch()
        renderedScene = if (kind != null && fingerprint.isNotBlank() && width > 0 && height > 0 &&
            (kind != Kind.CHOICES || (choiceHitBounds.isNotEmpty() && choices != null))
        ) {
            RenderedScene(fingerprint, width, height, kind, choiceHitBounds.toList(), choices)
        } else {
            null
        }
        if (autoRenderCommitted) autoRenderGeneration++
    }

    fun touchDown(x: Float, y: Float, slop: Float) {
        downX = x
        downY = y
        touchSlopSquared = slop.coerceAtLeast(0f).let { it * it }
        touchMayAdvance = x.isFinite() && y.isFinite()
    }

    fun touchMove(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        if (!x.isFinite() || !y.isFinite() || dx * dx + dy * dy > touchSlopSquared) {
            cancelTouch()
        }
    }

    fun cancelTouch() {
        touchMayAdvance = false
    }

    /** Consume only our advance hint. The service still forwards the original tap normally. */
    fun takeTap(x: Float, y: Float): RenderedScene? {
        touchMove(x, y)
        val simpleTap = touchMayAdvance
        cancelTouch()
        val scene = renderedScene ?: return null
        if (!simpleTap || x < 0f || y < 0f || x >= scene.width || y >= scene.height) return null
        if (scene.kind == Kind.CHOICES && scene.choiceHitBounds.none {
                x >= it.left && x < it.right && y >= it.top && y < it.bottom
            }) return null
        if (CONTROL_REGIONS.any { reference ->
                val bounds = FgoViewportGeometry.map(reference, scene.width, scene.height)
                x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
            }) return null
        return scene
    }

    /** A render/mode/foreground change invalidates a ticket while gesture replay is suspended. */
    fun beginAfterReplay(scene: RenderedScene, now: Long): Boolean {
        if (scene !== renderedScene) return false
        renderedScene = null // The same displayed scene cannot start a second handoff.
        pendingScene = scene
        pendingUntil = now + transitionTimeoutMs
        baselineUntil = now + baselineTimeoutMs
        return true
    }

    /** Keep suspended tap work tied to its displayed scene, including after replay starts. */
    fun ownsScene(scene: RenderedScene): Boolean = scene === renderedScene || scene === pendingScene

    fun isPending(now: Long): Boolean {
        if (pendingUntil != 0L && now >= pendingUntil) finishPending()
        return pendingUntil != 0L
    }

    fun keepDialogueBaseline(now: Long): Boolean =
        isPending(now) && pendingScene?.kind == Kind.DIALOGUE && now < baselineUntil

    /** Two ordinary captures without panels establish departure, not a single animation frame. */
    fun onChoicesAbsent() {
        choiceCandidate = null
        absentFrames++
        if (absentFrames >= 2) choicesDeparted = true
    }

    fun observeChoices(frame: ChoiceFrame, now: Long): ChoiceAction {
        if (!isPending(now)) return ChoiceAction.WAIT_SETTLE
        absentFrames = 0
        if (!choicesDeparted && frame.isVisiblePartOf(pendingScene?.choices)) {
            choiceCandidate = null
            return ChoiceAction.WAIT_OLD
        }
        if (!frame.hasText || choiceCandidate?.sameText(frame) != true) {
            choiceCandidate = frame
            return ChoiceAction.WAIT_SETTLE
        }
        // Reuse two existing background captures to settle new buttons. Failures can retry;
        // only a successful render (or cancellation/expiry) ends the handoff.
        return ChoiceAction.RECOGNIZE
    }

    fun finishPending() {
        pendingScene = null
        pendingUntil = 0L
        baselineUntil = 0L
        choicesDeparted = false
        absentFrames = 0
        choiceCandidate = null
    }

    fun clear() {
        renderedScene = null
        finishPending()
        cancelTouch()
    }

    companion object {
        fun sampleChoices(
            width: Int,
            height: Int,
            buttons: List<FgoReferenceRect>,
            pixel: (Int, Int) -> Int
        ): ChoiceFrame {
            val step = (minOf(width / 1920f, height / 1080f) * 3f).roundToInt().coerceIn(1, 6)
            val masks = buttons.sortedBy { it.top }.map { button ->
                val bounds = clipped(button, width, height)
                // Ignore the angled end caps and the cyan border/glow. Include upper ruby and
                // the whole main-text width, not just the previous OCR result's glyph boxes.
                val insetX = (bounds.width * 0.035f).roundToInt().coerceAtLeast(2)
                val insetY = (bounds.height * 0.10f).roundToInt().coerceAtLeast(2)
                val inner = FgoReferenceRect(bounds.left + insetX, bounds.top + insetY,
                    bounds.right - insetX, bounds.bottom - insetY)
                val columns = ((inner.width.coerceAtLeast(0) + step - 1) / step)
                // Read every y row so a thin dash or a short upper ruby is not between samples.
                val rows = inner.height.coerceAtLeast(0)
                val words = LongArray((columns * rows + 63) / 64)
                var index = 0
                var ink = 0
                if (inner.width > 0 && inner.height > 0) {
                    for (y in inner.top until inner.bottom) {
                        for (x in inner.left until inner.right step step) {
                            val value = pixel(x, y)
                            if (FgoStoryTextPalette.isChoiceInk((value shr 16) and 255,
                                    (value shr 8) and 255, value and 255)) {
                                words[index / 64] = words[index / 64] or (1L shl (index and 63))
                                ink++
                            }
                            index++
                        }
                    }
                }
                ChoiceMask(bounds, ink, words)
            }
            return ChoiceFrame(width, height, masks)
        }

        private fun clipped(bounds: FgoReferenceRect, width: Int, height: Int) = FgoReferenceRect(
            bounds.left.coerceIn(0, width.coerceAtLeast(0)), bounds.top.coerceIn(0, height.coerceAtLeast(0)),
            bounds.right.coerceIn(0, width.coerceAtLeast(0)), bounds.bottom.coerceIn(0, height.coerceAtLeast(0))
        )

        // Centered 1920x1080 story coordinates, with a small hit-area safety margin.
        // These are tap exclusions only; no OCR/render region is changed.
        private val CONTROL_REGIONS = listOf(
            FgoReferenceRect(1640, 0, 1920, 150), // SKIP.
            FgoReferenceRect(1790, 790, 1920, 950) // LOG and AUTO; the advance diamond remains eligible.
        )
    }

    internal class ChoiceFrame(
        private val width: Int,
        private val height: Int,
        private val masks: List<ChoiceMask>
    ) {
        val hasText: Boolean get() = masks.isNotEmpty() && masks.all { it.ink > 0 }

        fun sameText(other: ChoiceFrame): Boolean =
            width == other.width && height == other.height && masks.size == other.masks.size &&
                masks.indices.all { index ->
                    val a = masks[index]
                    val b = other.masks[index]
                    a.bounds == b.bounds && a.words.contentEquals(b.words)
                }

        /** A selection animation may remove old buttons one at a time. Those are not new rows. */
        fun isVisiblePartOf(old: ChoiceFrame?): Boolean =
            old != null && width == old.width && height == old.height && masks.isNotEmpty() &&
                masks.size <= old.masks.size && masks.all { current ->
                    old.masks.any { previous ->
                        current.bounds == previous.bounds && current.words.contentEquals(previous.words)
                    }
                }
    }

    internal class ChoiceMask(val bounds: FgoReferenceRect, val ink: Int, val words: LongArray)
}
