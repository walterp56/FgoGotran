package com.fgogotran.ui.overlay

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
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
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory

private val FGO_CHOICE_HISTORY_RED = AndroidColor.rgb(246, 58, 60)

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
            val typeface = historyTypeface(scrollView.context)

            if (entries.isEmpty()) {
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
                    addHistoryEntryViews(container, entry, typeface)
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
                typeface = typeface
            )
        )
    }
    entry.dialogueText?.takeIf { it.isNotBlank() }?.let {
        container.addView(
            historyTextView(
                context = container.context,
                text = it,
                color = entry.dialogueTextColor ?: AndroidColor.WHITE,
                typeface = typeface
            )
        )
    }
    entry.choices.forEachIndexed { index, choice ->
        if (choice.isBlank()) return@forEachIndexed
        container.addView(
            historyTextView(
                context = container.context,
                text = "$choice\n",
                color = FGO_CHOICE_HISTORY_RED,
                gravity = Gravity.CENTER,
                typeface = typeface
            )
        )
    }
}

private fun historyTextView(
    context: Context,
    text: String,
    typeface: Typeface,
    color: Int = AndroidColor.WHITE,
    gravity: Int = Gravity.START
): TextView {
    return TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = 18f
        this.typeface = typeface
        paint.isFakeBoldText = false
        paint.isSubpixelText = true
        paint.isLinearText = true
        includeFontPadding = false
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 3)
        }
    }
}

private fun spacerView(context: Context, heightDp: Int): View {
    return View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, heightDp)
        )
    }
}

private fun historyTypeface(context: Context): Typeface {
    return FgoTypefaceProvider.storyTypeface(context)
}

private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).toInt()
}
