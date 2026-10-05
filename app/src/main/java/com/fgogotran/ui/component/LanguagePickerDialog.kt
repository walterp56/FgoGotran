package com.fgogotran.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.ui.theme.FgoUiColors
import com.fgogotran.localization.AppLanguageManager

private data class AppLanguageDialogOption(
    val language: String,
    val label: String,
    val description: String
)

internal fun appLanguageLabel(language: String, followSystemLabel: String): String =
    when (AppLanguageManager.normalizeLanguage(language)) {
        AppLanguageManager.LANGUAGE_ENGLISH -> "English"
        AppLanguageManager.LANGUAGE_TRADITIONAL -> "繁體中文"
        AppLanguageManager.LANGUAGE_SIMPLIFIED -> "简体中文"
        else -> followSystemLabel
    }

/**
 * Compact app-language picker used from Settings > Appearance.
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
    val followSystemLabel = stringResource(R.string.ui_language_system)
    val options = listOf(
        AppLanguageManager.LANGUAGE_SYSTEM,
        AppLanguageManager.LANGUAGE_ENGLISH,
        AppLanguageManager.LANGUAGE_TRADITIONAL,
        AppLanguageManager.LANGUAGE_SIMPLIFIED
    ).map { language ->
        AppLanguageDialogOption(
            language = language,
            label = appLanguageLabel(language, followSystemLabel),
            description = if (language == AppLanguageManager.LANGUAGE_SYSTEM) {
                stringResource(R.string.ui_language_follow_system)
            } else ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.ui_language_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
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
            TextButton(colors = FgoUiColors.textButtonColors(), onClick = onDismiss) {
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
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton) { onSelect(option.language) }
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
            color = FgoUiColors.blueText
        )
    }
}


