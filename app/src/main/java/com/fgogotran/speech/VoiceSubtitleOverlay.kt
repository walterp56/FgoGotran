package com.fgogotran.speech

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.fgogotran.data.SettingsRepository
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class VoiceSubtitleOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicBoolean(false)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var textView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var hideRunnable: Runnable? = null
    private var pendingTimeoutMs: Long? = null

    private var fontSizeSp = SettingsRepository.DEFAULT_LIVE_VOICE_SUBTITLE_FONT_SIZE_SP
    private var portraitPosition: Pair<Int, Int>? = null
    private var landscapePosition: Pair<Int, Int>? = null
    private var layoutScreen: SubtitleScreen? = null

    private var touchActive = false
    private var dragMoved = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0

    init {
        settingsScope.launch {
            subtitleSettingsFlow().collect { settings ->
                withContext(Dispatchers.Main.immediate) {
                    applySettings(settings)
                }
            }
        }
    }

    /** Loads persisted placement before the first connection/status subtitle is displayed. */
    suspend fun prepare() {
        val settings = subtitleSettingsFlow().first()
        withContext(Dispatchers.Main.immediate) {
            applySettings(settings)
        }
    }

    fun activate() {
        active.set(true)
    }

    fun showPartial(text: String) {
        show(text, isFinal = false, isError = false)
    }

    fun showFinal(text: String) {
        show(text, isFinal = true, isError = false)
    }

    fun showStatus(text: String, isError: Boolean = false) {
        show(text, isFinal = true, isError = isError, timeoutMs = STATUS_TIMEOUT_MS)
    }

    /** Re-selects and safely clamps the orientation-specific position after rotation/resizing. */
    fun onDisplayChanged() {
        mainHandler.post {
            val view = textView ?: return@post
            layoutScreen = null
            updateMaximumWidth(view)
            view.requestLayout()
            view.post { placeView(view, useConfiguredPosition = true) }
        }
    }

    fun hide() {
        active.set(false)
        mainHandler.post {
            cancelScheduledHide()
            pendingTimeoutMs = null
            touchActive = false
            textView?.visibility = View.GONE
        }
    }

    fun destroy() {
        active.set(false)
        mainHandler.post {
            cancelScheduledHide()
            pendingTimeoutMs = null
            touchActive = false
            textView?.let { view ->
                runCatching { windowManager.removeViewImmediate(view) }
                    .onFailure { FgoLogger.warn(tag, "Voice subtitle overlay removal failed", it) }
            }
            textView = null
            layoutParams = null
            layoutScreen = null
        }
    }

    private fun show(
        text: String,
        isFinal: Boolean,
        isError: Boolean,
        timeoutMs: Long? = if (isFinal) FINAL_TIMEOUT_MS else null
    ) {
        val safeText = text.trim()
        if (safeText.isEmpty() || !active.get()) return
        mainHandler.post {
            if (!active.get()) return@post
            val view = ensureView() ?: return@post
            cancelScheduledHide()
            pendingTimeoutMs = timeoutMs
            view.text = safeText
            view.setTextColor(if (isError) ERROR_TEXT_COLOR else Color.WHITE)
            // Partial and final Azure results use identical brightness to avoid visual flashing.
            view.alpha = 1f
            view.visibility = View.VISIBLE
            updateMaximumWidth(view)
            view.requestLayout()
            view.post { placeView(view, useConfiguredPosition = false) }
            if (!touchActive) timeoutMs?.let(::scheduleHide)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView(): TextView? {
        textView?.let { return it }
        if (!Settings.canDrawOverlays(context)) {
            FgoLogger.warn(tag, "Cannot show voice subtitles without overlay permission")
            return null
        }
        val view = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(BACKGROUND_COLOR)
                cornerRadius = dp(10).toFloat()
            }
            visibility = View.GONE
            setOnClickListener { }
            setOnTouchListener { touchedView, event -> handleTouch(touchedView, event) }
        }
        updateMaximumWidth(view)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return runCatching {
            windowManager.addView(view, params)
            textView = view
            layoutParams = params
            layoutScreen = null
            view
        }.onFailure {
            layoutParams = null
            FgoLogger.warn(tag, "Voice subtitle overlay creation failed", it)
        }.getOrNull()
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                dragMoved = false
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                cancelScheduledHide()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (abs(dx) >= touchSlop || abs(dy) >= touchSlop) dragMoved = true
                moveView(view, dragStartWindowX + dx.roundToInt(), dragStartWindowY + dy.roundToInt())
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return false
                if (!dragMoved) view.performClick()
                finishTouch(view, savePosition = dragMoved)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!touchActive) return false
                finishTouch(view, savePosition = dragMoved)
                true
            }
            else -> touchActive
        }
    }

    private fun moveView(view: View, x: Int, y: Int) {
        val viewport = currentViewport()
        val position = SubtitleOverlayGeometry.clampPosition(
            x = x,
            y = y,
            viewWidth = view.width.coerceAtLeast(view.measuredWidth),
            viewHeight = view.height.coerceAtLeast(view.measuredHeight),
            screenWidth = viewport.width,
            screenHeight = viewport.height,
            insetLeft = viewport.insetLeft,
            insetTop = viewport.insetTop,
            insetRight = viewport.insetRight,
            insetBottom = viewport.insetBottom
        )
        updateWindowPosition(view, position)
    }

    private fun finishTouch(view: View, savePosition: Boolean) {
        touchActive = false
        val currentParams = layoutParams
        if (savePosition && currentParams != null) {
            moveView(view, currentParams.x, currentParams.y)
        } else {
            placeView(view, useConfiguredPosition = true)
        }
        val params = layoutParams
        if (savePosition && params != null) {
            val screen = currentScreen()
            val position = Pair(params.x, params.y)
            if (screen == SubtitleScreen.LANDSCAPE) {
                landscapePosition = position
            } else {
                portraitPosition = position
            }
            settingsScope.launch {
                settingsRepository.setLiveVoiceSubtitlePosition(
                    x = position.first,
                    y = position.second,
                    isLandscape = screen == SubtitleScreen.LANDSCAPE
                )
            }
        }
        dragMoved = false
        if (active.get() && view.visibility == View.VISIBLE) {
            pendingTimeoutMs?.let(::scheduleHide)
        }
    }

    private fun applySettings(settings: SubtitleSettings) {
        val fontChanged = fontSizeSp != settings.fontSizeSp
        val positionsChanged = portraitPosition != settings.portraitPosition ||
            landscapePosition != settings.landscapePosition
        fontSizeSp = settings.fontSizeSp
        portraitPosition = settings.portraitPosition
        landscapePosition = settings.landscapePosition
        val view = textView ?: return
        if (fontChanged) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
        }
        if (fontChanged || positionsChanged) {
            updateMaximumWidth(view)
            view.requestLayout()
            view.post { placeView(view, useConfiguredPosition = true) }
        }
    }

    private fun subtitleSettingsFlow() = combine(
        settingsRepository.liveVoiceSubtitleFontSizeSp,
        settingsRepository.liveVoiceSubtitlePortraitPosition,
        settingsRepository.liveVoiceSubtitleLandscapePosition
    ) { size, portrait, landscape ->
        SubtitleSettings(
            fontSizeSp = SettingsRepository.normalizeLiveVoiceSubtitleFontSizeSp(size),
            portraitPosition = portrait,
            landscapePosition = landscape
        )
    }

    private fun updateMaximumWidth(view: TextView) {
        val viewport = currentViewport()
        view.maxWidth = SubtitleOverlayGeometry.maximumTextWidth(
            screenWidth = viewport.width,
            insetLeft = viewport.insetLeft,
            insetRight = viewport.insetRight,
            coverage = MAX_WIDTH_COVERAGE
        )
    }

    private fun placeView(view: View, useConfiguredPosition: Boolean) {
        if (touchActive) return
        val params = layoutParams ?: return
        val viewport = currentViewport()
        val screen = currentScreen(viewport)
        val screenChanged = layoutScreen != screen
        val configuredPosition = if (screen == SubtitleScreen.LANDSCAPE) {
            landscapePosition
        } else {
            portraitPosition
        }
        val viewWidth = view.width.coerceAtLeast(view.measuredWidth)
        val viewHeight = view.height.coerceAtLeast(view.measuredHeight)
        val desired = when {
            useConfiguredPosition || screenChanged -> configuredPosition?.let {
                SubtitleOverlayPosition(it.first, it.second)
            } ?: defaultPosition(viewWidth, viewHeight, viewport)
            configuredPosition == null -> defaultPosition(viewWidth, viewHeight, viewport)
            else -> SubtitleOverlayPosition(params.x, params.y)
        }
        val clamped = SubtitleOverlayGeometry.clampPosition(
            x = desired.x,
            y = desired.y,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = viewport.width,
            screenHeight = viewport.height,
            insetLeft = viewport.insetLeft,
            insetTop = viewport.insetTop,
            insetRight = viewport.insetRight,
            insetBottom = viewport.insetBottom
        )
        layoutScreen = screen
        updateWindowPosition(view, clamped)
    }

    private fun defaultPosition(
        viewWidth: Int,
        viewHeight: Int,
        viewport: SubtitleViewport
    ): SubtitleOverlayPosition = SubtitleOverlayGeometry.defaultPosition(
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        screenWidth = viewport.width,
        screenHeight = viewport.height,
        insetLeft = viewport.insetLeft,
        insetTop = viewport.insetTop,
        insetRight = viewport.insetRight,
        insetBottom = viewport.insetBottom,
        topMargin = dp(TOP_MARGIN_DP)
    )

    private fun updateWindowPosition(view: View, position: SubtitleOverlayPosition) {
        val params = layoutParams ?: return
        if (params.x == position.x && params.y == position.y) return
        params.x = position.x
        params.y = position.y
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { FgoLogger.warn(tag, "Voice subtitle overlay move failed", it) }
    }

    private fun currentViewport(): SubtitleViewport {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return SubtitleViewport(
            width = metrics.bounds.width(),
            height = metrics.bounds.height(),
            insetLeft = insets.left,
            insetTop = insets.top,
            insetRight = insets.right,
            insetBottom = insets.bottom
        )
    }

    private fun currentScreen(viewport: SubtitleViewport = currentViewport()): SubtitleScreen {
        return if (viewport.width >= viewport.height) {
            SubtitleScreen.LANDSCAPE
        } else {
            SubtitleScreen.PORTRAIT
        }
    }

    private fun scheduleHide(delayMs: Long) {
        cancelScheduledHide()
        val runnable = Runnable {
            textView?.visibility = View.GONE
            hideRunnable = null
            pendingTimeoutMs = null
        }
        hideRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduledHide() {
        hideRunnable?.let(mainHandler::removeCallbacks)
        hideRunnable = null
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    private data class SubtitleSettings(
        val fontSizeSp: Int,
        val portraitPosition: Pair<Int, Int>?,
        val landscapePosition: Pair<Int, Int>?
    )

    private data class SubtitleViewport(
        val width: Int,
        val height: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int
    )

    private enum class SubtitleScreen {
        PORTRAIT,
        LANDSCAPE
    }

    private companion object {
        const val tag = "VoiceSubtitle"
        const val TOP_MARGIN_DP = 54
        const val MAX_WIDTH_COVERAGE = 0.90f
        const val FINAL_TIMEOUT_MS = 3_200L
        const val STATUS_TIMEOUT_MS = 3_000L
        const val BACKGROUND_COLOR = 0xCC101010.toInt()
        const val ERROR_TEXT_COLOR = 0xFFFFB4AB.toInt()
    }
}
