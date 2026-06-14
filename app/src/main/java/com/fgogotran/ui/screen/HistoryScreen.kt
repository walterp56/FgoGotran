package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fgogotran.translation.SessionTranslationEntry
import com.fgogotran.translation.SessionTranslationHistory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit
) {
    val items by SessionTranslationHistory.entries.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话历史（${items.size}）") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "本次服务还没有翻译历史。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { translation ->
                    HistoryItem(translation)
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(translation: SessionTranslationEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val titleColor = translation.speakerNameColor
                ?: if (translation.speakerName == null) translation.choiceColors.firstOrNull() else null
            Text(
                text = translation.speakerName
                    ?: translation.choices.firstOrNull()
                    ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor?.let { Color(it) } ?: Color.Unspecified
            )
            translation.dialogueText?.let {
                Spacer(modifier = Modifier.height(4.dp))
                val dialogueColor = translation.dialogueTextColor?.let { color -> Color(color) } ?: Color.Unspecified
                if (!translation.speakerName.isNullOrBlank()) {
                    SpeakerDialogueText(text = it, color = dialogueColor)
                } else {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = dialogueColor
                    )
                }
            }
            val droppedChoices = if (translation.speakerName == null) 1 else 0
            translation.choices.drop(droppedChoices).forEachIndexed { index, choice ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = choice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = translation.choiceColors.getOrNull(index + droppedChoices)
                        ?.let { Color(it) }
                        ?: Color.Unspecified
                )
            }
        }
    }
}

@Composable
private fun SpeakerDialogueText(text: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "「",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        Text(
            text = quoteSpeakerDialogueClosing(quoteSpeakerDialogueBody(text)),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
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

private fun quoteSpeakerDialogueClosing(text: String): AnnotatedString {
    val quotedText = "$text」"
    return buildAnnotatedString {
        append(quotedText.substring(0, quotedText.lastIndex))
        withStyle(SpanStyle(color = Color.White)) {
            append(quotedText.last().toString())
        }
    }
}
