package com.fgogotran.ui.overlay

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.overlay.OverlayRenderer
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory
import kotlin.math.abs
import kotlin.math.roundToInt

private val FGO_CHOICE_HISTORY_RED = AndroidColor.rgb(246, 58, 60)
private val HISTORY_ORIGINAL_TEXT_COLOR = AndroidColor.rgb(80, 235, 235)
private const val HISTORY_TEXT_SIZE_SP = 18f
private const val HISTORY_ORIGINAL_TEXT_SIZE_SP = 14f
private const val HISTORY_LINE_SPACING_EXTRA_DP = 2
private const val HISTORY_LINE_SPACING_MULTIPLIER = 1.08f
private const val HISTORY_TEXT_BOTTOM_MARGIN_DP = 3
private const val HISTORY_TEXT_WITH_ORIGINAL_BOTTOM_MARGIN_DP = 1
private const val HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_DP = 6
private const val HISTORY_SPEAKER_BOTTOM_MARGIN_DP = 6
private const val HISTORY_DIALOGUE_BOTTOM_MARGIN_DP = 4
private const val HISTORY_SCROLLBAR_TOUCH_SLOP_DP = 28
private const val HISTORY_REFERENCE_DENSITY = 3f

@Composable
fun HistoryOverlayPanel(onDismiss: () -> Unit) {
    val entries by SessionTranslationHistory.entries.collectAsState()
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val screenWidthPx = with(density) { maxWidth.toPx().roundToInt() }
            val screenHeightPx = with(density) { maxHeight.toPx().roundToInt() }
            val viewport = FgoViewportLayout.viewportForScreen(screenWidthPx, screenHeightPx)
            val viewportScale = FgoViewportLayout.viewportScaleForScreen(screenWidthPx, screenHeightPx)
            val panelWidth = with(density) {
                viewport.width().coerceAtLeast(1).toDp()
            }
            val panelHeight = with(density) {
                viewport.height().coerceAtLeast(1).toDp()
            }

            Surface(
                modifier = Modifier
                    .requiredWidth(panelWidth)
                    .requiredHeight(panelHeight)
                    .clickable(enabled = true) { },
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(10.dp)
            ) {
                HistoryScrollView(
                    entries = entries,
                    onTap = onDismiss,
                    viewportScale = viewportScale,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun HistoryScrollView(
    entries: List<SessionTranslationEntry>,
    onTap: () -> Unit,
    viewportScale: Float,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = true
                scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                isScrollbarFadingEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setOnTouchListener(HistoryTapDismissTouchListener(onTap))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    verticalScrollbarThumbDrawable = ColorDrawable(AndroidColor.WHITE)
                }

                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(
                            scaledHistoryDp(context, 20, viewportScale),
                            scaledHistoryDp(context, 14, viewportScale),
                            scaledHistoryDp(context, 28, viewportScale),
                            scaledHistoryDp(context, 14, viewportScale)
                        )
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        },
        update = { scrollView ->
            val container = scrollView.getChildAt(0) as LinearLayout
            container.removeAllViews()
            container.setPadding(
                scaledHistoryDp(scrollView.context, 20, viewportScale),
                scaledHistoryDp(scrollView.context, 14, viewportScale),
                scaledHistoryDp(scrollView.context, 28, viewportScale),
                scaledHistoryDp(scrollView.context, 14, viewportScale)
            )

            if (entries.isEmpty()) {
                val typeface = historyTypeface(scrollView.context)
                container.gravity = Gravity.CENTER
                container.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(
                    historyTextView(
                        context = scrollView.context,
                        text = "暂无翻译LOG。",
                        typeface = typeface
                    )
                )
            } else {
                container.gravity = Gravity.NO_GRAVITY
                container.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                entries.forEachIndexed { index, entry ->
                    addHistoryEntryViews(
                        container,
                        entry,
                        historyTypeface(scrollView.context, entry.targetLocale),
                        viewportScale
                    )
                    if (index != entries.lastIndex) {
                        container.addView(spacerView(scrollView.context, 12, viewportScale))
                    }
                }
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }
    )
}

private fun addHistoryEntryViews(
    container: LinearLayout,
    entry: SessionTranslationEntry,
    typeface: Typeface,
    viewportScale: Float
) {
    val speakerName = entry.speakerName?.takeIf { it.isNotBlank() }
    speakerName?.let {
        container.addView(
            historyTextView(
                context = container.context,
                text = it,
                color = entry.speakerNameColor ?: AndroidColor.WHITE,
                typeface = typeface,
                viewportScale = viewportScale,
                bottomMarginDp = HISTORY_SPEAKER_BOTTOM_MARGIN_DP
            )
        )
    }
    entry.dialogueText?.takeIf { it.isNotBlank() }?.let {
        val originalDialogue = entry.originalDialogueText?.trim()?.takeIf { original -> original.isNotBlank() }
        if (speakerName != null || originalDialogue != null) {
            if (speakerName != null) {
                container.addView(
                    historySpeakerDialogueView(
                        context = container.context,
                        text = it,
                        originalText = originalDialogue,
                        color = entry.dialogueTextColor ?: AndroidColor.WHITE,
                        typeface = typeface,
                        viewportScale = viewportScale
                    )
                )
            } else if (originalDialogue != null) {
                container.addView(
                    historyBilingualLinesView(
                        context = container.context,
                        translationText = it,
                        originalText = originalDialogue,
                        translationColor = entry.dialogueTextColor ?: AndroidColor.WHITE,
                        typeface = typeface,
                        viewportScale = viewportScale
                    )
                )
            }
        } else {
            container.addView(
                historyTextView(
                    context = container.context,
                    text = it,
                    color = entry.dialogueTextColor ?: AndroidColor.WHITE,
                    typeface = typeface,
                    viewportScale = viewportScale,
                    useDialogueLineHeight = true
                )
            )
        }
    }
    entry.choices.forEachIndexed { index, choice ->
        if (choice.isBlank()) return@forEachIndexed
        val originalChoice = entry.originalChoices
            .getOrNull(index)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        container.addView(
            historyTextView(
                context = container.context,
                text = if (originalChoice == null) "$choice\n" else choice,
                color = FGO_CHOICE_HISTORY_RED,
                gravity = Gravity.CENTER,
                typeface = typeface,
                viewportScale = viewportScale,
                bottomMarginDp = if (originalChoice == null) {
                    HISTORY_TEXT_BOTTOM_MARGIN_DP
                } else {
                    HISTORY_TEXT_WITH_ORIGINAL_BOTTOM_MARGIN_DP
                }
            )
        )
        originalChoice?.let { original ->
            container.addView(
                historyOriginalTextView(
                    context = container.context,
                    text = original,
                    gravity = Gravity.CENTER,
                    typeface = typeface,
                    viewportScale = viewportScale
                )
            )
        }
    }
}

private fun historySpeakerDialogueView(
    context: Context,
    text: String,
    originalText: String?,
    typeface: Typeface,
    color: Int,
    viewportScale: Float
): LinearLayout {
    val quoteWidth = historyQuoteIndentPx(context, typeface, viewportScale)
    val bodyText = quoteSpeakerDialogueBody(text)
    val originalBodyText = originalText?.trim()?.takeIf { it.isNotBlank() }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = scaledHistoryDp(context, HISTORY_DIALOGUE_BOTTOM_MARGIN_DP, viewportScale)
        }
        addView(
            historyTextView(
                context = context,
                text = "「",
                color = AndroidColor.WHITE,
                typeface = typeface
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    quoteWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                if (originalBodyText == null) {
                    addView(
                        historyTextView(
                            context = context,
                            text = quoteSpeakerDialogueClosing(bodyText),
                            color = color,
                            typeface = typeface,
                            viewportScale = viewportScale,
                            useDialogueLineHeight = true
                        )
                    )
                } else {
                    addHistoryBilingualLinePairs(
                        container = this,
                        context = context,
                        translationLines = historyDisplayLines(bodyText)
                            .withClosingQuoteOnLastLine(),
                        originalLines = historyDisplayLines(originalBodyText),
                        translationColor = color,
                        typeface = typeface,
                        viewportScale = viewportScale
                    )
                }
            }
        )
    }
}

