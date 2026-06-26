package com.fgogotran.runner

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fgogotran.R
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.crop.CropModeState
import com.fgogotran.crop.CropSelectionOverlay
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationMode
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.ui.overlay.FloatingButton
import com.fgogotran.ui.overlay.FloatingButtonMode
import com.fgogotran.ui.overlay.HistoryOverlayPanel
import com.fgogotran.ui.overlay.FloatingMenu
import com.fgogotran.util.FakeComposeHost
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.overlayType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the draggable floating button overlay window.
 *
 * This class:
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
    private val cropSelectionOverlay: CropSelectionOverlay,
    private val settingsRepository: SettingsRepository
) {
    private var windowManager: WindowManager? = null
    private var composeHost: FakeComposeHost? = null
    private var historyHost: FakeComposeHost? = null
    private var floatingMenuDialog: androidx.appcompat.app.AlertDialog? = null
    private var onCloseRequested: (() -> Unit)? = null
    private var shown = false
    private var cropModeState = CropModeState.IDLE
    private var modeBeforeCrop: TranslationMode? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buttonMode by mutableStateOf(FloatingButtonMode.MANUAL)
    private var showButtonFailureRing by mutableStateOf(false)
    private var failureFeedbackVersion = 0
    private var buttonPositionScreen: ButtonScreen? = null
    private var buttonPositionLoaded = false
    private var savePositionJob: Job? = null
    private var showRequestVersion = 0
    private var callbacksRegistered = false

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handleScreenBoundsChanged()
        }

        override fun onLowMemory() = Unit
    }

    /**
     * Current position of the floating button (top-left origin).
     * Saved to DataStore when the button is hidden, restored on show.
     */
    private var btnX = SettingsRepository.DEFAULT_FLOATING_BUTTON_X
    private var btnY = SettingsRepository.DEFAULT_FLOATING_BUTTON_Y

    private val tag = "FgoRunnerOverlay"

    private companion object {
        const val FAILURE_FEEDBACK_MS = 1800L
        const val POSITION_SAVE_DEBOUNCE_MS = 300L
        const val FLOATING_BUTTON_SIZE_DP = 54f
    }

    private enum class ButtonScreen {
        PORTRAIT,
        LANDSCAPE
    }

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
        if (!callbacksRegistered) {
            context.registerComponentCallbacks(componentCallbacks)
            callbacksRegistered = true
        }
        FgoLogger.info(tag, "Overlay initialized")
    }

    /** Shows the floating button on screen. No-op if already showing. */
    fun show() {
        if (shown) return

        if (!Settings.canDrawOverlays(context)) {
            FgoLogger.warn(tag, "Overlay permission not granted, cannot show button")
            return
        }

        shown = true
        val requestVersion = ++showRequestVersion
        overlayScope.launch {
            try {
                loadButtonPositionIfNeeded()
                restoreLastTranslationMode()
                if (!shown || requestVersion != showRequestVersion) return@launch

                val wm = windowManager
                if (wm == null) {
                    shown = false
                    return@launch
                }

                refreshButtonMode()
                composeHost = FakeComposeHost(context) {
                    FloatingButton(
                        mode = buttonMode,
                        showFailureRing = showButtonFailureRing && buttonMode != FloatingButtonMode.AUTO,
                        onClick = {
                            onButtonClick()
                        },
                        onLongClick = { onButtonLongClick() },
                        onDrag = { dx, dy -> onDrag(dx, dy) }
                    )
                }

                clampButtonPositionToScreen()
                wm.addView(composeHost!!.view, btnLayoutParams)
                FgoLogger.info(tag, "Floating button shown at ($btnX, $btnY)")
            } catch (e: Exception) {
                composeHost?.close()
                composeHost = null
                shown = false
                FgoLogger.warn(tag, "Failed to show floating button", e)
            }
        }
    }

    private suspend fun restoreLastTranslationMode() {
        val mode = runCatching {
            TranslationMode.valueOf(
                SettingsRepository.normalizeTranslationMode(settingsRepository.getLastTranslationMode())
            )
        }.getOrElse { error ->
            FgoLogger.warn(tag, "Failed to restore runner translation mode; using manual", error)
            TranslationMode.MANUAL
        }
        val accessibility = FgoAccessibilityService.instance
        if (accessibility != null) {
            accessibility.setTranslationMode(mode, persist = false)
        } else {
            TranslationTrigger.setTranslationMode(mode)
            refreshButtonMode()
        }
        FgoLogger.debug(tag, "Runner restored translation mode: $mode")
    }

    /** Hides the floating button and saves its position. */
    fun hide() {
        if (!shown) return
        showRequestVersion += 1
        saveButtonPositionNow()
        val wm = windowManager
        cancelCropMode()

        composeHost?.let {
            try { wm?.removeView(it.view) } catch (_: Exception) {}
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
        if (callbacksRegistered) {
            context.unregisterComponentCallbacks(componentCallbacks)
            callbacksRegistered = false
        }
        windowManager = null
        onCloseRequested = null
        FgoLogger.info(tag, "Overlay destroyed")
    }

    /** Whether the floating button is currently visible. */
    fun isShowing(): Boolean = shown

    fun refreshButtonMode() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateButtonMode()
        } else {
            mainHandler.post { updateButtonMode() }
        }
    }

    fun showTranslationFailureFeedback(fromUserTap: Boolean = true) {
        mainHandler.post {
            if (!fromUserTap || !TranslationTrigger.canUserTapTranslate() || buttonMode == FloatingButtonMode.AUTO) {
                showButtonFailureRing = false
                return@post
            }
            failureFeedbackVersion += 1
            val version = failureFeedbackVersion
            showButtonFailureRing = true
            mainHandler.postDelayed(
                {
                    if (failureFeedbackVersion == version) {
                        showButtonFailureRing = false
                    }
                },
                FAILURE_FEEDBACK_MS
            )
        }
    }

    fun handleInterceptedButtonTap(rawX: Float, rawY: Float): Boolean {
        if (!isPointInsideButton(rawX, rawY)) return false
        FgoLogger.debug(tag, "Translated overlay tap routed to floating button")
        onButtonClick()
        return true
    }

    fun handleInterceptedButtonLongPress(): Boolean {
        if (!shown) return false
        FgoLogger.debug(tag, "Translated overlay long press routed to floating menu")
        onButtonLongClick()
        return true
    }

    fun isPointInsideButton(rawX: Float, rawY: Float): Boolean {
        if (!shown) return false
        val view = composeHost?.view ?: return false
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight
        if (width <= 0 || height <= 0) return false

        val centerX = btnX + width / 2f
        val centerY = btnY + height / 2f
        val radius = minOf(width, height) / 2f
        val dx = rawX - centerX
        val dy = rawY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun onButtonClick() {
        if (cropModeState == CropModeState.SELECTING) {
            requestOneShotCropTranslation()
            return
        }

        if (!TranslationTrigger.canUserTapTranslate()) {
            FgoLogger.debug(tag, "Floating button tap ignored while full auto translation is enabled")
            return
        }

        val requested = FgoAccessibilityService.instance
            ?.requestManualTranslation()
            ?: false
        if (!requested) {
            FgoLogger.debug(tag, "Accessibility service unavailable; queued manual translation")
            TranslationTrigger.requestTranslation()
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
        buttonPositionScreen = currentButtonScreen()

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
        saveButtonPositionSoon()
    }

    // ─── Menu handling ─────────────────────────────────────────────────

    private suspend fun loadButtonPositionIfNeeded(force: Boolean = false) {
        val screen = currentButtonScreen()
        if (!force && buttonPositionLoaded && buttonPositionScreen == screen) return
        val position = settingsRepository.getFloatingButtonPosition(screen == ButtonScreen.LANDSCAPE)
        btnX = position.first
        btnY = position.second
        buttonPositionScreen = screen
        buttonPositionLoaded = true
        clampButtonPositionToScreen()
    }

    private fun currentButtonScreen(): ButtonScreen {
        val bounds = windowManager?.currentWindowMetrics?.bounds
        val width = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val height = bounds?.height() ?: context.resources.displayMetrics.heightPixels
        return if (width >= height) ButtonScreen.LANDSCAPE else ButtonScreen.PORTRAIT
    }

    private fun clampButtonPositionToScreen(buttonWidth: Int? = null, buttonHeight: Int? = null) {
        val wm = windowManager ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val fallbackSize = (FLOATING_BUTTON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
        val width = buttonWidth?.takeIf { it > 0 } ?: fallbackSize
        val height = buttonHeight?.takeIf { it > 0 } ?: fallbackSize
        val maxX = (bounds.width() - width).coerceAtLeast(0)
        val maxY = (bounds.height() - height).coerceAtLeast(0)
        btnX = btnX.coerceIn(0, maxX)
        btnY = btnY.coerceIn(0, maxY)
    }

    private fun saveButtonPositionSoon() {
        val x = btnX
        val y = btnY
        val screen = buttonPositionScreen ?: currentButtonScreen()
        savePositionJob?.cancel()
        savePositionJob = overlayScope.launch {
            delay(POSITION_SAVE_DEBOUNCE_MS)
            saveButtonPosition(screen, x, y)
        }
    }

    private fun saveButtonPositionNow(screen: ButtonScreen = buttonPositionScreen ?: currentButtonScreen()) {
        if (!buttonPositionLoaded && composeHost == null) return
        val x = btnX
        val y = btnY
        savePositionJob?.cancel()
        savePositionJob = overlayScope.launch {
            saveButtonPosition(screen, x, y)
        }
    }

    private suspend fun saveButtonPosition(screen: ButtonScreen, x: Int, y: Int) {
        settingsRepository.setFloatingButtonPosition(
            x = x,
            y = y,
            isLandscape = screen == ButtonScreen.LANDSCAPE
        )
    }

    private fun handleScreenBoundsChanged() {
        if (!shown) {
            buttonPositionScreen = null
            buttonPositionLoaded = false
            return
        }

        overlayScope.launch {
            val oldScreen = buttonPositionScreen
            val newScreen = currentButtonScreen()
            if (oldScreen == null || oldScreen != newScreen) {
                savePositionJob?.cancel()
                if (oldScreen != null && buttonPositionLoaded) {
                    saveButtonPosition(oldScreen, btnX, btnY)
                }
                loadButtonPositionIfNeeded(force = true)
                updateButtonLayout()
                FgoLogger.info(tag, "Floating button position restored for $newScreen at ($btnX, $btnY)")
            } else {
                buttonPositionScreen = newScreen
                clampButtonPositionToScreen()
                updateButtonLayout()
            }
        }
    }

    private fun updateButtonLayout() {
        val wm = windowManager ?: return
        val view = composeHost?.view ?: return
        btnLayoutParams.x = btnX
        btnLayoutParams.y = btnY
        try {
            wm.updateViewLayout(view, btnLayoutParams)
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Failed to update floating button layout", e)
        }
    }

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
                translationMode = TranslationTrigger.translationMode(),
                onTranslationModeChange = { mode ->
                    val accessibility = FgoAccessibilityService.instance
                    if (accessibility != null) {
                        accessibility.setTranslationMode(mode)
                    } else {
                        TranslationTrigger.setTranslationMode(mode)
                        overlayScope.launch(Dispatchers.IO) {
                            settingsRepository.setLastTranslationMode(mode.name)
                        }
                        refreshButtonMode()
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
        FgoAccessibilityService.instance?.stopRunnerSession()
        FgoLogger.info(tag, "Close requested from floating menu")
        onCloseRequested?.invoke()
    }

    private fun armOneShotCropMode() {
        if (cropModeState != CropModeState.SELECTING) {
            modeBeforeCrop = TranslationTrigger.translationMode()
        }
        FgoAccessibilityService.instance?.setTranslationMode(TranslationMode.MANUAL, persist = false)
            ?: TranslationTrigger.setTranslationMode(TranslationMode.MANUAL)
        FgoAccessibilityService.instance?.clearCropTranslationOverlay()
        TranslationTrigger.cancelPendingTranslation()

        cropModeState = CropModeState.SELECTING
        cropSelectionOverlay.show()
        refreshButtonMode()
        bringFloatingButtonToFront()
        FgoLogger.info(tag, "One-shot crop mode armed")
    }

    private fun requestOneShotCropTranslation() {
        val bounds = cropSelectionOverlay.selectedBounds()
        val restoreMode = modeBeforeCrop
        modeBeforeCrop = null
        cropSelectionOverlay.hide()
        cropModeState = CropModeState.IDLE
        refreshButtonMode()

        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            restoreModeAfterCropSelection(restoreMode)
            FgoLogger.warn(tag, "Crop translation requested without valid bounds")
            return
        }

        val requested = FgoAccessibilityService.instance
            ?.requestCropTranslation(Rect(bounds), restoreMode)
            ?: false
        if (!requested) {
            restoreModeAfterCropSelection(restoreMode)
            FgoLogger.warn(tag, "Accessibility service unavailable; crop translation ignored")
        }
    }

    private fun cancelCropMode() {
        cropModeState = CropModeState.IDLE
        cropSelectionOverlay.hide()
        restoreModeAfterCropSelection(modeBeforeCrop)
        modeBeforeCrop = null
        refreshButtonMode()
    }

    private fun restoreModeAfterCropSelection(mode: TranslationMode?) {
        if (mode == null) return
        FgoAccessibilityService.instance?.setTranslationMode(mode, persist = false)
            ?: TranslationTrigger.setTranslationMode(mode)
    }

    private fun updateButtonMode() {
        buttonMode = when {
            cropModeState == CropModeState.SELECTING -> FloatingButtonMode.CROP
            TranslationTrigger.translationMode() == TranslationMode.SEMI_AUTO -> FloatingButtonMode.SEMI_AUTO
            TranslationTrigger.translationMode() == TranslationMode.AUTO -> FloatingButtonMode.AUTO
            else -> FloatingButtonMode.MANUAL
        }
        if (buttonMode == FloatingButtonMode.AUTO) {
            showButtonFailureRing = false
        }
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

}
