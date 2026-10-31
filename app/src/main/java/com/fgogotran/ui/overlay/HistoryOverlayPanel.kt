package com.fgogotran.ui.overlay

import com.fgogotran.text.EmphasisText
import android.content.Context
import com.fgogotran.R
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.overlay.FgoViewportLayout
import com.fgogotran.overlay.RubyRenderText
import com.fgogotran.overlay.RubyTextView
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory
import kotlin.math.abs
import kotlin.math.roundToInt

private val FGO_CHOICE_HISTORY_RED = AndroidColor.rgb(246, 58, 60)
private val HISTORY_ORIGINAL_TEXT_COLOR = AndroidColor.rgb(80, 235, 235)
private const val HISTORY_EMPTY_TEXT_SIZE_SP = 18f
private const val HISTORY_LINE_SPACING_EXTRA_BASE_DP = 4
private const val HISTORY_LINE_SPACING_MULTIPLIER = 1.2f
private const val HISTORY_TEXT_BOTTOM_MARGIN_BASE_DP = 3
private const val HISTORY_TEXT_WITH_ORIGINAL_BOTTOM_MARGIN_BASE_DP = 1
private const val HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_BASE_DP = 6
private const val HISTORY_SPEAKER_BOTTOM_MARGIN_BASE_DP = 10
private const val HISTORY_DIALOGUE_BOTTOM_MARGIN_BASE_DP = 4
private const val HISTORY_SCROLLBAR_TOUCH_SLOP_DP = 28

private const val HISTORY_DIALOGUE_LINE_HEIGHT_MULTIPLIER = 1.7f

@Composable
fun HistoryOverlayPanel(settingsRepository: SettingsRepository, onDismiss: () -> Unit) {
    val entries by SessionTranslationHistory.entries.collectAsState()
    val showAnnotations by settingsRepository.translationIncludeRuby.collectAsState(
        initial = SettingsRepository.DEFAULT_TRANSLATION_INCLUDE_RUBY)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val uiConfiguration = HistoryUiConfiguration(configuration.densityDpi, configuration.fontScale,
        configuration.locales.toLanguageTags(), showAnnotations)

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
            val contentSizing = HistoryContentSizing.forScreen(screenWidthPx, screenHeightPx)
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
                    configuration = uiConfiguration,
                    contentSizing = contentSizing,
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
    configuration: HistoryUiConfiguration,
    contentSizing: HistoryContentSizing,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                val list = RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = HistoryRowsAdapter()
                    itemAnimator = null // Updating the LOG should not flash/fade existing text.
                    isVerticalScrollBarEnabled = true
                    scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                    isScrollbarFadingEnabled = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    setOnTouchListener(HistoryTapDismissTouchListener(onTap))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        verticalScrollbarThumbDrawable = ColorDrawable(AndroidColor.WHITE)
                    }
                }
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(historyTextView(context, context.getString(R.string.history_empty), historyTypeface(context), contentSizing).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, HISTORY_EMPTY_TEXT_SIZE_SP)
                    gravity = Gravity.CENTER
                    setOnClickListener { onTap() }
                }, FrameLayout.LayoutParams(-1, -1))
                val state = HistoryListState()
                tag = state
                list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) state.userScrollVersion++
                    }
                })
            }
        },
        update = { root ->
            val list = root.getChildAt(0) as RecyclerView
            val state = root.tag as HistoryListState
            val emptyLabel = root.getChildAt(1) as TextView
            emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            emptyLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, HISTORY_EMPTY_TEXT_SIZE_SP)
            if (state.entries !== entries || state.configuration != configuration || state.contentSizing != contentSizing) {
                val followTail = !state.initialized ||
                    (!list.canScrollVertically(1) && list.scrollState == RecyclerView.SCROLL_STATE_IDLE)
                val scrollVersion = state.userScrollVersion
                val manager = list.layoutManager as LinearLayoutManager
                val adapter = list.adapter as HistoryRowsAdapter
                val firstVisible = manager.findFirstVisibleItemPosition()
                val anchorId = adapter.currentList.getOrNull(firstVisible)?.entry?.historyId
                val anchorOffset = manager.findViewByPosition(firstVisible)?.let { it.top - list.paddingTop }
                list.setPadding(
                    contentSizing.spacingPx(20),
                    contentSizing.spacingPx(14),
                    contentSizing.spacingPx(28),
                    contentSizing.spacingPx(14)
                )
                val updateVersion = ++state.updateVersion
                state.entries = entries
                state.configuration = configuration
                state.contentSizing = contentSizing
                adapter.submitList(entries.map { HistoryRow(it, configuration, contentSizing) }) {
                    if (updateVersion == state.updateVersion) {
                        state.initialized = true
                        if (followTail && scrollVersion == state.userScrollVersion && entries.isNotEmpty()) {
                            list.post {
                                if (updateVersion == state.updateVersion && scrollVersion == state.userScrollVersion &&
                                    list.scrollState == RecyclerView.SCROLL_STATE_IDLE) list.scrollToPosition(entries.lastIndex)
                            }
                        } else if (!followTail && scrollVersion == state.userScrollVersion &&
                            list.scrollState == RecyclerView.SCROLL_STATE_IDLE && anchorId != null && anchorOffset != null) {
                            // A delayed battle result can be inserted before the visible story row.
                            // Keep that row at the same pixel offset instead of jumping to the new entry.
                            val anchorPosition = entries.indexOfFirst { it.historyId == anchorId }
                            if (anchorPosition >= 0) manager.scrollToPositionWithOffset(anchorPosition, anchorOffset)
                        }
                    }
                }
            }
        }
    )
}