private fun historyBilingualLinesView(
    context: Context,
    translationText: String,
    originalText: String,
    translationColor: Int,
    typeface: Typeface,
    viewportScale: Float
): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = scaledHistoryDp(context, HISTORY_DIALOGUE_BOTTOM_MARGIN_DP, viewportScale)
        }
        addHistoryBilingualLinePairs(
            container = this,
            context = context,
            translationLines = historyDisplayLines(translationText),
            originalLines = historyDisplayLines(originalText),
            translationColor = translationColor,
            typeface = typeface,
            viewportScale = viewportScale
        )
    }
}

private fun addHistoryBilingualLinePairs(
    container: LinearLayout,
    context: Context,
    translationLines: List<CharSequence>,
    originalLines: List<String>,
    translationColor: Int,
    typeface: Typeface,
    viewportScale: Float
) {
    val pairCount = maxOf(translationLines.size, originalLines.size)
    repeat(pairCount) { index ->
        val hasOriginal = index < originalLines.size
        if (index < translationLines.size) {
            container.addView(
                historyTextView(
                    context = context,
                    text = translationLines[index],
                    color = translationColor,
                    typeface = typeface,
                    viewportScale = viewportScale,
                    bottomMarginDp = if (hasOriginal) {
                        HISTORY_TEXT_WITH_ORIGINAL_BOTTOM_MARGIN_DP
                    } else {
                        HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_DP
                    }
                )
            )
        }
        if (hasOriginal) {
            container.addView(
                historyOriginalTextView(
                    context = context,
                    text = originalLines[index],
                    typeface = typeface,
                    viewportScale = viewportScale,
                    bottomMarginDp = if (index == pairCount - 1) {
                        0
                    } else {
                        HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_DP
                    }
                )
            )
        }
    }
}

