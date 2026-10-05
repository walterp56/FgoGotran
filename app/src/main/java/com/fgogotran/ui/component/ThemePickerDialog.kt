package com.fgogotran.ui.component

import androidx.annotation.StringRes
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.ui.theme.FgoUiColors
import com.fgogotran.data.AppThemeMode

@StringRes
internal fun themeLabelRes(mode: AppThemeMode): Int = when (mode) {
    AppThemeMode.SYSTEM -> R.string.app_theme_system
    AppThemeMode.LIGHT -> R.string.app_theme_light
    AppThemeMode.DARK -> R.string.app_theme_dark
}

@Composable
fun ThemePickerDialog(
    selectedMode: AppThemeMode,
    onDismiss: () -> Unit,
    onSelect: (AppThemeMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_theme_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                AppThemeMode.entries.forEach { mode ->
                    val selected = mode == selectedMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { onSelect(mode) }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(themeLabelRes(mode)),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (mode == AppThemeMode.SYSTEM) {
                                Text(
                                    stringResource(R.string.app_theme_system_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(colors = FgoUiColors.textButtonColors(), onClick = onDismiss) {
                Text(stringResource(R.string.app_theme_close))
            }
        }
    )
}