private class HistoryListState {
    var entries: List<SessionTranslationEntry>? = null
    var configuration: HistoryUiConfiguration? = null
    var contentSizing: HistoryContentSizing? = null
    var initialized = false
    var userScrollVersion = 0L
    var updateVersion = 0L
}

private data class HistoryUiConfiguration(val densityDpi: Int, val fontScale: Float, val locales: String,
    val showAnnotations: Boolean)

private data class HistoryRow(
    val entry: SessionTranslationEntry,
    val configuration: HistoryUiConfiguration,
    val contentSizing: HistoryContentSizing
)

private class HistoryRowsAdapter : ListAdapter<HistoryRow, HistoryRowsAdapter.Holder>(
    object : DiffUtil.ItemCallback<HistoryRow>() {
        override fun areItemsTheSame(oldItem: HistoryRow, newItem: HistoryRow) =
            oldItem.entry.historyId == newItem.entry.historyId
        override fun areContentsTheSame(oldItem: HistoryRow, newItem: HistoryRow) = oldItem == newItem
    }
) {
    class Holder(val container: LinearLayout) : RecyclerView.ViewHolder(container)

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = getItem(position).entry.historyId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.container.removeAllViews()
        holder.container.setPadding(0, 0, 0, row.contentSizing.spacingPx(12))
        addHistoryEntryViews(
            holder.container, row.entry,
            historyTypeface(holder.container.context, row.entry.targetLocale), row.contentSizing,
            row.configuration.showAnnotations
        )
    }
}

