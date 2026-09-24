package com.fgogotran.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.localization.AppLanguageManager

private data class AppLanguageDialogOption(
    val language: String,
    val label: String,
    val description: String
)

/**
 * Compact language picker used from the home-page globe button.
 *
 * Language names stay in their own script, matching common app-launcher and
 * system-settings patterns, while the dialog chrome follows the current app language.
 */
@Composable
fun LanguagePickerDialog(
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val normalizedSelected = AppLanguageManager.normalizeLanguage(selectedLanguage)
    val options = listOf(
        AppLanguageDialogOption(
            language = AppLanguageManager.LANGUAGE_SYSTEM,
            label = stringResource(R.string.ui_language_system),
            description = stringResource(R.string.ui_language_follow_system)
        ),
        AppLanguageDialogOption(
            language = AppLanguageManager.LANGUAGE_ENGLISH,
            label = "English",
            description = ""
        ),
        AppLanguageDialogOption(
            language = AppLanguageManager.LANGUAGE_TRADITIONAL,
            label = "繁體中文",
            description = ""
        ),
        AppLanguageDialogOption(
            language = AppLanguageManager.LANGUAGE_SIMPLIFIED,
            label = "简体中文",
            description = ""
        )
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.ui_language_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { option ->
                    LanguageOptionRow(
                        option = option,
                        selected = option.language == normalizedSelected,
                        onSelect = onSelect
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_language_close))
            }
        }
    )
}

@Composable
private fun LanguageOptionRow(
    option: AppLanguageDialogOption,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(option.language) }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (option.description.isNotBlank()) {
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = if (selected) "✓" else "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}


