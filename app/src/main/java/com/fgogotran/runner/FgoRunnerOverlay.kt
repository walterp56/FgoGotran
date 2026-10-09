package com.fgogotran.runner

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.battle.BattleModeState
import com.fgogotran.crop.CropModeState
import com.fgogotran.crop.CropSelectionOverlay
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.translation.TranslationMode
import com.fgogotran.translation.TranslationTrigger
import com.fgogotran.ui.overlay.FloatingButton
import com.fgogotran.ui.overlay.FloatingButtonMode
import com.fgogotran.ui.overlay.FloatingButtonDocking
import com.fgogotran.ui.overlay.FloatingControlBounds
import com.fgogotran.ui.overlay.FloatingDockSide
import com.fgogotran.ui.overlay.HistoryOverlayPanel
import com.fgogotran.ui.overlay.FloatingArcMenu
import com.fgogotran.ui.overlay.withBattleIndicator
import com.fgogotran.util.FakeComposeHost
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.overlayType
import com.fgogotran.util.setFloatingHandleGestureExclusion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
 * - Requests translation on tap and shows the popup [FloatingArcMenu] on long press
 * - Persists button position via DataStore
 *
 * The overlay uses [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * with FLAG_NOT_TOUCH_MODAL | FLAG_NOT_FOCUSABLE so touches outside its
 * small window pass through to FGO underneath.
 */
@Singleton
class FgoRunnerOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cropSelectionOverlay: CropSelectionOverlay,
    private val settingsRepository: SettingsRepository,
    private val battleModeState: BattleModeState,
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private var windowManager: WindowManager? = null
    private var composeHost: FakeComposeHost? = null
    private var historyHost: FakeComposeHost? = null
    private var floatingMenuHost: FakeComposeHost? = null
    private var onCloseRequested: (() -> Unit)? = null
    private var onLiveVoiceTranslationToggleRequested: ((Boolean) -> Unit)? = null
    private var shown = false
    private var cropModeState = CropModeState.IDLE
    private var cropLoadingJob: Job? = null
    private var modeBeforeCrop: TranslationMode? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buttonMode by mutableStateOf(FloatingButtonMode.MANUAL)
    private var buttonSizeDp by mutableStateOf(SettingsRepository.DEFAULT_FLOATING_BUTTON_SIZE_DP)
    private var dockedSide by mutableStateOf<FloatingDockSide?>(null)
    private var buttonDrag: ButtonDrag? = null
    private var dockViewport = FloatingControlBounds(
        0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels
    )
    private var gameServer by mutableStateOf(SettingsRepository.DEFAULT_GAME_SERVER)
    private var aiVoiceEnabled by mutableStateOf(false)
    private var liveVoiceTranslationEnabled by mutableStateOf(false)
    private var battleModeActive by mutableStateOf(false)
    private var showButtonFailureRing by mutableStateOf(false)
    private var failureFeedbackVersion = 0
    private var buttonPositionScreen: ButtonScreen? = null
    private var buttonPositionLoaded = false
    private var savePositionJob: Job? = null
    private var buttonSizeJob: Job? = null
    private var gameServerJob: Job? = null
    private var aiVoiceEnabledJob: Job? = null
    private var liveVoiceTranslationEnabledJob: Job? = null
    private var battleModeJob: Job? = null
    private var showRequestVersion = 0
    private var callbacksRegistered = false
    private var appliedLanguageTag: String? = null

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handleScreenBoundsChanged()
            refreshHostsForLanguageChange()
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
    }

    private enum class ButtonScreen {
        PORTRAIT,
        LANDSCAPE
    }

    private data class ButtonDrag(
        val downX: Float,
        val downY: Float,
        val buttonX: Int,
        val buttonY: Int,
        val windowOriginX: Int,
        val windowOriginY: Int,
        val startedDocked: Boolean,
        val viewport: FloatingControlBounds
    )

    /** Layout params for the floating button overlay. */
    private val btnLayoutParams: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            val bounds = controlWindowBounds()
            type = overlayType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = if (dockedSide == null) WindowManager.LayoutParams.WRAP_CONTENT else bounds.width
            height = if (dockedSide == null) WindowManager.LayoutParams.WRAP_CONTENT else bounds.height
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
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

    /** Layout params for the full-screen arc menu overlay. */
    private val menuLayoutParams: WindowManager.LayoutParams
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
    fun init(
        onCloseRequested: () -> Unit,
        onLiveVoiceTranslationToggleRequested: (Boolean) -> Unit
    ) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.onCloseRequested = onCloseRequested
        this.onLiveVoiceTranslationToggleRequested = onLiveVoiceTranslationToggleRequested
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
        dockedSide = null
        buttonDrag = null
        val requestVersion = ++showRequestVersion
        overlayScope.launch {
            try {
                loadButtonPositionIfNeeded()
                restoreLastTranslationMode()
                buttonSizeDp = settingsRepository.getFloatingButtonSizeDp()
                gameServer = settingsRepository.getGameServer()
                aiVoiceEnabled = settingsRepository.aiVoiceEnabled.first()
                liveVoiceTranslationEnabled = settingsRepository.liveVoiceTranslationEnabled.first()
                battleModeActive = battleModeState.active.value
                if (!shown || requestVersion != showRequestVersion) return@launch

                val wm = windowManager
                if (wm == null) {
                    shown = false
                    return@launch
                }

                refreshButtonMode()
                appliedLanguageTag = AppLanguageManager.effectiveLanguageTag(context)
                composeHost = FakeComposeHost(context) {
                    FloatingButtonContent()
                }

                clampButtonPositionToScreen()
                wm.addView(composeHost!!.view, btnLayoutParams)
                observeControlLayout(composeHost!!.view)
                startButtonSizeObserver()
                startGameServerObserver()
                startAiVoiceEnabledObserver()
                startLiveVoiceTranslationEnabledObserver()
                startBattleModeObserver()
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
        buttonDrag = null
        dockedSide = null
        publishDockHandleBounds()
        dismissMenu()
        showRequestVersion += 1
        buttonSizeJob?.cancel()
        buttonSizeJob = null
        gameServerJob?.cancel()
        gameServerJob = null
        aiVoiceEnabledJob?.cancel()
        aiVoiceEnabledJob = null
        liveVoiceTranslationEnabledJob?.cancel()
        liveVoiceTranslationEnabledJob = null
        battleModeJob?.cancel()
        battleModeJob = null
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
        onLiveVoiceTranslationToggleRequested = null
        FgoLogger.info(tag, "Overlay destroyed")
    }

    /** Whether the floating control is running, including its small docked handle. */
    fun isShowing(): Boolean = shown

    /**
     * Rebuilds the floating overlay after the UI language changed.
     *
     * Compose resolves string resources with the locale captured when the ComposeView was
     * created, so the button and arc menu keep the previous language until their host view is
     * recreated. [FgoRunnerService] calls this from the in-app language picker;
     * [refreshHostsForLanguageChange] covers configuration-driven locale changes.
     */
    fun refreshUiLanguage() {
        val languageTag = AppLanguageManager.effectiveLanguageTag(context)
        val previous = appliedLanguageTag
        appliedLanguageTag = languageTag
        if (previous == languageTag) return
        FgoLogger.info(tag, "UI language set to $languageTag")
        if (!shown) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            rebuildHostsForLanguage()
        } else {
            mainHandler.post { rebuildHostsForLanguage() }
        }
    }

    /** Locale change delivered as a configuration change; older releases go through [refreshUiLanguage]. */
    private fun refreshHostsForLanguageChange() {
        val languageTag = AppLanguageManager.effectiveLanguageTag(context)
        val previous = appliedLanguageTag
        appliedLanguageTag = languageTag
        if (previous == null || previous == languageTag || !shown) return
        FgoLogger.info(tag, "UI language changed $previous -> $languageTag, rebuilding overlay")
        mainHandler.post { rebuildHostsForLanguage() }
    }

    private fun rebuildHostsForLanguage() {
        val wm = windowManager ?: return
        if (!shown) return
        if (floatingMenuHost != null) dismissMenu()
        dismissHistoryPanel()
        val previousHost = composeHost ?: return
        buttonDrag = null
        composeHost = null
        runCatching { wm.removeView(previousHost.view) }
        previousHost.close()
        val host = FakeComposeHost(context) { FloatingButtonContent() }
        try {
            wm.addView(host.view, btnLayoutParams)
            composeHost = host
            observeControlLayout(host.view)
            FgoLogger.info(tag, "Floating button rebuilt for UI language")
        } catch (e: Exception) {
            host.close()
            FgoLogger.warn(tag, "Failed to rebuild floating button for UI language", e)
        }
    }

    @Composable
    private fun FloatingButtonContent() {
        FloatingButton(
            mode = buttonMode,
            buttonSize = buttonSizeDp.dp,
            dockedSide = dockedSide,
            showFailureRing = showButtonFailureRing &&
                buttonMode != FloatingButtonMode.AUTO &&
                buttonMode != FloatingButtonMode.BATTLE,
            onClick = { onButtonClick() },
            onLongClick = { onButtonLongClick() },
            onDragStart = { x, y -> beginButtonDrag(x, y) },
            onDragTo = { x, y -> dragButtonTo(x, y) },
            onDragEnd = { cancelled -> finishButtonDrag(cancelled) }
        )
    }

    fun refreshButtonMode() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateButtonMode()
        } else {
            mainHandler.post { updateButtonMode() }
        }
    }

    /** Display-only: retain the crop icon while the accepted request is loading. */
    fun showCropTranslationLoading(job: Job) {
        cropLoadingJob = job
        refreshButtonMode()
        job.invokeOnCompletion {
            mainHandler.post {
                // An older request must not clear a newer request's indicator.
                if (cropLoadingJob === job) {
                    cropLoadingJob = null
                    refreshButtonMode()
                }
            }
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
        if (!shown || dockedSide != null) return false
        FgoLogger.debug(tag, "Translated overlay long press routed to floating menu")
        onButtonLongClick()
        return true
    }

    fun isButtonDocked(): Boolean = dockedSide != null

    fun canStartButtonDrag(dx: Float, dy: Float, slop: Float): Boolean =
        FloatingButtonDocking.canStartDrag(dockedSide, dx, dy, slop)

    fun handleInterceptedButtonDragStart(rawX: Float, rawY: Float): Boolean = beginButtonDrag(rawX, rawY)

    fun handleInterceptedButtonDrag(rawX: Float, rawY: Float): Boolean = dragButtonTo(rawX, rawY)

    fun handleInterceptedButtonDragEnd(cancelled: Boolean) = finishButtonDrag(cancelled)

    fun isPointInsideButton(rawX: Float, rawY: Float): Boolean {
        if (!shown) return false
        val view = composeHost?.view ?: return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (dockedSide != null) {
            return dockHandleScreenBounds()?.contains(rawX.toInt(), rawY.toInt()) == true
        }
        val fallbackSize = currentButtonSizePx()
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth.takeIf { it > 0 } ?: fallbackSize
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight.takeIf { it > 0 } ?: fallbackSize

        val centerX = location[0] + width / 2f
        val centerY = location[1] + height / 2f
        val radius = minOf(width, height) / 2f
        val dx = rawX - centerX
        val dy = rawY - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun onButtonClick() {
        if (dockedSide != null) {
            restoreDockedButton()
            FgoLogger.debug(tag, "Floating button revealed by edge handle tap")
            return
        }
        if (cropModeState == CropModeState.SELECTING) {
            if (!isJapaneseServer()) {
                cancelCropMode()
                return
            }
            requestOneShotCropTranslation()
            return
        }

        if (battleModeState.active.value) {
            return
        }

        if (!TranslationTrigger.canUserTapTranslate()) {
            FgoLogger.debug(tag, "Floating button tap ignored while full auto translation is enabled")
            return
        }

        val accessibility = FgoAccessibilityService.instance
        if (accessibility == null) {
            reportAccessibilityServiceUnavailable("手动翻译")
            return
        }
        if (!accessibility.requestManualTranslation()) {
            FgoLogger.debug(tag, "Manual translation queued by the translation trigger")
            TranslationTrigger.requestTranslation()
        }
    }

    /**
     * The floating menu keeps working when the accessibility binding dies, but every OCR
     * action used to silently do nothing. Reporting it tells the user to re-enable the
     * service instead of assuming the translation itself is broken.
     */
    private fun reportAccessibilityServiceUnavailable(action: String) {
        FgoLogger.warn(tag, "Accessibility service unavailable; $action ignored")
        showTranslationFailureFeedback(fromUserTap = true)
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "accessibility_service_unavailable",
            title = "无障碍服务不可用",
            message = "系统设置显示已开启，但 FgoGotran 没有收到连接",
            detail = "关闭无障碍服务，等待 2–3 秒后重新开启，然后回到 FGO 重试"
        )
    }

    // ─── Drag handling ────────────────────────────────────────────────

    private fun beginButtonDrag(rawX: Float, rawY: Float): Boolean {
        if (!shown) return false
        val wm = windowManager ?: return false
        val view = composeHost?.view ?: return false
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        // Existing saved positions are window-relative; routed touches are screen-relative.
        val originX = location[0] - params.x
        val originY = location[1] - params.y
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val left = (insets.left - originX).coerceAtLeast(0)
        val top = (insets.top - originY).coerceAtLeast(0)
        dockViewport = FloatingControlBounds(
            left, top,
            (metrics.bounds.width() - insets.right - originX).coerceAtLeast(left + 1),
            (metrics.bounds.height() - insets.bottom - originY).coerceAtLeast(top + 1)
        )
        buttonPositionScreen = currentButtonScreen()
        buttonDrag = ButtonDrag(rawX, rawY, btnX, btnY, originX, originY, dockedSide != null, dockViewport)
        return true
    }

    private fun dragButtonTo(rawX: Float, rawY: Float): Boolean {
        if (!shown || composeHost?.view == null) return false
        val drag = buttonDrag ?: return false
        val size = currentButtonSizePx()
        val bounds = if (drag.startedDocked) {
            FloatingButtonDocking.revealedButtonBounds(
                rawX - drag.windowOriginX, rawY - drag.windowOriginY, size, drag.viewport
            )
        } else {
            FloatingButtonDocking.buttonBounds(
                drag.buttonX + (rawX - drag.downX).roundToInt(),
                drag.buttonY + (rawY - drag.downY).roundToInt(), size, drag.viewport
            )
        }
        val wasDocked = dockedSide != null
        dockedSide = null
        if (wasDocked) {
            publishDockHandleBounds()
            FgoLogger.debug(tag, "Floating button revealed from edge")
        }
        if (wasDocked || btnX != bounds.left || btnY != bounds.top) {
            btnX = bounds.left
            btnY = bounds.top
            updateButtonLayout()
            saveButtonPositionSoon()
        }
        return true
    }

    private fun finishButtonDrag(cancelled: Boolean) {
        val drag = buttonDrag ?: return
        buttonDrag = null
        if (!shown) return
        val size = currentButtonSizePx()
        val side = FloatingButtonDocking.sideAfterRelease(
            cancelled, drag.startedDocked, btnX, size, drag.viewport,
            (FloatingButtonDocking.EDGE_ALLOWANCE_DP * context.resources.displayMetrics.density).roundToInt()
        )
        if (side != null) {
            btnX = if (side == FloatingDockSide.LEFT) drag.viewport.left else
                (drag.viewport.right - size).coerceAtLeast(drag.viewport.left)
            dockedSide = side
            updateButtonLayout()
            FgoLogger.debug(tag, "Floating button docked at $side edge")
        }
        saveButtonPositionSoon()
    }

    private fun controlWindowBounds(): FloatingControlBounds {
        val size = currentButtonSizePx()
        val side = dockedSide ?: return FloatingControlBounds(btnX, btnY, btnX + size, btnY + size)
        val density = context.resources.displayMetrics.density
        return FloatingButtonDocking.handleBounds(
            side, btnY, size,
            (FloatingButtonDocking.HANDLE_WIDTH_DP * density).roundToInt(),
            (FloatingButtonDocking.HANDLE_HEIGHT_DP * density).roundToInt(), dockViewport
        )
    }

    fun dockHandleScreenBounds(): Rect? {
        if (!shown || dockedSide == null) return null
        val view = composeHost?.view ?: return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val bounds = controlWindowBounds()
        return Rect(location[0], location[1], location[0] + bounds.width, location[1] + bounds.height)
    }

    private fun publishDockHandleBounds() {
        val bounds = dockHandleScreenBounds()
        composeHost?.view?.setFloatingHandleGestureExclusion(bounds)
        FgoAccessibilityService.instance?.updateFloatingHandleGestureExclusion(bounds)
    }

    private fun observeControlLayout(view: View) {
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (composeHost?.view === view) publishDockHandleBounds()
        }
        view.post { if (composeHost?.view === view) publishDockHandleBounds() }
    }

    private fun restoreDockedButton(relayout: Boolean = true) {
        buttonDrag = null
        if (dockedSide == null) return
        dockedSide = null
        publishDockHandleBounds()
        if (relayout) {
            clampButtonPositionToScreen()
            updateButtonLayout()
        }
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
        val fallbackSize = currentButtonSizePx()
        val width = buttonWidth?.takeIf { it > 0 } ?: fallbackSize
        val height = buttonHeight?.takeIf { it > 0 } ?: fallbackSize
        val maxX = (bounds.width() - width).coerceAtLeast(0)
        val maxY = (bounds.height() - height).coerceAtLeast(0)
        btnX = btnX.coerceIn(0, maxX)
        btnY = btnY.coerceIn(0, maxY)
    }

    private fun currentButtonSizePx(): Int {
        val safeSize = SettingsRepository.normalizeFloatingButtonSizeDp(buttonSizeDp)
        return (safeSize * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    private fun startButtonSizeObserver() {
        buttonSizeJob?.cancel()
        buttonSizeJob = overlayScope.launch {
            settingsRepository.floatingButtonSizeDp.collect { sizeDp ->
                val safeSize = SettingsRepository.normalizeFloatingButtonSizeDp(sizeDp)
                if (safeSize == buttonSizeDp) return@collect
                restoreDockedButton()
                buttonSizeDp = safeSize
                composeHost?.view?.post {
                    clampButtonPositionToScreen()
                    updateButtonLayout()
                }
            }
        }
    }

    private fun startGameServerObserver() {
        gameServerJob?.cancel()
        gameServerJob = overlayScope.launch {
            settingsRepository.gameServer.collect { server ->
                val normalizedServer = SettingsRepository.normalizeGameServer(server)
                if (normalizedServer == gameServer) return@collect
                gameServer = normalizedServer
                if (!isJapaneseServer()) {
                    setBattleModeEnabled(false)
                    cancelCropMode()
                    dismissHistoryPanel()
                }
                refreshButtonMode()
            }
        }
    }

    private fun startAiVoiceEnabledObserver() {
        aiVoiceEnabledJob?.cancel()
        aiVoiceEnabledJob = overlayScope.launch {
            settingsRepository.aiVoiceEnabled.collect { enabled ->
                if (enabled == aiVoiceEnabled) return@collect
                aiVoiceEnabled = enabled
            }
        }
    }

    private fun startLiveVoiceTranslationEnabledObserver() {
        liveVoiceTranslationEnabledJob?.cancel()
        liveVoiceTranslationEnabledJob = overlayScope.launch {
            settingsRepository.liveVoiceTranslationEnabled.collect { enabled ->
                if (enabled == liveVoiceTranslationEnabled) return@collect
                liveVoiceTranslationEnabled = enabled
            }
        }
    }

    private fun startBattleModeObserver() {
        battleModeJob?.cancel()
        battleModeJob = overlayScope.launch {
            battleModeState.active.collect { active ->
                if (active == battleModeActive) return@collect
                battleModeActive = active
                refreshButtonMode()
            }
        }
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
        dismissMenu()
        // Save the old orientation's full-button position before applying the new bounds.
        restoreDockedButton(relayout = false)
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
        try {
            wm.updateViewLayout(view, btnLayoutParams)
            if (dockedSide != null) {
                view.post { if (composeHost?.view === view) publishDockHandleBounds() }
            }
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Failed to update floating button layout", e)
        }
    }

    /** Called when the user holds the floating button (not drags). */
    private fun onButtonLongClick() {
        if (dockedSide != null) return
        if (floatingMenuHost != null) {
            dismissMenu()
        }
        showArcMenu()
    }

    /**
     * Creates and shows the arc fan menu as a full-screen overlay window.
     *
     * Uses [FakeComposeHost] to render the [FloatingArcMenu] composable
     * anchored around the floating button.
     */
    private fun showArcMenu() {
        val wm = windowManager ?: return
        dismissMenu()
        TranslationTrigger.setMenuVisible(true)

        val buttonSizePx = currentButtonSizePx()
        val centerX = btnX + buttonSizePx / 2
        val centerY = btnY + buttonSizePx / 2
        val viewportScale = currentViewportScale()

        val host = FakeComposeHost(context) {
            FloatingArcMenu(
                translationMode = TranslationTrigger.translationMode(),
                battleModeActive = battleModeActive,
                gameServer = gameServer,
                liveVoiceTranslationEnabled = liveVoiceTranslationEnabled,
                buttonCenterX = centerX,
                buttonCenterY = centerY,
                viewportScale = viewportScale,
                onDismiss = { dismissMenu() },
                onTranslationModeChange = { mode ->
                    val accessibility = FgoAccessibilityService.instance
                    if (accessibility != null) {
                        accessibility.setBattleModeEnabled(false)
                        accessibility.setTranslationMode(mode)
                    } else {
                        battleModeState.setEnabled(false)
                        TranslationTrigger.setTranslationMode(mode)
                        overlayScope.launch(Dispatchers.IO) {
                            settingsRepository.setLastTranslationMode(mode.name)
                        }
                        refreshButtonMode()
                    }
                    dismissMenu()
                },
                onBattleModeSelect = {
                    if (isJapaneseServer()) {
                        cancelCropMode()
                        setBattleModeEnabled(true)
                        dismissMenu()
                    }
                },
                onLiveVoiceTranslationToggle = { enabled ->
                    liveVoiceTranslationEnabled = enabled
                    onLiveVoiceTranslationToggleRequested?.invoke(enabled)
                },
                onCropTranslateClick = {
                    if (isJapaneseServer()) {
                        dismissMenu()
                        armOneShotCropMode()
                    }
                },
                onHistoryClick = {
                    if (isJapaneseServer()) {
                        dismissMenu()
                        showHistoryPanel()
                    }
                },
                onCloseClick = { requestClose() }
            )
        }

        floatingMenuHost = host
        try {
            wm.addView(host.view, menuLayoutParams)
        } catch (e: Exception) {
            host.close()
            floatingMenuHost = null
            TranslationTrigger.setMenuVisible(false)
            FgoLogger.warn(tag, "Failed to show arc menu", e)
            return
        }
        FgoLogger.info(tag, "Arc menu shown at ($centerX, $centerY)")
    }

    private fun currentViewportScale(): Float {
        val bounds = windowManager?.currentWindowMetrics?.bounds
            ?: return 1f
        return FgoViewportLayout.viewportScaleForScreen(bounds.width(), bounds.height())
    }

    private fun dismissMenu() {
        val wm = windowManager
        floatingMenuHost?.let {
            try { wm?.removeView(it.view) } catch (_: Exception) {}
            it.close()
        }
        floatingMenuHost = null
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
        if (!isJapaneseServer()) return
        setBattleModeEnabled(false)
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

        val accessibility = FgoAccessibilityService.instance
        if (accessibility == null) {
            restoreModeAfterCropSelection(restoreMode)
            reportAccessibilityServiceUnavailable("区域翻译")
            return
        }
        if (!accessibility.requestCropTranslation(Rect(bounds), restoreMode)) {
            restoreModeAfterCropSelection(restoreMode)
            FgoLogger.warn(tag, "Crop translation rejected by the accessibility service")
        }
    }

    private fun cancelCropMode() {
        cropModeState = CropModeState.IDLE
        cropLoadingJob = null
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

    private fun isJapaneseServer(): Boolean {
        return SettingsRepository.normalizeGameServer(gameServer) == SettingsRepository.GAME_SERVER_JP
    }

    private fun setBattleModeEnabled(enabled: Boolean) {
        val accessibility = FgoAccessibilityService.instance
        if (accessibility != null) {
            accessibility.setBattleModeEnabled(enabled)
        } else {
            battleModeState.setEnabled(enabled)
            refreshButtonMode()
        }
    }

    private fun updateButtonMode() {
        val japaneseServer = isJapaneseServer()
        val translationMode = TranslationTrigger.translationMode()
        val normalMode = when {
            japaneseServer && (cropModeState == CropModeState.SELECTING || cropLoadingJob?.isActive == true) ->
                FloatingButtonMode.CROP
            translationMode == TranslationMode.SEMI_AUTO -> FloatingButtonMode.SEMI_AUTO
            translationMode == TranslationMode.AUTO -> FloatingButtonMode.AUTO
            else -> FloatingButtonMode.MANUAL
        }
        val newMode = normalMode.withBattleIndicator(battleModeActive)
        if (buttonMode != newMode) buttonDrag = null
        buttonMode = newMode
        if (translationMode == TranslationMode.AUTO || buttonMode == FloatingButtonMode.BATTLE) {
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
        try {
            wm.addView(historyHost!!.view, historyLayoutParams)
        } catch (e: Exception) {
            historyHost?.close()
            historyHost = null
            TranslationTrigger.setHistoryVisible(false)
            FgoLogger.warn(tag, "Failed to show history panel", e)
            return
        }
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
