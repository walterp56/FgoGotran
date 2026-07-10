package com.fgogotran.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.util.FgoLogger
import com.fgogotran.util.overlayType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceSubtitleOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var rootView: FrameLayout? = null
    private var subtitleView: TextView? = null
    private var hideRunnable: Runnable? = null
    private val subtitleLines = mutableListOf<String>()
    private var targetLocale = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    private var userPositioned = false
    private var statusShowing = false
    private var lastSubtitleTextAtMs = 0L
    private val tag = "VoiceSubtitle"

    init {
        overlayScope.launch {
            settingsRepository.targetChineseLocale.collect { locale ->
                targetLocale = locale
                subtitleView?.typeface = FgoTypefaceProvider.storyTypeface(context, locale)
            }
        }
    }

    fun showText(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        mainHandler.post {
            ensureView()
            appendSubtitleText(cleanText)
            rootView?.alpha = 1f
            if (!userPositioned) {
                rootView?.post { applyDefaultPosition() }
            }
            scheduleHide()
        }
    }

    fun showTemporaryStatus(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        mainHandler.post {
            ensureView()
            statusShowing = true
            subtitleView?.text = cleanText
            rootView?.alpha = 0.92f
            if (!userPositioned) {
                rootView?.post { applyDefaultPosition() }
            }
            scheduleHide(STATUS_VISIBLE_MS)
        }
    }

    fun hide() {
        mainHandler.post {
            hideRunnable?.let { mainHandler.removeCallbacks(it) }
            hideRunnable = null
            val wm = windowManager ?: return@post
            rootView?.let {
                runCatching { wm.removeView(it) }
                    .onFailure { error -> FgoLogger.warn(tag, "Failed to remove voice subtitle", error) }
            }
            rootView = null
            subtitleView = null
            windowLayoutParams = null
            subtitleLines.clear()
            statusShowing = false
            lastSubtitleTextAtMs = 0L
        }
    }

    private fun ensureView() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) {
            FgoLogger.warn(tag, "Overlay permission not granted, cannot show voice subtitle")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val bounds = wm.currentWindowMetrics.bounds
        val horizontalPadding = dp(24)
        val maxSubtitleWidth = (bounds.width() * 0.62f)
            .toInt()
            .coerceIn(
                dp(180),
                (bounds.width() - horizontalPadding * 2).coerceAtLeast(dp(180))
            )

        val root = FrameLayout(context)
        val subtitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = FgoTypefaceProvider.storyTypeface(context, targetLocale)
            gravity = Gravity.START
            maxLines = 3
            setMaxWidth(maxSubtitleWidth)
            includeFontPadding = false
            setLineSpacing(0f, 1.0f)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(0xCC000000.toInt())
            }
        }
        root.addView(
            subtitle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val params = WindowManager.LayoutParams().apply {
            type = overlayType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - maxSubtitleWidth) / 2).coerceAtLeast(0)
            y = (bounds.height() - dp(196)).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val dragTouchListener = buildDragTouchListener(wm, root, params)
        root.setOnTouchListener(dragTouchListener)
        subtitle.setOnTouchListener(dragTouchListener)

        runCatching {
            wm.addView(root, params)
            windowLayoutParams = params
            rootView = root
            subtitleView = subtitle
            applySavedOrDefaultPosition(wm, root, params)
        }.onFailure { error ->
            FgoLogger.warn(tag, "Failed to show voice subtitle", error)
        }
    }

    private fun appendSubtitleText(text: String) {
        val now = SystemClock.elapsedRealtime()
        val shouldStartFresh = lastSubtitleTextAtMs == 0L ||
            now - lastSubtitleTextAtMs > SUBTITLE_HISTORY_RESET_MS
        lastSubtitleTextAtMs = now

        if (statusShowing || shouldStartFresh) {
            subtitleLines.clear()
            statusShowing = false
        }

        val chunks = splitSubtitleText(text)
        if (chunks.isEmpty()) return

        val lastLine = subtitleLines.lastOrNull()
        if (lastLine != null && chunks.size == 1 && isLikelySameUtterance(lastLine, chunks.first())) {
            subtitleLines[subtitleLines.lastIndex] = chunks.first()
        } else {
            subtitleLines.addAll(chunks)
        }

        while (subtitleLines.size > MAX_SUBTITLE_LINES) {
            subtitleLines.removeAt(0)
        }
        subtitleView?.text = subtitleLines.joinToString("\n")
    }

    private fun splitSubtitleText(text: String): List<String> {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return emptyList()

        val punctuationChunks = mutableListOf<String>()
        val builder = StringBuilder()
        normalized.forEach { char ->
            builder.append(char)
            if (char in SENTENCE_BREAKS) {
                punctuationChunks += builder.toString().trim()
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) {
            punctuationChunks += builder.toString().trim()
        }

        return punctuationChunks.flatMap { chunk ->
            if (chunk.length <= MAX_CHARS_PER_LINE) {
                listOf(chunk)
            } else {
                chunk.chunked(MAX_CHARS_PER_LINE)
            }
        }.filter { it.isNotBlank() }
    }

    private fun isLikelySameUtterance(oldLine: String, newLine: String): Boolean {
        return oldLine == newLine ||
            newLine.startsWith(oldLine) ||
            oldLine.startsWith(newLine)
    }

    private fun buildDragTouchListener(
        wm: WindowManager,
        root: View,
        params: WindowManager.LayoutParams
    ): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hideRunnable?.let { mainHandler.removeCallbacks(it) }
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val targetX = startX + (event.rawX - downRawX).toInt()
                    val targetY = startY + (event.rawY - downRawY).toInt()
                    val (safeX, safeY) = clampPosition(wm, root, targetX, targetY)
                    updatePosition(wm, root, params, safeX, safeY)
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    userPositioned = true
                    saveCurrentPosition(wm, params)
                    scheduleHide()
                    true
                }
                else -> false
            }
        }
    }

    private fun applySavedOrDefaultPosition(
        wm: WindowManager,
        root: View,
        params: WindowManager.LayoutParams
    ) {
        val isLandscape = isLandscape(wm)
        overlayScope.launch(Dispatchers.IO) {
            val savedPosition = settingsRepository.getVoiceSubtitlePosition(isLandscape)
            mainHandler.post {
                if (rootView !== root) return@post
                if (savedPosition == null) {
                    userPositioned = false
                    root.post { applyDefaultPosition() }
                } else {
                    userPositioned = true
                    val (safeX, safeY) = clampPosition(wm, root, savedPosition.first, savedPosition.second)
                    updatePosition(wm, root, params, safeX, safeY)
                }
            }
        }
    }

    private fun applyDefaultPosition() {
        val wm = windowManager ?: return
        val root = rootView ?: return
        val params = windowLayoutParams ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val defaultX = ((bounds.width() - root.width) / 2).coerceAtLeast(0)
        val defaultY = (bounds.height() - root.height - dp(126)).coerceAtLeast(0)
        val (safeX, safeY) = clampPosition(wm, root, defaultX, defaultY)
        updatePosition(wm, root, params, safeX, safeY)
    }

    private fun clampPosition(
        wm: WindowManager,
        root: View,
        targetX: Int,
        targetY: Int
    ): Pair<Int, Int> {
        val bounds = wm.currentWindowMetrics.bounds
        val maxX = (bounds.width() - root.width).coerceAtLeast(0)
        val maxY = (bounds.height() - root.height).coerceAtLeast(0)
        return Pair(targetX.coerceIn(0, maxX), targetY.coerceIn(0, maxY))
    }

    private fun updatePosition(
        wm: WindowManager,
        root: View,
        params: WindowManager.LayoutParams,
        x: Int,
        y: Int
    ) {
        params.x = x
        params.y = y
        runCatching { wm.updateViewLayout(root, params) }
            .onFailure { error -> FgoLogger.warn(tag, "Failed to move voice subtitle", error) }
    }

    private fun saveCurrentPosition(wm: WindowManager, params: WindowManager.LayoutParams) {
        val isLandscape = isLandscape(wm)
        overlayScope.launch(Dispatchers.IO) {
            settingsRepository.setVoiceSubtitlePosition(params.x, params.y, isLandscape)
        }
    }

    private fun isLandscape(wm: WindowManager): Boolean {
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() >= bounds.height()
    }

    private fun scheduleHide(delayMs: Long = TEXT_VISIBLE_MS) {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = Runnable {
            rootView?.alpha = 0f
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val TEXT_VISIBLE_MS = 6_000L
        const val STATUS_VISIBLE_MS = 3_000L
        const val SUBTITLE_HISTORY_RESET_MS = 3_000L
        const val MAX_SUBTITLE_LINES = 3
        const val MAX_CHARS_PER_LINE = 24
        val SENTENCE_BREAKS = setOf('\u3002', '\uFF01', '\uFF1F', '!', '?')
    }
}