private fun addHistoryEntryViews(
    container: LinearLayout,
    entry: SessionTranslationEntry,
    typeface: Typeface,
    contentSizing: HistoryContentSizing,
    showAnnotations: Boolean,
) {
    val speakerName = entry.speakerName?.takeIf { it.isNotBlank() }
    speakerName?.let {
        container.addView(
            historyTextView(
                context = container.context,
                text = it,
                color = entry.speakerNameColor ?: AndroidColor.WHITE,
                typeface = typeface,
                contentSizing = contentSizing,
                bottomMarginBaseDp = HISTORY_SPEAKER_BOTTOM_MARGIN_BASE_DP
            )
        )
    }
    entry.dialogueText?.takeIf { it.isNotBlank() }?.let { unmarked ->
        val it = RubyRenderText.forDisplay(EmphasisText.encode(unmarked, entry.dialogueEmphasisRanges), showAnnotations)
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
                        contentSizing = contentSizing,
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
                        contentSizing = contentSizing,
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
                    contentSizing = contentSizing,
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
                text = RubyRenderText.forDisplay(EmphasisText.encode(choice,
                    entry.choiceEmphasisRanges.getOrElse(index) { emptyList() }), showAnnotations) +
                    if (originalChoice == null) "\n" else "",
                color = FGO_CHOICE_HISTORY_RED,
                gravity = Gravity.CENTER,
                typeface = typeface,
                contentSizing = contentSizing,
                bottomMarginBaseDp = if (originalChoice == null) {
                    HISTORY_TEXT_BOTTOM_MARGIN_BASE_DP
                } else {
                    HISTORY_TEXT_WITH_ORIGINAL_BOTTOM_MARGIN_BASE_DP
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
                    contentSizing = contentSizing,
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
    contentSizing: HistoryContentSizing,
    color: Int,
): LinearLayout {
    val quotedDialogue = HistorySpeakerDialogueFormatter.prepare(text)
    val quoteWidth = historyQuoteIndentPx(
        typeface,
        contentSizing,
        quotedDialogue.openingQuote
    )
    val bodyText = quotedDialogue.body
    val originalBodyText = originalText?.trim()?.takeIf { it.isNotBlank() }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = contentSizing.spacingPx(HISTORY_DIALOGUE_BOTTOM_MARGIN_BASE_DP)
        }
        addView(
            historyTextView(
                context = context,
                text = quotedDialogue.openingQuote,
                color = AndroidColor.WHITE,
                typeface = typeface,
                contentSizing = contentSizing
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
                            text = quoteSpeakerDialogueClosing(
                                bodyText,
                                quotedDialogue.closingQuote,
                                quotedDialogue.trailingPunctuation
                            ),
                            color = color,
                            typeface = typeface,
                            contentSizing = contentSizing,
                            useDialogueLineHeight = true
                        )
                    )
                } else {
                    addHistoryBilingualLinePairs(
                        container = this,
                        context = context,
                        translationLines = historyDisplayLines(bodyText)
                            .withClosingQuoteOnLastLine(
                                quotedDialogue.closingQuote,
                                quotedDialogue.trailingPunctuation
                            ),
                        originalLines = historyDisplayLines(originalBodyText),
                        translationColor = color,
                        typeface = typeface,
                        contentSizing = contentSizing,
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
    contentSizing: HistoryContentSizing,
): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = contentSizing.spacingPx(HISTORY_DIALOGUE_BOTTOM_MARGIN_BASE_DP)
        }
        addHistoryBilingualLinePairs(
            container = this,
            context = context,
            translationLines = historyDisplayLines(translationText),
            originalLines = historyDisplayLines(originalText),
            translationColor = translationColor,
            typeface = typeface,
            contentSizing = contentSizing,
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
    contentSizing: HistoryContentSizing,
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
                    contentSizing = contentSizing,
                    bottomMarginBaseDp = if (hasOriginal) {
                        HISTORY_TEXT_WITH_ORIGINAL_BOTTOM_MARGIN_BASE_DP
                    } else {
                        HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_BASE_DP
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
                    contentSizing = contentSizing,
                    bottomMarginBaseDp = if (index == pairCount - 1) {
                        0
                    } else {
                        HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_BASE_DP
                    }
                )
            )
        }
    }
}

private fun historyDisplayLines(text: String): List<String> {
    if (EmphasisText.has(text)) return EmphasisText.lines(text).map { EmphasisText.transform(it, String::trim) }
        .filter { EmphasisText.plain(it).isNotBlank() }
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(text.trim()) }
}

