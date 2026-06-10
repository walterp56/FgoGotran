package com.fgogotran.runner

import android.content.Context
import android.graphics.Rect
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.fgogotran.R
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.crop.CropModeState
import com.fgogotran.crop.CropSelectionOverlay
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.ui.overlay.FloatingButton
import com.fgogotran.ui.overlay.HistoryOverlayPanel
import com.fgogotran.ui.overlay.FloatingMenu
import com.fgogotran.util.FakeComposeHost
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.overlayType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the draggable floating button overlay window.
 *
 * Like FGA's [ScriptRunnerOverlay], this class:
 * - Adds a [androidx.compose.ui.platform.ComposeView] to the WindowManager
 * - Renders the [FloatingButton] composable inside it
 * - Handles drag-to-reposition via [onDrag]
 * - Requests translation on tap and shows the popup [FloatingMenu] on long press
 * - Persists button position via DataStore
 *
 * The overlay uses [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * with FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE so that all touch events
 * pass through to FGO underneath.
 */
@Singleton
class FgoRunnerOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cropSelectionOverlay: CropSelectionOverlay
) {
    private var windowManager: WindowManager? = null
    private var composeHost: FakeComposeHost? = null
    private var historyHost: FakeComposeHost? = null
    private var floatingMenuDialog: androidx.appcompat.app.AlertDialog? = null
    private var onCloseRequested: (() -> Unit)? = null
    private var shown = false
    private var cropModeState = CropModeState.IDLE

    /**
     * Current position of the floating button (top-left origin).
     * Saved to DataStore when the button is hidden, restored on show.
     */
    private var btnX = 8
    private var btnY = 300  // Default: near the left edge, offset down a bit

    private val tag = "FgoRunnerOverlay"

    /** Layout params for the floating button overlay. */
    private val btnLayoutParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = overlayType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = btnX
            y = btnY
        }

    private val historyLayoutParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = overlayType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    /** Must be called before [show]. Initializes the WindowManager. */
    fun init(onCloseRequested: () -> Unit) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.onCloseRequested = onCloseRequested
        FgoLogger.info(tag, "Overlay initialized")
    }

    /** Shows the floating button on screen. No-op if already showing. */
    fun show() {
        if (shown) return
        val wm = windowManager ?: return

        if (!Settings.canDrawOverlays(context)) {
            FgoLogger.warn(tag, "Overlay permission not granted, cannot show button")
            return
        }

        // Create the ComposeView hosting the floating button
        composeHost = FakeComposeHost(context) {
            FloatingButton(
                onClick = {
                    onButtonClick()
                },
                onLongClick = { onButtonLongClick() },
                onDrag = { dx, dy -> onDrag(dx, dy) }
            )
        }

        wm.addView(composeHost!!.view, btnLayoutParams)
        shown = true
        FgoLogger.info(tag, "Floating button shown at ($btnX, $btnY)")
    }

    /** Hides the floating button and saves its position. */
    fun hide() {
        if (!shown) return
        val wm = windowManager ?: return
        cancelCropMode()

        composeHost?.let {
            try { wm.removeView(it.view) } catch (_: Exception) {}
            it.close()
        }
        composeHost = null
        shown = false
        FgoLogger.info(tag, "Floating button hidden")
    }

    /** Cleans up everything (button + dialog). Called on service destroy. */
    fun destroy() {
        dismissMenu()
        dismissHistoryPanel()
        cancelCropMode()
        FgoAccessibilityService.instance?.clearCropTranslationOverlay()
        hide()
        windowManager = null
        onCloseRequested = null
        FgoLogger.info(tag, "Overlay destroyed")
    }

    /** Whether the floating button is currently visible. */
    fun isShowing(): Boolean = shown

    private fun onButtonClick() {
        if (cropModeState == CropModeState.SELECTING) {
            requestOneShotCropTranslation()
            return
        }

        if (!TranslationTrigger.isAutoTranslateEnabled()) {
            val requested = FgoAccessibilityService.instance
                ?.requestManualTranslation()
                ?: false
            if (!requested) {
                TranslationTrigger.requestTranslation()
            }
        }
    }

    // ─── Drag handling ────────────────────────────────────────────────

    /**
     * Moves the button by (dx, dy) and clamps to screen bounds.
     * Called from [FloatingButton.onDrag].
     */
    private fun onDrag(dx: Float, dy: Float) {
        val wm = windowManager ?: return
        val view = composeHost?.view ?: return

        btnX = (btnX + dx.toInt()).coerceAtLeast(0)
        btnY = (btnY + dy.toInt()).coerceAtLeast(0)

        // Clamp to screen bounds accounting for the button's measured size
        val bounds = wm.currentWindowMetrics.bounds
        val maxX = bounds.width() - (view.measuredWidth.coerceAtLeast(1))
        val maxY = bounds.height() - (view.measuredHeight.coerceAtLeast(1))
        btnX = btnX.coerceAtMost(maxX.coerceAtLeast(0))
        btnY = btnY.coerceAtMost(maxY.coerceAtLeast(0))

        btnLayoutParams.x = btnX
        btnLayoutParams.y = btnY
        wm.updateViewLayout(view, btnLayoutParams)
    }

    // ─── Menu handling ─────────────────────────────────────────────────

    /** Called when the user holds the floating button (not drags). */
    private fun onButtonLongClick() {
        if (floatingMenuDialog?.isShowing == true) {
            floatingMenuDialog?.dismiss()
        }
        floatingMenuDialog = showMenuDialog()
    }

    /**
     * Creates and shows the white popup menu as an overlay AlertDialog.
     *
     * Uses [FakeComposeHost] to render the [FloatingMenu] composable
     * inside the dialog, so it has the correct plain white style.
     */
    private fun showMenuDialog(): androidx.appcompat.app.AlertDialog {
        TranslationTrigger.setMenuVisible(true)
        val menuHost = FakeComposeHost(context) {
            FloatingMenu(
                autoTranslateEnabled = TranslationTrigger.isAutoTranslateEnabled(),
                onAutoTranslateChange = { enabled ->
                    val accessibility = FgoAccessibilityService.instance
                    if (accessibility != null) {
                        accessibility.setAutoTranslationEnabled(enabled)
                    } else {
                        TranslationTrigger.setAutoTranslateEnabled(enabled)
                    }
                    dismissMenu()
                },
                onCropTranslateClick = {
                    dismissMenu()
                    armOneShotCropMode()
                },
                onHistoryClick = {
                    dismissMenu()
                    showHistoryPanel()
                },
                onCloseClick = { requestClose() }
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context, R.style.Theme_FgoGotran_Dialog)
            .setView(menuHost.view)
            .create()

        dialog.window?.setType(overlayType)
        // Make the dialog background transparent so only our white card shows
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            TranslationTrigger.setMenuVisible(false)
            if (floatingMenuDialog === dialog) {
                floatingMenuDialog = null
            }
        }
        dialog.show()

        return dialog
    }

    private fun dismissMenu() {
        floatingMenuDialog?.dismiss()
        floatingMenuDialog = null
        TranslationTrigger.setMenuVisible(false)
    }

    private fun requestClose() {
        dismissMenu()
        cancelCropMode()
        FgoAccessibilityService.instance?.clearCropTranslationOverlay()
        FgoLogger.info(tag, "Close requested from floating menu")
        onCloseRequested?.invoke()
    }

    private fun armOneShotCropMode() {
        FgoAccessibilityService.instance?.setAutoTranslationEnabled(false)
            ?: TranslationTrigger.setAutoTranslateEnabled(false)
        FgoAccessibilityService.instance?.clearCropTranslationOverlay()
        TranslationTrigger.cancelPendingTranslation()

        cropModeState = CropModeState.SELECTING
        cropSelectionOverlay.show()
        bringFloatingButtonToFront()
        FgoLogger.info(tag, "One-shot crop mode armed")
    }

    private fun requestOneShotCropTranslation() {
        val bounds = cropSelectionOverlay.selectedBounds()
        cropSelectionOverlay.hide()
        cropModeState = CropModeState.IDLE

        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            FgoLogger.warn(tag, "Crop translation requested without valid bounds")
            return
        }

        val requested = FgoAccessibilityService.instance
            ?.requestCropTranslation(Rect(bounds))
            ?: false
        if (!requested) {
            FgoLogger.warn(tag, "Accessibility service unavailable; crop translation ignored")
        }
    }

    private fun cancelCropMode() {
        cropModeState = CropModeState.IDLE
        cropSelectionOverlay.hide()
    }

    private fun bringFloatingButtonToFront() {
        val wm = windowManager ?: return
        val view = composeHost?.view ?: return
        try {
            wm.removeView(view)
            wm.addView(view, btnLayoutParams)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Failed to bring floating button above crop selector", e)
        }
    }

    private fun showHistoryPanel() {
        val wm = windowManager ?: return
        dismissHistoryPanel()
        TranslationTrigger.setHistoryVisible(true)

        historyHost = FakeComposeHost(context) {
            HistoryOverlayPanel(onDismiss = { dismissHistoryPanel() })
        }
        wm.addView(historyHost!!.view, historyLayoutParams)
        FgoLogger.info(tag, "History panel shown")
    }

    private fun dismissHistoryPanel() {
        val wm = windowManager
        historyHost?.let {
            try { wm?.removeView(it.view) } catch (_: Exception) {}
            it.close()
        }
        historyHost = null
        TranslationTrigger.setHistoryVisible(false)
    }

    // ─── MediaProjection ───────────────────────────────────────────────

    /** Called when a MediaProjection token becomes available. */
    fun onMediaProjectionReady() {
        FgoLogger.info(tag, "MediaProjection token received")
        // The ScreenshotServiceHolder will be wired into FgoAccessibilityService
        // to start using MediaProjection for screenshots
    }
}
