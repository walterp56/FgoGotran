package com.fgogotran.dialoguesubtitle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnPreDraw
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.util.FgoLogger

/** Main-thread-only, touch-transparent source-row captions outside the source OCR band. */
class DialogueSubtitleOverlay(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: CaptionView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastRows = emptyList<DialogueSubtitleRenderRow>()
    private var lastWidth = 0
    private var lastHeight = 0
    private var drawVersion = 0L
    private var drawn = false
    private var onVisible: (() -> Unit)? = null

    @SuppressLint("RtlHardcoded") // Coordinates belong to the physical screenshot, not UI reading direction.
    fun show(rows: List<DialogueSubtitleRenderRow>, screenWidth: Int, screenHeight: Int, onVisible: () -> Unit): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || rows.isEmpty() || rows.size > 2 ||
            rows.any { it.slot !in 0..1 } || rows.map { it.slot }.distinct().size != rows.size) return false
        val normalizedRows = rows.map { it.copy(targetLocale = SettingsRepository.normalizeTargetLanguage(it.targetLocale)) }
        val geometry = DialogueSubtitleStyle.geometry(screenWidth, screenHeight)
        val source = DialogueSubtitleLayout.map(DialogueSubtitleLayout.text, screenWidth, screenHeight)
        if (geometry.contentWidth <= 0 || geometry.bounds.height <= 0 ||
            geometry.bounds.top < 0 || geometry.bounds.bottom >= source.top) return false
        val label = view ?: CaptionView(context).also { view = it }
        this.onVisible = onVisible
        val sameContent = lastRows == normalizedRows && lastWidth == screenWidth && lastHeight == screenHeight
        if (sameContent && label.visibility == View.VISIBLE && params != null) {
            if (drawn) onVisible()
            return true
        }
        val version = ++drawVersion
        drawn = false
        if (!sameContent && !label.prepare(
                normalizedRows, geometry,
                DialogueSubtitleStyle.textSizePx(screenWidth, screenHeight)
            )) {
            hide()
            FgoLogger.warn("DialogueSubtitle", "Cannot fit complete source-row captions")
            return false
        }
        lastRows = normalizedRows
        lastWidth = screenWidth
        lastHeight = screenHeight
        val bounds = geometry.bounds
        label.measure(
            View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY)
        )
        val layoutChanged = params?.let {
            it.width != bounds.width || it.height != bounds.height || it.x != bounds.left || it.y != bounds.top
        } ?: true
        val layout = params ?: WindowManager.LayoutParams(
            bounds.width, bounds.height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        layout.width = bounds.width
        layout.height = bounds.height
        layout.x = bounds.left
        layout.y = bounds.top
        label.visibility = View.VISIBLE
        try {
            if (params == null) manager.addView(label, layout)
            else if (layoutChanged) manager.updateViewLayout(label, layout)
            params = layout
            label.doOnPreDraw {
                label.post {
                    if (version == drawVersion && label.isShown && label.isAttachedToWindow) {
                        drawn = true
                        this.onVisible?.invoke()
                    }
                }
            }
            // Identical words in a new occurrence still need their own actual draw and timer.
            label.invalidate()
            return true
        } catch (error: Exception) {
            hide()
            FgoLogger.warn("DialogueSubtitle", "Cannot display dialogue subtitle", error)
            return false
        }
    }

    fun hide() {
        view?.visibility = View.GONE
        lastRows = emptyList()
        drawn = false
        onVisible = null
        drawVersion++
    }

    fun destroy() {
        hide()
        if (params != null) view?.let { runCatching { manager.removeViewImmediate(it) } }
        view = null
        params = null
    }

    /** Keep shaping/full-text measurement, with independent one-line slots and snug ink backgrounds. */
    private class CaptionView(context: Context) : View(context) {
        private data class CaptionLayout(
            val text: String,
            val targetLocale: String,
            val layout: StaticLayout,
            val ink: DialogueSubtitleLineMetrics,
            val fullTextCovered: Boolean
        )
        private var measured = emptyMap<Long, CaptionLayout>()
        private var rows = emptyList<DialogueSubtitleRenderRow>()
        private var geometry: DialogueSubtitleRenderGeometry? = null
        private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }

        fun prepare(rows: List<DialogueSubtitleRenderRow>, geometry: DialogueSubtitleRenderGeometry, preferredSize: Float): Boolean {
            val next = mutableMapOf<Long, CaptionLayout>()
            for (row in rows) {
                val text = DialogueSubtitleStyle.displayText(row.text)
                if (text.isBlank()) return false
                val previous = measured[row.id]?.takeIf {
                    this.geometry == geometry && it.text == text && it.targetLocale == row.targetLocale
                }
                val fitted = previous ?: run {
                    val size = DialogueSubtitleStyle.fitTextSize(preferredSize) {
                        val candidate = buildLayout(text, it, geometry, row.targetLocale)
                        // Measure both slots so moving the same row upward keeps the same font size.
                        (0..1).all { slot -> DialogueSubtitleStyle.rowPlate(geometry, slot, candidate.ink,
                            candidate.fullTextCovered) != null }
                    } ?: return false
                    buildLayout(text, size, geometry, row.targetLocale)
                }
                next[row.id] = fitted
            }
            measured = next
            this.rows = rows.toList()
            this.geometry = geometry
            contentDescription = rows.sortedBy { it.slot }.joinToString("\n") { it.text }
            return true
        }

        private fun buildLayout(text: String, size: Float, geometry: DialogueSubtitleRenderGeometry, targetLocale: String): CaptionLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = size
                typeface = FgoTypefaceProvider.storyTypeface(context, targetLocale)
                isFakeBoldText = false
                setShadowLayer(geometry.shadowPx, 0f, 0f, Color.BLACK)
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, geometry.contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            // No maxLines/ellipsis: a wrapped or clipped row must fail fitting, never lose text.
            // Font-wide top/bottom include glyphs absent from this caption. Some bundled CJK
            // fonts have very tall global bounds, so fit the actual shaped caption ink instead.
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val lineLeft = layout.getLineLeft(0)
            val ink = DialogueSubtitleLineMetrics(
                minOf(lineLeft, lineLeft + bounds.left),
                maxOf(layout.getLineRight(0), lineLeft + bounds.right),
                bounds.top, bounds.bottom, 0
            )
            return CaptionLayout(text, targetLocale, layout, ink,
                !bounds.isEmpty && layout.lineCount == 1 && layout.getLineEnd(0) == text.length)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val geometry = geometry ?: return
            val radius = geometry.shadowPx / 1.5f * 8f
            for (row in rows) {
                val caption = measured[row.id] ?: continue
                val plate = DialogueSubtitleStyle.rowPlate(geometry, row.slot, caption.ink, caption.fullTextCovered) ?: continue
                canvas.drawRoundRect(RectF(plate.left, plate.top, plate.right, plate.bottom), radius, radius, platePaint)
                val saved = canvas.save()
                canvas.translate(geometry.textOriginX, (geometry.baseline(row.slot) - caption.layout.getLineBaseline(0)).toFloat())
                caption.layout.draw(canvas)
                canvas.restoreToCount(saved)
            }
        }
    }
}