private fun List<String>.withClosingQuoteOnLastLine(
    closingQuote: String = "」",
    trailingPunctuation: String = ""
): List<CharSequence> {
    return mapIndexed { index, line ->
        if (index == lastIndex) {
            quoteSpeakerDialogueClosing(line, closingQuote, trailingPunctuation)
        } else {
            line
        }
    }
}

private fun quoteSpeakerDialogueClosing(
    text: String,
    closingQuote: String = "」",
    trailingPunctuation: String = ""
): CharSequence {
    val quotedText = "$text$closingQuote$trailingPunctuation"
    val closingQuoteIndex = text.length
    return SpannableString(quotedText).apply {
        setSpan(
            ForegroundColorSpan(AndroidColor.WHITE),
            closingQuoteIndex,
            closingQuoteIndex + closingQuote.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

private fun historyQuoteIndentPx(
    typeface: Typeface,
    contentSizing: HistoryContentSizing,
    openingQuote: String
): Int {
    val quotePaint = TextPaint().apply {
        textSize = contentSizing.translationTextSizePx
        this.typeface = typeface
    }
    return quotePaint.measureText(openingQuote).roundToInt().coerceAtLeast(1)
}

private fun historyTextView(
    context: Context,
    text: CharSequence,
    typeface: Typeface,
    contentSizing: HistoryContentSizing,
    color: Int = AndroidColor.WHITE,
    gravity: Int = Gravity.START,
    textSizePx: Float = contentSizing.translationTextSizePx,
    bottomMarginBaseDp: Int = HISTORY_TEXT_BOTTOM_MARGIN_BASE_DP,
    useDialogueLineHeight: Boolean = false,
    rubyMode: RubyRenderText.Mode = RubyRenderText.Mode.ABOVE_BASE
): TextView {
    val view = if (RubyRenderText.parse(text.toString(), rubyMode).annotations.isNotEmpty()) {
        RubyTextView(context)
    } else {
        TextView(context)
    }
    return view.apply {
        if (this is RubyTextView) setRubyText(text) else this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        val displayedTextSizePx = this.textSize
        this.typeface = typeface
        paint.isFakeBoldText = false
        paint.isSubpixelText = true
        paint.isLinearText = true
        if (useDialogueLineHeight) {
            setLineHeight((displayedTextSizePx * HISTORY_DIALOGUE_LINE_HEIGHT_MULTIPLIER).roundToInt())
        } else {
            setLineSpacing(
                contentSizing.spacingPx(HISTORY_LINE_SPACING_EXTRA_BASE_DP).toFloat(),
                HISTORY_LINE_SPACING_MULTIPLIER
            )
            setPadding(
                0,
                0,
                0,
                contentSizing.spacingPx(3)
            )
        }
        includeFontPadding = false
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = contentSizing.spacingPx(bottomMarginBaseDp)
        }
    }
}

private fun historyOriginalTextView(
    context: Context,
    text: String,
    typeface: Typeface,
    contentSizing: HistoryContentSizing,
    gravity: Int = Gravity.START,
    bottomMarginBaseDp: Int = HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_BASE_DP
): TextView {
    return historyTextView(
        context = context,
        text = text,
        color = HISTORY_ORIGINAL_TEXT_COLOR,
        gravity = gravity,
        typeface = typeface,
        contentSizing = contentSizing,
        textSizePx = contentSizing.originalTextSizePx,
        bottomMarginBaseDp = bottomMarginBaseDp,
        rubyMode = RubyRenderText.Mode.INLINE
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
        return (view as? RecyclerView)?.computeVerticalScrollOffset() ?: view.scrollY
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

private fun historyTypeface(
    context: Context,
    targetLocale: String = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
): Typeface {
    return FgoTypefaceProvider.storyTypeface(context, targetLocale)
}

private fun dp(context: Context, value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).roundToInt()
