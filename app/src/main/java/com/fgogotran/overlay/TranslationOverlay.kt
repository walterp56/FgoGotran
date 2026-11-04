package com.fgogotran.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.story.StoryTapHandoff
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.setFloatingHandleGestureExclusion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay

/**
 * Manages the TYPE_ACCESSIBILITY_OVERLAY system windows.
 *
 * The full-screen image overlay displays the rendered translated screenshot.
 * It is created/updated on each pipeline completion and removed when the user leaves FGO.
 *
 * ## Window flags
 * - The translated image receives a tap and asks the accessibility service to forward it to FGO.
 * - FLAG_LAYOUT_IN_SCREEN: overlay fills the entire screen, including behind status/nav bars.
 *
 * ## Lifecycle
 * init() → showTranslatedImage() → updateImage() (repeat) → hideAll() → destroy()
 */
@Singleton
class TranslationOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private var windowManager: WindowManager? = null
    private var overlayView: DialoguePlaybackView? = null
    private var isOverlayShowing = false
    private var overlayTouchable = true
    private var requestedOverlayTouchable = true
    private var controlsVisible = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var latestTranslatedBitmap: Bitmap? = null
    private var renderedChoices: List<ChoiceRenderRegion> = emptyList()
    private var hasNonChoiceContent = true
    private var renderedDialogueBounds: List<Rect> = emptyList()
    private val choiceTapGate = DialoguePlaybackTapGate()
    private var choiceDownIndex = -1
    private var pendingChoiceTap: ChoiceTap? = null
    internal var renderGeneration = 0L
        private set
    internal data class ChoiceTap(val generation: Long, val selectedIndex: Int)
    private var onOverlayTap: ((Float, Float) -> Unit)? = null
    private var onOverlayTouch: ((MotionEvent) -> Boolean)? = null
    private var floatingHandleBounds: Rect? = null
    private var callbacksRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Window metrics can settle after the configuration notification.
            mainHandler.post { refreshScreenBounds() }
        }
        override fun onLowMemory() = Unit
    }

    private val tag = "Overlay"

    companion object {
        // Added after the existing 120ms hold, calibrated to the supplied 30fps recording.
        internal const val DIALOGUE_EXIT_TAIL_MS = 60L
    }

    fun setFloatingHandleBounds(screenBounds: Rect?) {
        floatingHandleBounds = screenBounds?.let { Rect(it) }
        updateFloatingHandleGestureExclusion()
    }

    private fun updateFloatingHandleGestureExclusion() {
        overlayView?.setFloatingHandleGestureExclusion(
            floatingHandleBounds.takeIf { isOverlayShowing && overlayTouchable }
        )
    }

    /**
     * Layout params for the full-screen translated image overlay.
     * TYPE_ACCESSIBILITY_OVERLAY is the correct type for accessibility-service-managed overlays.
     */
    private val overlayParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = screenWidth
            height = screenHeight
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private val overlayPassthroughParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = screenWidth
            height = screenHeight
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    /**
     * Must be called once from the AccessibilityService after onServiceConnected().
     *
     * @param serviceContext the AccessibilityService context — TYPE_ACCESSIBILITY_OVERLAY
     *        requires a WindowManager obtained from the service, not the application
     * @param screenWidth raw screen width in pixels
     * @param screenHeight raw screen height in pixels
     */
    fun init(
        serviceContext: Context,
        screenWidth: Int,
        screenHeight: Int,
        onOverlayTap: (Float, Float) -> Unit,
        onOverlayTouch: ((MotionEvent) -> Boolean)? = null
    ) {
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        this.onOverlayTap = onOverlayTap
        this.onOverlayTouch = onOverlayTouch
        // Must use the service context — Application context has no valid window token
        windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!callbacksRegistered) {
            context.registerComponentCallbacks(componentCallbacks)
            callbacksRegistered = true
        }
        FgoLogger.info(tag, "Overlay initialized: ${screenWidth}x${screenHeight}")
    }

    /** Displays a current-sized rendered bitmap, reusing the native window when possible. */
    fun showTranslatedImage(bitmap: Bitmap) {
        resetChoicePresentation()
        overlayView?.clearPlayback()
        showOrUpdateTranslatedImage(bitmap)
    }

    /** Ownership transfers only after successful presentation. Caller closes rejected frames. */
    internal fun updatePlayback(frame: DialoguePlaybackFrame): Boolean {
        hideOverlayView(clearBitmap = true)
        showOrUpdateTranslatedImage(frame.bitmap)
        if (!isOverlayShowing || latestTranslatedBitmap !== frame.bitmap) return false
        renderedChoices = frame.choices
        renderedDialogueBounds = frame.panels.map { Rect(it.bounds) }
        hasNonChoiceContent = true
        overlayView?.startPlayback(frame)
        if (!overlayTouchable) overlayView?.pausePlayback()
        return true
    }

    internal fun handleDialoguePlaybackTouch(event: MotionEvent): Boolean =
        isOverlayShowing && overlayTouchable && overlayView?.handlePlaybackTouch(event) == true

    private fun resetChoicePresentation() {
        renderGeneration++
        renderedChoices = emptyList()
        hasNonChoiceContent = true
        renderedDialogueBounds = emptyList()
        overlayView?.clearDialogueExitTail()
        choiceTapGate.cancel()
        pendingChoiceTap = null
        choiceDownIndex = -1
        overlayView?.resetChoiceExit()
    }

    private fun trackChoiceTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingChoiceTap = null
                choiceDownIndex = renderedChoices.indexOfFirst {
                    it.hitBounds.contains(event.rawX.toInt(), event.rawY.toInt())
                }
                val eligible = choiceDownIndex >= 0 && !StoryTapHandoff.isControlTap(
                    event.rawX, event.rawY, screenWidth, screenHeight)
                choiceTapGate.down(renderGeneration, event.rawX, event.rawY,
                    ViewConfiguration.get(context).scaledTouchSlop.toFloat(), eligible)
            }
            MotionEvent.ACTION_MOVE -> choiceTapGate.move(event.rawX, event.rawY)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                choiceTapGate.cancel()
                pendingChoiceTap = null
            }
            MotionEvent.ACTION_UP -> {
                pendingChoiceTap = if (choiceTapGate.up(renderGeneration, event.rawX, event.rawY) &&
                    renderedChoices.getOrNull(choiceDownIndex)?.hitBounds
                        ?.contains(event.rawX.toInt(), event.rawY.toInt()) == true
                ) ChoiceTap(renderGeneration, choiceDownIndex) else null
            }
        }
    }

    internal fun takeChoiceTap(): ChoiceTap? = pendingChoiceTap.also { pendingChoiceTap = null }

    internal fun beginChoiceExit(tap: ChoiceTap, gameTapCompletedAtMs: Long): Deferred<Boolean>? {
        val view = overlayView ?: return null
        val bitmap = latestTranslatedBitmap ?: return null
        if (tap.generation != renderGeneration || !isOverlayShowing || controlsVisible ||
            tap.selectedIndex !in renderedChoices.indices || view.hasRetiredChoices()) return null
        val frame = try {
            ChoiceExitFrame.capture(bitmap, renderedChoices, tap.selectedIndex)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Could not retain choice patches; using existing tap handoff", e)
            return null
        }
        val completion = CompletableDeferred<Boolean>()
        renderedChoices = emptyList() // A displayed list can only start one exit.
        pendingChoiceTap = null
        view.startChoiceExit(frame, gameTapCompletedAtMs) { completed ->
            if (renderGeneration == tap.generation) {
                // Never retain an empty input target, including a mixed scene whose base has hidden.
                if (!hasNonChoiceContent) latestTranslatedBitmap = null
                if (!hasNonChoiceContent || view.isBaseHiddenForChoiceExit()) {
                    hideOverlayView(clearBitmap = false)
                }
            }
            completion.complete(completed)
        }
        return completion
    }

    internal fun hideNonChoiceContentDuringExit(generation: Long) {
        if (generation == renderGeneration) overlayView?.hideBaseDuringChoiceExit()
    }

    internal fun cancelChoiceExit(generation: Long) {
        if (generation == renderGeneration) overlayView?.cancelChoiceExit()
    }

    /** Called after the existing read hold and only after a successful game-tap replay. */
    internal suspend fun awaitDialogueExitTail(generation: Long): Boolean {
        if (generation != renderGeneration || !isOverlayShowing || controlsVisible) return false
        val view = overlayView ?: return false
        if (latestTranslatedBitmap == null) return false
        // Choices, including mixed frames, keep their existing complete exit workflow.
        if (renderedChoices.isNotEmpty() || view.hasChoiceExit() || renderedDialogueBounds.isEmpty()) return true
        // Without choices, this story bitmap contains only dialogue and its speaker name.
        // Keep the whole presentation intact so both retire on the same clock.
        view.showDialogueExitTail()
        try {
            delay(DIALOGUE_EXIT_TAIL_MS)
            return generation == renderGeneration && isOverlayShowing && !controlsVisible && view.hasDialogueExitTail()
        } finally {
            if (generation == renderGeneration && view.hasDialogueExitTail()) retireDialogueExitTail()
        }
    }

    /** A tapped-away dialogue must never be restored by a later capture or delayed completion. */
    private fun retireDialogueExitTail() {
        hideOverlayView(clearBitmap = false)
        latestTranslatedBitmap = null
        renderedDialogueBounds = emptyList()
        overlayView?.let {
            it.setImageBitmap(null)
            it.clearPlayback()
            it.clearDialogueExitTail()
        }
    }

    private fun showOrUpdateTranslatedImage(bitmap: Bitmap) {
        val wm = windowManager ?: return
        refreshScreenBounds()
        // A completed render may belong to the screen size from before a rotation/resize.
        // Never stretch it or retain it for a later capture restore.
        if (bitmap.width != screenWidth || bitmap.height != screenHeight) {
            hideOverlayView(clearBitmap = true)
            FgoLogger.debug(tag, "Ignoring old-sized translated image: ${bitmap.width}x${bitmap.height}, screen=${screenWidth}x${screenHeight}")
            return
        }

        overlayView?.let {
            if (!setTranslatedOverlayTouchable(true)) return
            latestTranslatedBitmap = bitmap
            it.setImageBitmap(bitmap)
            it.restoreBaseContent()
            it.visibility = View.VISIBLE
            it.alpha = 1f
            isOverlayShowing = true
            if (overlayTouchable) it.resumePlayback() else it.pausePlayback()
            FgoLogger.info(tag, "Showing translated image: ${bitmap.width}x${bitmap.height}")
            return
        }

        val imageView = DialoguePlaybackView(context).apply {
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFloatingHandleGestureExclusion() }
            setImageBitmap(bitmap)
            alpha = 1f
            scaleType = ImageView.ScaleType.FIT_XY
            setOnTouchListener { _, event ->
                // A queued event from the old input target must not replay through a menu.
                if (controlsVisible) return@setOnTouchListener true
                if (onOverlayTouch?.invoke(event) == true) {
                    choiceTapGate.cancel()
                    pendingChoiceTap = null
                    return@setOnTouchListener true
                }
                trackChoiceTouch(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    FgoLogger.debug(
                        this@TranslationOverlay.tag,
                        "Translated overlay tapped at ${event.rawX},${event.rawY}"
                    )
                    onOverlayTap?.invoke(event.rawX, event.rawY)
                }
                true
            }
        }

        try {
            requestedOverlayTouchable = true
            wm.addView(imageView, if (controlsVisible) overlayPassthroughParams else overlayParams)
        } catch (e: Exception) {
            imageView.setImageBitmap(null)
            isOverlayShowing = false
            overlayTouchable = true
            latestTranslatedBitmap = null
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "translated_overlay_show_failed",
                title = "翻译结果悬浮层显示失败",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                detail = "TYPE_ACCESSIBILITY_OVERLAY addView failed",
                errorCode = e::class.java.simpleName
            )
            FgoLogger.warn(tag, "Failed to show translated overlay", e)
            return
        }
        overlayView = imageView
        latestTranslatedBitmap = bitmap
        isOverlayShowing = true
        overlayTouchable = !controlsVisible
        updateFloatingHandleGestureExclusion()
        FgoLogger.info(tag, "Showing translated image: ${bitmap.width}x${bitmap.height}")
    }

    /**
     * Updates the bitmap of an existing overlay, or creates a new one if not showing.
     * This is the preferred method — it avoids removing/re-adding the window on every frame.
     */
    fun updateImage(bitmap: Bitmap) {
        updateStoryImage(bitmap, emptyList(), hasOtherContent = true)
    }

    internal fun updateStoryImage(
        bitmap: Bitmap,
        choices: List<ChoiceRenderRegion>,
        hasOtherContent: Boolean,
        dialogueBounds: List<Rect> = emptyList()
    ): Boolean {
        resetChoicePresentation()
        overlayView?.clearPlayback()
        showOrUpdateTranslatedImage(bitmap)
        if (!isOverlayShowing || latestTranslatedBitmap !== bitmap) return false
        renderedChoices = choices
        hasNonChoiceContent = hasOtherContent
        renderedDialogueBounds = dialogueBounds.map(::Rect)
        return true
    }

    /** Temporarily hides the translated window during OCR, retaining its rendered image. */
    fun hideForCapture() {
        if (isOverlayShowing) {
            FgoLogger.debug(tag, "Temporarily hiding overlay for OCR capture")
        }
        if (overlayView?.hasDialogueExitTail() == true) retireDialogueExitTail()
        else hideOverlayView(clearBitmap = false)
    }

    fun setTranslatedOverlayTouchable(touchable: Boolean): Boolean {
        requestedOverlayTouchable = touchable
        val effectiveTouchable = touchable && !controlsVisible
        val wm = windowManager ?: return false
        val view = overlayView ?: return false
        val currentParams = view.layoutParams
        if (overlayTouchable == effectiveTouchable && currentParams?.width == screenWidth &&
            currentParams.height == screenHeight) return true
        val params = if (effectiveTouchable) overlayParams else overlayPassthroughParams
        try {
            wm.updateViewLayout(view, params)
            overlayTouchable = effectiveTouchable
            if (!effectiveTouchable) view.pausePlayback() else if (isOverlayShowing) view.resumePlayback()
            updateFloatingHandleGestureExclusion()
            FgoLogger.debug(tag, "Translated overlay touchable=$effectiveTouchable, requested=$touchable, controlsVisible=$controlsVisible")
            return true
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Failed to update translated overlay touchable=$touchable", e)
            return false
        }
    }

    /** Modal controls own input until every menu/history window has closed. The image stays visible. */
    fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        if (visible) {
            choiceTapGate.cancel()
            pendingChoiceTap = null
            overlayView?.cancelChoiceExit()
            if (overlayView?.hasDialogueExitTail() == true) retireDialogueExitTail()
        }
        setTranslatedOverlayTouchable(requestedOverlayTouchable)
    }

    /** Restores the translated window after an OCR check found no source-text change. */
    fun restoreAfterCapture() {
        refreshScreenBounds()
        if (isOverlayShowing) return
        latestTranslatedBitmap?.let {
            FgoLogger.debug(tag, "Restoring unchanged translated overlay")
            showOrUpdateTranslatedImage(it)
        }
    }

    /** Pixel geometry is independent of Android density/font scale. */
    private fun refreshScreenBounds() {
        val bounds = windowManager?.currentWindowMetrics?.bounds ?: return
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0 || (width == screenWidth && height == screenHeight)) return
        screenWidth = width
        screenHeight = height
        // Also clears the image retained by hideForCapture; old geometry must not return.
        hideOverlayView(clearBitmap = true)
        FgoLogger.info(tag, "Translation overlay screen changed: ${width}x${height}")
    }

    /** Hides the full-screen overlay. Safe to call even if nothing is showing. */
    fun hide() {
        hideOverlayView(clearBitmap = true)
    }

    private fun hideOverlayView(clearBitmap: Boolean) {
        if (clearBitmap) resetChoicePresentation() else overlayView?.cancelChoiceExit()
        overlayView?.let {
            it.pausePlayback()
            it.alpha = 0f
            if (clearBitmap) {
                it.setImageBitmap(null)
                it.clearPlayback()
            }
            FgoLogger.info(tag, "Overlay hidden")
        }
        isOverlayShowing = false
        setTranslatedOverlayTouchable(false)
        if (clearBitmap) {
            latestTranslatedBitmap = null
        }
    }

    private fun removeOverlayView() {
        resetChoicePresentation()
        val wm = windowManager ?: return
        overlayView?.let {
            try {
                it.clearPlayback()
                it.setFloatingHandleGestureExclusion(null)
                wm.removeView(it)
                FgoLogger.info(tag, "Overlay hidden")
            } catch (e: Exception) {
                FgoLogger.warn(tag, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        isOverlayShowing = false
        overlayTouchable = true
    }

    /** Hides the full-screen overlay. */
    fun hideAll() {
        if (isOverlayShowing || overlayView != null) {
            FgoLogger.info(tag, "Hiding translation overlay")
        }
        latestTranslatedBitmap = null
        removeOverlayView()
    }

    /** Whether the full-screen overlay is currently displayed. */
    fun isShowing(): Boolean = isOverlayShowing

    /** Removes all overlays and releases the WindowManager reference. */
    fun destroy() {
        if (callbacksRegistered) {
            context.unregisterComponentCallbacks(componentCallbacks)
            callbacksRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        hideAll()
        onOverlayTap = null
        onOverlayTouch = null
        floatingHandleBounds = null
        windowManager = null
        FgoLogger.info(tag, "Overlay destroyed")
    }
}
