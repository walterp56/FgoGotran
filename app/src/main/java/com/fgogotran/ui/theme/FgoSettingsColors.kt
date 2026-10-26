package com.fgogotran.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Preference values describe a choice, so they do not use readiness or warning colours. */
internal object FgoSettingsColors {
    val value: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    val supporting: Color
        @Composable get() = FgoUiColors.text(darkAlpha = 0.6f, secondary = true)

    /** Only unavailable controls are dimmed; an ordinary OFF preference remains readable. */
    @Composable
    fun value(enabled: Boolean): Color = if (enabled) {
        value
    } else {
        FgoUiColors.disabledContent(value.copy(alpha = 0.38f), enabled = false)
    }

    @Composable
    fun supporting(enabled: Boolean): Color = if (enabled) supporting else value(enabled = false)
}
