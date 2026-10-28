package com.fgogotran.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as PreviewText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fgogotran.R
import com.fgogotran.localization.LocalizedText as Text
import com.fgogotran.overlay.DialoguePlaybackCharacters
import com.fgogotran.overlay.DialoguePlaybackController
import com.fgogotran.overlay.DialoguePlaybackSettings
import com.fgogotran.overlay.DialoguePlaybackTimeline
import com.fgogotran.overlay.DialogueTextSpeed
import com.fgogotran.overlay.FgoTypefaceProvider
import com.fgogotran.ui.theme.FgoSettingsColors
import kotlin.math.roundToInt

@Composable
internal fun DialoguePlaybackPreferences(
    settings: DialoguePlaybackSettings, targetLocale: String, bilingual: Boolean,
    onSpeed: (DialogueTextSpeed) -> Unit, onPause: (Int) -> Unit
) {
    var replay by remember { mutableIntStateOf(0) }
    var speed by remember(settings.speed) { mutableStateOf(settings.speed) }
    var pause by remember(settings.pauseLevel) { mutableIntStateOf(settings.pauseLevel) }
    var previewSettings by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { previewSettings = settings }
    val pauseLabel = stringResource(when (pause) {
        0 -> R.string.dialogue_playback_pause_none
        1 -> R.string.dialogue_playback_pause_short
        2 -> R.string.dialogue_playback_pause_medium
        else -> R.string.dialogue_playback_pause_long
    })

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlaybackValueRow(stringResource(R.string.dialogue_playback_speed),
                if (speed == DialogueTextSpeed.INSTANT) speed.label else "${speed.label}×")
            FgoSettingsSlider(speed.ordinal.toFloat(), {
                speed = DialogueTextSpeed.entries[it.roundToInt().coerceIn(0, 5)]
            }, modifier = Modifier.fillMaxWidth(), valueRange = 0f..5f, steps = 4,
                onValueChangeFinished = {
                    previewSettings = DialoguePlaybackSettings(speed, pause)
                    onSpeed(speed)
                })
            PlaybackRangeLabels(stringResource(R.string.dialogue_playback_slow),
                stringResource(R.string.dialogue_playback_instant))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlaybackValueRow(stringResource(R.string.dialogue_playback_pause),
                stringResource(R.string.dialogue_playback_pause_value, pause, pauseLabel))
            FgoSettingsSlider(pause.toFloat(), { pause = it.roundToInt().coerceIn(0, 3) },
                modifier = Modifier.fillMaxWidth(), valueRange = 0f..3f, steps = 2,
                onValueChangeFinished = {
                    previewSettings = DialoguePlaybackSettings(speed, pause)
                    onPause(pause)
                })
            PlaybackRangeLabels(stringResource(R.string.dialogue_playback_pause_none),
                stringResource(R.string.dialogue_playback_pause_long))
            Text(stringResource(R.string.dialogue_playback_max_hint),
                style = MaterialTheme.typography.bodySmall, color = FgoSettingsColors.supporting)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dialogue_playback_preview), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { previewSettings = DialoguePlaybackSettings(speed, pause); replay++ }) {
                    Text("↻", modifier = Modifier.clearAndSetSemantics { }, fontSize = 20.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dialogue_playback_replay))
                }
            }
            DialoguePlaybackPreview(previewSettings, targetLocale, bilingual, replay)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dialogue_playback_hint), style = MaterialTheme.typography.bodySmall,
                color = FgoSettingsColors.supporting)
            Text(stringResource(R.string.dialogue_playback_next_scene), style = MaterialTheme.typography.bodySmall,
                color = FgoSettingsColors.supporting)
        }
    }
}

@Composable
private fun PlaybackValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = FgoSettingsColors.value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlaybackRangeLabels(start: String, end: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, style = MaterialTheme.typography.bodySmall, color = FgoSettingsColors.supporting)
        Text(end, style = MaterialTheme.typography.bodySmall, color = FgoSettingsColors.supporting)
    }
}

@Composable
private fun DialoguePlaybackPreview(settings: DialoguePlaybackSettings, locale: String, bilingual: Boolean, replay: Int) {
    val context = LocalContext.current
    val example = remember(locale) { DialoguePlaybackExample.forTarget(locale) }
    val family = remember(context, locale) { FontFamily(FgoTypefaceProvider.storyTypeface(context, locale)) }
    val timeline = remember(example, settings) {
        DialoguePlaybackTimeline(example.text, DialoguePlaybackCharacters.ends(example.text), settings)
    }
    val visibleEnd = remember { mutableIntStateOf(0) }
    LaunchedEffect(timeline, replay) {
        val controller = DialoguePlaybackController()
        visibleEnd.intValue = 0
        var time = withFrameNanos { it }
        controller.start(timeline, time)
        try {
            while (true) {
                visibleEnd.intValue = controller.visibleEnd(time)
                if (!controller.isRevealing(time)) break
                time = withFrameNanos { it }
            }
        } finally { controller.cancel() }
    }
    // Settings text uses sp and wraps naturally. The actual game renderer keeps its pixel geometry.
    Surface(color = Color(0xFF1B304E), shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            example.lines.forEachIndexed { index, line ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RevealedPreviewLine(line, example.starts[index], visibleEnd, family)
                    if (bilingual) PreviewText(example.originalLines[index], color = Color(0xFF50EBEB),
                        fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun RevealedPreviewLine(text: String, start: Int, visibleEnd: IntState, family: FontFamily) {
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    // Always lay out the complete line; only the draw pass reads the changing reveal state.
    // This preserves wrapping and gives accessibility services a stable complete sentence.
    PreviewText(text, color = Color(0xFFF5F5F0), fontFamily = family, fontSize = 20.sp, lineHeight = 28.sp,
        onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth().drawWithContent {
            val end = (visibleEnd.intValue - start).coerceIn(0, text.length)
            val measured = layout
            if (end == text.length) drawContent()
            else if (end > 0 && measured != null) clipPath(measured.getPathForRange(0, end)) {
                this@drawWithContent.drawContent()
            }
        })
}
