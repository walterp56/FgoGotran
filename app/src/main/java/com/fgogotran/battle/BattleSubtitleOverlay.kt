package com.fgogotran.battle

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreaker
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import com.fgogotran.overlay.FgoViewportGeometry
import com.fgogotran.util.FgoLogger

/** Main-thread-only, touch-transparent caption placed entirely above the OCR band. */
class BattleSubtitleOverlay(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastText = ""
    private var lastWidth = 0
    private var lastHeight = 0
    private var drawVersion = 0L
    private var drawn = false
    private var onVisible: (() -> Unit)? = null

    @SuppressLint("RtlHardcoded") // x/y are physical screenshot coordinates, not reading-direction offsets.
    fun show(text: String, screenWidth: Int, screenHeight: Int, onVisible: () -> Unit): Boolean {
        val subtitle = BattleLayout.map(BattleLayout.subtitle, screenWidth, screenHeight)
        val viewport = FgoViewportGeometry.viewport(screenWidth, screenHeight)
        val scale = viewport.height / 1080f
        val label = view ?: TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false
            breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setShadowLayer(1.5f, 0f, 0f, Color.BLACK)
            background = GradientDrawable().apply { setColor(0xCC101010.toInt()); cornerRadius = 8f * scale }
        }.also { view = it }
        this.onVisible = onVisible
        if (lastText == text && lastWidth == screenWidth && lastHeight == screenHeight &&
            label.visibility == View.VISIBLE && params != null) {
            if (drawn) onVisible()
            return true
        }
        val version = ++drawVersion
        drawn = false
        lastText = text
        lastWidth = screenWidth
        lastHeight = screenHeight
        val displayText = BattleSubtitleStyle.displayText(text)
        label.text = displayText
        label.alpha = 1f
        // FGO renders its subtitle glyphs at about 44 px on the 1920x1080 reference
        // canvas. Keep that physical relationship on every viewport and wrap long
        // translations instead of shrinking their type.
        label.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            BattleSubtitleStyle.textSizePx(screenWidth, screenHeight)
        )
        label.setLineSpacing(0f, BattleSubtitleStyle.LINE_SPACING_MULTIPLIER)
        val horizontalPadding = (12 * scale).toInt()
        val verticalPadding = (6 * scale).toInt()
        label.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        val captionWidth = BattleSubtitleStyle.captionWidthPx(
            textWidthPx = label.paint.measureText(displayText),
            horizontalPaddingPx = horizontalPadding,
            maxWidthPx = subtitle.width
        )
        label.minWidth = captionWidth
        label.maxWidth = captionWidth
        label.measure(
            View.MeasureSpec.makeMeasureSpec(captionWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val layout = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        layout.width = captionWidth
        layout.height = WindowManager.LayoutParams.WRAP_CONTENT
        layout.x = (viewport.left + (viewport.width - label.measuredWidth) / 2).coerceAtLeast(0)
        layout.y = (subtitle.top - label.measuredHeight - (10 * scale).toInt()).coerceAtLeast(viewport.top)
        // Never cover source text, even if an unexpected response is excessively long.
        if (layout.y + label.measuredHeight >= subtitle.top) { hide(); return false }
        label.visibility = View.VISIBLE
        try {
            if (params == null) manager.addView(label, layout) else manager.updateViewLayout(label, layout)
            params = layout
            label.doOnPreDraw {
                label.post {
                    if (version == drawVersion && label.isShown && label.isAttachedToWindow) {
                        drawn = true
                        this.onVisible?.invoke()
                    }
                }
            }
            return true
        } catch (error: Exception) {
            hide()
            FgoLogger.warn("BattleSubtitle", "Cannot display battle subtitle", error)
            return false
        }
    }

    fun hide() {
        view?.visibility = View.GONE
        lastText = ""
        drawn = false
        onVisible = null
        drawVersion++
    }

    fun destroy() {
        hide()
        if (params != null) view?.let { runCatching { manager.removeViewImmediate(it) } }
        view = null; params = null; lastText = ""
    }
}
