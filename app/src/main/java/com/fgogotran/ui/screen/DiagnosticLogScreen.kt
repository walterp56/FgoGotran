package com.fgogotran.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fgogotran.diagnostic.DiagnosticEvent
import com.fgogotran.R
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.localization.AppLanguageManager
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogScreen(
    diagnosticEventStore: DiagnosticEventStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val events by diagnosticEventStore.events.collectAsState()
    var exportMessage by remember { mutableStateOf("") }

    val visibleEvents = remember(events) {
        events
            .sortedByDescending { it.timestampMs }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostic_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.diagnostic_back), color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.diagnostic_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val file = diagnosticEventStore.exportTextReport(visibleEvents)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_subject))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.diagnostic_share_title)))
                            }.onSuccess {
                                exportMessage = context.getString(R.string.diagnostic_txt_generated)
                            }.onFailure {
                                exportMessage = context.getString(R.string.diagnostic_export_failed)
                            }
                        }
                    },
                    enabled = visibleEvents.isNotEmpty()
                ) {
                    Text(stringResource(R.string.diagnostic_export_txt))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        diagnosticEventStore.clear()
                        exportMessage = context.getString(R.string.diagnostic_cleared)
                    },
                    enabled = events.isNotEmpty()
                ) {
                    Text(stringResource(R.string.diagnostic_clear))
                }
            }

            if (exportMessage.isNotBlank()) {
                Text(
                    exportMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (visibleEvents.isEmpty()) {
                EmptyDiagnosticState()
            } else {
                visibleEvents.forEach { event ->
                    DiagnosticEventRow(event)
                }
            }
        }
    }
}

@Composable
private fun EmptyDiagnosticState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Text(
            stringResource(R.string.diagnostic_empty),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
        )
    }
}

@Composable
private fun DiagnosticEventRow(event: DiagnosticEvent) {
    val context = LocalContext.current
    val colors = eventColors(event)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        event.metaLine(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                    )
                }
                SeverityBadge(event.level, colors)
            }
            event.bodyLine(context)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                )
            }
            event.detailLine(context)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
        }
    }
}

@Composable
private fun SeverityBadge(level: String, colors: DiagnosticColors) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.container,
        contentColor = colors.content
    ) {
        Text(
            levelLabel(level),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun eventColors(event: DiagnosticEvent): DiagnosticColors {
    return when {
        event.level == DiagnosticEventStore.LEVEL_ERROR -> DiagnosticColors(
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer
        )
        event.category == DiagnosticEventStore.CATEGORY_MISSING_VOICE -> DiagnosticColors(
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer
        )
        event.category == DiagnosticEventStore.CATEGORY_TEMP_VOICE_API -> DiagnosticColors(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer
        )
        else -> DiagnosticColors(
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private data class DiagnosticColors(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color
)

private fun DiagnosticEvent.metaLine(context: android.content.Context): String {
    return listOfNotNull(
        formatEventTime(timestampMs),
        server.takeIf { it.isNotBlank() }
            ?.let { AppLanguageManager.localizeUiText(context, it) },
        mode.takeIf { it.isNotBlank() }
            ?.let { AppLanguageManager.localizeUiText(context, it) },
        speaker.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
}

private fun DiagnosticEvent.bodyLine(context: android.content.Context): String? {
    return listOfNotNull(
        message.takeIf { it.isNotBlank() }
            ?.let { AppLanguageManager.localizeUiText(context, it) },
        voiceType.takeIf { it.isNotBlank() }?.let { "type=$it" },
        voiceName.takeIf { it.isNotBlank() }?.let { "voice=$it" },
        apiBackend.takeIf { it.isNotBlank() }?.let { "api=$it" },
        errorCode.takeIf { it.isNotBlank() }?.let { "code=$it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun DiagnosticEvent.detailLine(context: android.content.Context): String? {
    return listOfNotNull(
        detail.takeIf { it.isNotBlank() }
            ?.let { AppLanguageManager.localizeUiText(context, it) },
        textPreview.takeIf { it.isNotBlank() }?.let { "text=$it" }
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

@Composable
private fun levelLabel(level: String): String {
    return when (level) {
        DiagnosticEventStore.LEVEL_ERROR -> stringResource(R.string.diagnostic_error)
        DiagnosticEventStore.LEVEL_WARNING -> stringResource(R.string.diagnostic_note)
        else -> stringResource(R.string.diagnostic_record)
    }
}

private fun formatEventTime(timestampMs: Long): String {
    return runCatching {
        Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(TIME_FORMATTER)
    }.getOrDefault("")
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

