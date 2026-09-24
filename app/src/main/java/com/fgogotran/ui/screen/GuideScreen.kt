package com.fgogotran.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.fgogotran.R
import com.fgogotran.localization.LocalizedText as Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guide_auto_30)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.guide_auto_38), color = MaterialTheme.colorScheme.primary)
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GuideWebsiteCard(onOpenWebsite = { openFgoGotranWebsite(context) })

            GuideScopeCard()

            GuideSectionCard(
                number = "1",
                title = stringResource(R.string.guide_auto_24),
                body = stringResource(R.string.guide_auto_12)
            ) {
                GuideSettingRow(label = stringResource(R.string.guide_auto_25), value = "MAX")
                GuideSettingRow(label = stringResource(R.string.guide_auto_21), value = "MAX")
                GuideSettingRow(label = stringResource(R.string.guide_auto_18), value = "0")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
                GuideInfoRow(
                    label = stringResource(R.string.guide_auto_31),
                    text = stringResource(R.string.guide_auto_1)
                )
            }

            GuideSectionCard(
                number = "2",
                title = stringResource(R.string.guide_auto_27),
                body = stringResource(R.string.guide_auto_15)
            ) {
                GuideModeRow(
                    mode = stringResource(R.string.guide_auto_39),
                    text = stringResource(R.string.guide_auto_10)
                )
                GuideModeRow(
                    mode = stringResource(R.string.guide_auto_35),
                    text = stringResource(R.string.guide_auto_6)
                )
                GuideModeRow(
                    mode = stringResource(R.string.guide_auto_36),
                    text = stringResource(R.string.guide_auto_11)
                )
                GuideModeRow(
                    mode = stringResource(R.string.guide_auto_40),
                    text = stringResource(R.string.guide_auto_13)
                )
            }

            GuideSectionCard(
                number = "3",
                title = stringResource(R.string.guide_auto_28),
                body = stringResource(R.string.guide_auto_14)
            ) {
                GuideBadgeRow(label = "GO", text = stringResource(R.string.guide_auto_26))
                GuideBadgeRow(label = stringResource(R.string.guide_auto_42), text = stringResource(R.string.guide_auto_19))
                GuideBadgeRow(label = stringResource(R.string.guide_auto_43), text = stringResource(R.string.guide_auto_16))
                GuideBadgeRow(label = stringResource(R.string.guide_auto_44), text = stringResource(R.string.guide_auto_3))
                GuideBadgeRow(label = stringResource(R.string.guide_auto_32), text = stringResource(R.string.guide_auto_9))
                GuideBadgeRow(label = "LOG", text = stringResource(R.string.guide_auto_22))
            }

            GuideSectionCard(
                number = "4",
                title = stringResource(R.string.guide_auto_29),
                body = stringResource(R.string.guide_auto_2)
            ) {
                GuideInfoRow(
                    label = stringResource(R.string.guide_auto_33),
                    text = stringResource(R.string.guide_auto_4)
                )
            }
        }
    }
}

@Composable
private fun GuideScopeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.guide_auto_34), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.guide_auto_7),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            GuideInfoRow(label = stringResource(R.string.guide_auto_41), text = stringResource(R.string.guide_auto_5))
            GuideInfoRow(label = stringResource(R.string.guide_auto_37), text = stringResource(R.string.guide_auto_8))
        }
    }
}

@Composable
private fun GuideWebsiteCard(onOpenWebsite: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.guide_auto_23),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                stringResource(R.string.guide_auto_17),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenWebsite
            ) {
                Text(stringResource(R.string.guide_auto_20))
            }
        }
    }
}

@Composable
private fun GuideSectionCard(
    number: String,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StepBadge(number)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StepBadge(number: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            number,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GuideModeRow(mode: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        ModeChip(mode)
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
    }
}

@Composable
private fun ModeChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun GuideBadgeRow(label: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = Color(0xFF23405F),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
    }
}

@Composable
private fun GuideSettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GuideInfoRow(label: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun openFgoGotranWebsite(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(FGOGOTRAN_WEBSITE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

private const val FGOGOTRAN_WEBSITE_URL = "https://fgogotran.com/"



