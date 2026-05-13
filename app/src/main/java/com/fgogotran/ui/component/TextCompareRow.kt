package com.fgogotran.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable side-by-side JP/CN text comparison row.
 *
 * Displays two equal-width columns:
 * - Left: JP text with a "JP" label in the primary color
 * - Right: CN text with a "CN" label in the secondary color
 */
@Composable
fun TextCompareRow(
    japaneseText: String,
    chineseText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "JP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = japaneseText,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "CN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = chineseText,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
