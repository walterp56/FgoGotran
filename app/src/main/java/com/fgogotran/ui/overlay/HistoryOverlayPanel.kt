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
import android.view.View
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fgogotran.data.SettingsRepository
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory
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

@Composable
fun HistoryOverlayPanel(onDismiss: () -> Unit) {
    val entries by SessionTranslationHistory.entries.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val panelWidth = if (maxWidth / maxHeight >= 16f / 9f) {
                maxHeight * 16f / 9f
            } else {
                maxWidth
            }
            val panelHeight = panelWidth * 9f / 16f

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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun HistoryScrollView(
    entries: List<SessionTranslationEntry>,
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    verticalScrollbarThumbDrawable = ColorDrawable(AndroidColor.WHITE)
                }

                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(context, 20), dp(context, 14), dp(context, 28), dp(context, 14))
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
                        historyTypeface(scrollView.context, entry.targetLocale)
                    )
                    if (index != entries.lastIndex) {
                        container.addView(spacerView(scrollView.context, 12))
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
    typeface: Typeface
) {
    val speakerName = entry.speakerName?.takeIf { it.isNotBlank() }
    speakerName?.let {
        container.addView(
            historyTextView(
                context = container.context,
                text = it,
                color = entry.speakerNameColor ?: AndroidColor.WHITE,
                typeface = typeface,
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
                        typeface = typeface
                    )
                )
            } else if (originalDialogue != null) {
                container.addView(
                    historyBilingualLinesView(
                        context = container.context,
                        translationText = it,
                        originalText = originalDialogue,
                        translationColor = entry.dialogueTextColor ?: AndroidColor.WHITE,
                        typeface = typeface
                    )
                )
            }
        } else {
            container.addView(
                historyTextView(
                    context = container.context,
                    text = it,
                    color = entry.dialogueTextColor ?: AndroidColor.WHITE,
                    typeface = typeface
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
                    typeface = typeface
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
    color: Int
): LinearLayout {
    val quoteWidth = historyQuoteIndentPx(context, typeface)
    val bodyText = quoteSpeakerDialogueBody(text)
    val originalBodyText = originalText?.trim()?.takeIf { it.isNotBlank() }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, HISTORY_DIALOGUE_BOTTOM_MARGIN_DP)
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
                            typeface = typeface
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
                        typeface = typeface
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
    typeface: Typeface
): LinearLayout {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, HISTORY_DIALOGUE_BOTTOM_MARGIN_DP)
        }
        addHistoryBilingualLinePairs(
            container = this,
            context = context,
            translationLines = historyDisplayLines(translationText),
            originalLines = historyDisplayLines(originalText),
            translationColor = translationColor,
            typeface = typeface
        )
    }
}

private fun addHistoryBilingualLinePairs(
    container: LinearLayout,
    context: Context,
    translationLines: List<CharSequence>,
    originalLines: List<String>,
    translationColor: Int,
    typeface: Typeface
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

private fun historyQuoteIndentPx(context: Context, typeface: Typeface): Int {
    val quotePaint = TextPaint().apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            HISTORY_TEXT_SIZE_SP,
            context.resources.displayMetrics
        )
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
    bottomMarginDp: Int = HISTORY_TEXT_BOTTOM_MARGIN_DP
): TextView {
    return TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = textSizeSp
        this.typeface = typeface
        paint.isFakeBoldText = false
        paint.isSubpixelText = true
        paint.isLinearText = true
        setLineSpacing(dp(context, HISTORY_LINE_SPACING_EXTRA_DP).toFloat(), HISTORY_LINE_SPACING_MULTIPLIER)
        includeFontPadding = false
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, bottomMarginDp)
        }
    }
}

private fun historyOriginalTextView(
    context: Context,
    text: String,
    typeface: Typeface,
    gravity: Int = Gravity.START,
    bottomMarginDp: Int = HISTORY_ORIGINAL_TEXT_BOTTOM_MARGIN_DP
): TextView {
    return historyTextView(
        context = context,
        text = text,
        color = HISTORY_ORIGINAL_TEXT_COLOR,
        gravity = gravity,
        typeface = typeface,
        textSizeSp = HISTORY_ORIGINAL_TEXT_SIZE_SP,
        bottomMarginDp = bottomMarginDp
    )
}

private fun spacerView(context: Context, heightDp: Int): View {
    return View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, heightDp)
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
