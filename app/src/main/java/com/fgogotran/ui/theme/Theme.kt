package com.fgogotran.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
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

/** Independent light palette: achromatic surfaces with restrained purple and blue accents. */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF76518F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1EEF4),
    onPrimaryContainer = Color(0xFF402A50),
    secondary = Color(0xFF606060),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7E7E7),
    onSecondaryContainer = Color(0xFF292929),
    tertiary = Color(0xFF875A20),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEE1C7),
    onTertiaryContainer = Color(0xFF4A3618),
    background = Color(0xFFEBEBEB),
    onBackground = Color(0xFF292929),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF292929),
    surfaceVariant = Color(0xFFE7E7E7),
    onSurfaceVariant = Color(0xFF606060),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF292929),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFD6B6E6),
    error = Color(0xFFA63D40),
    onError = Color.White,
    errorContainer = Color(0xFFF4DDDE),
    onErrorContainer = Color(0xFF531F22),
    outline = Color(0xFF838383),
    outlineVariant = Color(0xFFD1D1D1),
    scrim = Color.Black,
    surfaceDim = Color(0xFFDADADA),
    surfaceBright = Color(0xFFF5F5F5),
    surfaceContainerLowest = Color(0xFFF5F5F5),
    surfaceContainerLow = Color(0xFFF0F0F0),
    surfaceContainer = Color(0xFFECECEC),
    surfaceContainerHigh = Color(0xFFE7E7E7),
    surfaceContainerHighest = Color(0xFFE0E0E0),
)

private val LightShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Scoped to the Activity UI; fixed game-facing overlays do not use this theme. */
internal val LocalFgoDarkTheme = staticCompositionLocalOf { true }

internal object FgoUiColors {
    private val lightInformation = Color(0xFF315E8C)
    private val lightBadgeContainer = Color(0xFFEFEFEF)
    private val disabledText = Color(0xFF606060)

    /** Light page cards stay neutral; colour is reserved for content and controls. */
    @Composable
    fun cardColors(darkContainerColor: Color = MaterialTheme.colorScheme.surface): CardColors =
        CardDefaults.cardColors(
            containerColor = if (LocalFgoDarkTheme.current) darkContainerColor else MaterialTheme.colorScheme.surface
        )

    @Composable
    fun sectionContainer(darkColor: Color): Color =
        if (LocalFgoDarkTheme.current) darkColor else MaterialTheme.colorScheme.surfaceContainerHigh

    /** Neutral light badge backgrounds keep the purple icons and labels restrained. */
    @Composable
    fun accentContainer(darkColor: Color): Color =
        if (LocalFgoDarkTheme.current) darkColor else lightBadgeContainer

    @Composable
    fun accentContent(darkColor: Color): Color =
        if (LocalFgoDarkTheme.current) darkColor else MaterialTheme.colorScheme.primary

    /** Ordinary information boxes stay neutral; filled guide examples retain blue. */
    @Composable
    fun informationContainer(darkColor: Color, filled: Boolean = false): Color =
        if (LocalFgoDarkTheme.current) darkColor else if (filled) {
            lightInformation
        } else {
            lightBadgeContainer
        }

    /** Matches the blue text positions in dark mode without recolouring controls or icons. */
    val blueText: Color
        @Composable get() = if (LocalFgoDarkTheme.current) MaterialTheme.colorScheme.primary else lightInformation

    @Composable
    fun outlinedButtonColors(): ButtonColors = if (LocalFgoDarkTheme.current) {
        ButtonDefaults.outlinedButtonColors()
    } else {
        ButtonDefaults.outlinedButtonColors(contentColor = lightInformation)
    }

    @Composable
    fun textButtonColors(): ButtonColors = if (LocalFgoDarkTheme.current) {
        ButtonDefaults.textButtonColors()
    } else {
        ButtonDefaults.textButtonColors(contentColor = lightInformation)
    }

    @Composable
    fun filterChipColors(): SelectableChipColors = if (LocalFgoDarkTheme.current) {
        FilterChipDefaults.filterChipColors()
    } else {
        FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
        )
    }

    /** Dark styling is kept verbatim; light text uses solid, readable semantic colours. */
    @Composable
    fun text(darkAlpha: Float, secondary: Boolean = false, enabled: Boolean = true): Color =
        text(MaterialTheme.colorScheme.onSurface.copy(alpha = darkAlpha), secondary, enabled)

    @Composable
    fun text(darkColor: Color, secondary: Boolean = false, enabled: Boolean = true): Color =
        if (LocalFgoDarkTheme.current) darkColor else when {
            !enabled -> disabledText
            secondary -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }

    @Composable
    fun disabledContent(darkColor: Color, enabled: Boolean): Color =
        if (LocalFgoDarkTheme.current || enabled) darkColor else disabledText

    @Composable
    fun optionContainer(darkColor: Color, selected: Boolean = false, enabled: Boolean = true): Color =
        if (LocalFgoDarkTheme.current) darkColor else if (selected && enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    @Composable
    fun controlOutline(darkColor: Color, selected: Boolean = false, enabled: Boolean = true): Color =
        if (LocalFgoDarkTheme.current) darkColor else when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }

    @Composable
    fun outlinedTextFieldColors(): TextFieldColors = if (LocalFgoDarkTheme.current) {
        OutlinedTextFieldDefaults.colors()
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedLabelColor = lightInformation,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledTextColor = disabledText,
            disabledLabelColor = disabledText,
            disabledPlaceholderColor = disabledText,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun topAppBarColors(): TopAppBarColors = if (LocalFgoDarkTheme.current) {
        TopAppBarDefaults.topAppBarColors()
    } else {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    val success: Color
        @Composable get() = if (LocalFgoDarkTheme.current) Color(0xFF4CAF50) else Color(0xFF2F6B4F)

    val warning: Color
        @Composable get() = if (LocalFgoDarkTheme.current) Color(0xFFFF9800) else Color(0xFF875A20)
}

/** Light-only component styling; dark branches return the existing Material defaults. */
internal object FgoUiStyle {
    val cardBorder: BorderStroke?
        @Composable get() = if (LocalFgoDarkTheme.current) null else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }

    @Composable
    fun controlShape(darkShape: Shape): Shape =
        if (LocalFgoDarkTheme.current) darkShape else MaterialTheme.shapes.small

    val buttonShape: Shape
        @Composable get() = controlShape(ButtonDefaults.shape)

    val fabShape: Shape
        @Composable get() = controlShape(FloatingActionButtonDefaults.extendedFabShape)

    @Composable
    fun fabElevation(): FloatingActionButtonElevation = if (LocalFgoDarkTheme.current) {
        FloatingActionButtonDefaults.elevation()
    } else {
        FloatingActionButtonDefaults.elevation(
            defaultElevation = 2.dp,
            pressedElevation = 2.dp,
            focusedElevation = 2.dp,
            hoveredElevation = 2.dp,
        )
    }
}

/** The caller resolves the saved preference against the phone's current night mode. */
@Composable
fun FgoGotranTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        val background = colorScheme.background.toArgb()
        val backgroundDrawable = remember(background) { ColorDrawable(background) }
        SideEffect {
            val window = (view.context as Activity).window
            window.setBackgroundDrawable(backgroundDrawable)
            window.statusBarColor = background
            window.navigationBarColor = background
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalFgoDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = if (darkTheme) MaterialTheme.shapes else LightShapes,
            content = content,
        )
    }
}