private fun historyDisplayLines(text: String): List<String> {
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(text.trim()) }
}

private fun List<String>.withClosingQuoteOnLastLine(): List<CharSequence> {
    return mapIndexed { index, line ->
        if (index == lastIndex) quoteSpeakerDialogueClosing(line) else line
    }
}

private fun quoteSpeakerDialogueBody(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.length >= 2 && trimmed.startsWith("「") && trimmed.endsWith("」")) {
        trimmed.substring(1, trimmed.lastIndex)
    } else {
        text
    }
}

private fun quoteSpeakerDialogueClosing(text: String): CharSequence {
    val quotedText = "$text」"
    return SpannableString(quotedText).apply {
        setSpan(
            ForegroundColorSpan(AndroidColor.WHITE),
            quotedText.lastIndex,
            quotedText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

private fun historyQuoteIndentPx(context: Context, typeface: Typeface, viewportScale: Float): Int {
    val quotePaint = TextPaint().apply {
        textSize = scaledHistoryTextPx(context, HISTORY_TEXT_SIZE_SP, viewportScale)
        this.typeface = typeface
    }
    return quotePaint.measureText("「").roundToInt().coerceAtLeast(1)
}

private fun historyTextView(
    context: Context,
    text: CharSequence,
    typeface: Typeface,
    color: Int = AndroidColor.WHITE,
    gravity: Int = Gravity.START,
    textSizeSp: Float = HISTORY_TEXT_SIZE_SP,
    viewportScale: Float = 1f,
    bottomMarginDp: Int = HISTORY_TEXT_BOTTOM_MARGIN_DP,
    useDialogueLineHeight: Boolean = false
): TextView {
    return TextView(context).apply {
        this.text = text
        setTextColor(color)
        val textSizePx = scaledHistoryTextPx(context, textSizeSp, viewportScale)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        this.typeface = typeface
        paint.isFakeBoldText = false
        paint.isSubpixelText = true
        paint.isLinearText = true
        if (useDialogueLineHeight) {
            setLineHeight((textSizePx * OverlayRenderer.DIALOGUE_LINE_HEIGHT_MULTIPLIER).roundToInt())
        } else {
            setLineSpacing(
                scaledHistoryDp(context, HISTORY_LINE_SPACING_EXTRA_DP, viewportScale).toFloat(),
                HISTORY_LINE_SPACING_MULTIPLIER
            )
        }
        includeFontPadding = false
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = scaledHistoryDp(context, bottomMarginDp, viewportScale)
        }
    }
}

private fun historyOriginalTextView(
    context: Context,
    text: String,
    typeface: Typeface,
    gravity: Int = Gravity.START,
    viewportScale: Float = 1f,
    bottomMarginDp: Int = HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_DP
): TextView {
    return historyTextView(
        context = context,
        text = text,
        color = HISTORY_ORIGINAL_TEXT_COLOR,
        gravity = gravity,
        typeface = typeface,
        textSizeSp = HISTORY_ORIGINAL_TEXT_SIZE_SP,
        viewportScale = viewportScale,
        bottomMarginDp = bottomMarginDp
    )
}

private class HistoryTapDismissTouchListener(
    private val onTap: () -> Unit
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var downScrollY = 0
    private var dragging = false
    private var downOnScrollbar = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downScrollY = scrollYOf(view)
                dragging = false
                downOnScrollbar = isNearVerticalScrollbar(view, event.x)
            }

            MotionEvent.ACTION_MOVE -> {
                if (movedBeyondSlop(event, touchSlop) || scrollChanged(view)) {
                    dragging = true
                }
            }

            MotionEvent.ACTION_UP -> {
                val moved = movedBeyondSlop(event, touchSlop)
                val scrolled = scrollChanged(view)
                if (!downOnScrollbar && !dragging && !moved && !scrolled) {
                    onTap()
                }
                reset()
            }

            MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    private fun movedBeyondSlop(event: MotionEvent, touchSlop: Int): Boolean {
        return abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
    }

    private fun scrollChanged(view: View): Boolean {
        return scrollYOf(view) != downScrollY
    }

    private fun scrollYOf(view: View): Int {
        return (view as? ScrollView)?.scrollY ?: 0
    }

    private fun isNearVerticalScrollbar(view: View, x: Float): Boolean {
        if (!view.isVerticalScrollBarEnabled) return false
        return x >= view.width - dp(view.context, HISTORY_SCROLLBAR_TOUCH_SLOP_DP)
    }

    private fun reset() {
        dragging = false
        downOnScrollbar = false
    }
}

private fun spacerView(context: Context, heightDp: Int, viewportScale: Float): View {
    return View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            scaledHistoryDp(context, heightDp, viewportScale)
        )
    }
}

private fun historyTypeface(
    context: Context,
    targetLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
): Typeface {
    return FgoTypefaceProvider.storyTypeface(context, targetLocale)
}

private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).toInt()
}

private fun scaledHistoryDp(context: Context, value: Int, viewportScale: Float): Int {
    val currentPx = dp(context, value)
    val safeScale = viewportScale.coerceIn(0.75f, 1.4f)
    val targetPx = (value * HISTORY_REFERENCE_DENSITY * safeScale).roundToInt()
    return maxOf(currentPx, targetPx)
}

private fun scaledHistoryTextPx(context: Context, valueSp: Float, viewportScale: Float): Float {
    val currentPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        valueSp,
        context.resources.displayMetrics
    )
    val safeScale = viewportScale.coerceIn(0.75f, 1.4f)
    val targetPx = valueSp * HISTORY_REFERENCE_DENSITY * safeScale
    return maxOf(currentPx, targetPx)
}
