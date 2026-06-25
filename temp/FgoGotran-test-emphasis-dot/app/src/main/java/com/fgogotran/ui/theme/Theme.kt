package com.fgogotran.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Material 3 dark color scheme for FgoGotran.
 *
 * Colors:
 * - Primary: Light blue (#90CAF9) — used for buttons, highlights, JP labels
 * - Secondary: Purple (#CE93D8) — used for CN labels, secondary elements
 * - Tertiary: Amber (#FFD54F) — used sparingly for accent
 * - Surface: Dark gray (#1E1E1E) — card backgrounds
 * - Background: Near-black (#121212) — root background, matches FGO's dark UI
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFCE93D8),
    tertiary = Color(0xFFFFD54F),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

/**
 * Applies the dark theme and sets the status bar color to match the background.
 * Uses [SideEffect] to run the window styling after composition, outside the render phase.
 */
@Composable
fun FgoGotranTheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
