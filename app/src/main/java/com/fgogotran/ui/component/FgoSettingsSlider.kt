package com.fgogotran.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fgogotran.ui.theme.FgoGotranTheme

/** Compact visuals with Material's native touch target, gestures and slider semantics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FgoSettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            // A fixed circle avoids the default handle narrowing during press or drag.
            Spacer(
                modifier = Modifier
                    .size(20.dp)
                    .shadow(if (enabled) 1.dp else 0.dp, CircleShape, clip = false)
                    .background(
                        color = if (enabled) colors.thumbColor else colors.disabledThumbColor,
                        shape = CircleShape
                    )
                    .hoverable(interactionSource, enabled = enabled)
                    .indication(interactionSource, ripple(bounded = false, radius = 20.dp))
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(4.dp),
                enabled = enabled,
                colors = colors,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
                drawStopIndicator = null,
                drawTick = { offset, color ->
                    // Keep sparse stops readable without cluttering volume and size tracks.
                    if (steps in 1..4) {
                        drawCircle(color = color, radius = 1.dp.toPx(), center = offset)
                    }
                }
            )
        }
    )
}

@Preview(name = "Compact sliders - light", widthDp = 360)
@Composable
private fun FgoSettingsSliderLightPreview() {
    FgoSettingsSliderPreview(darkTheme = false)
}

@Preview(name = "Compact sliders - dark", widthDp = 360)
@Composable
private fun FgoSettingsSliderDarkPreview() {
    FgoSettingsSliderPreview(darkTheme = true)
}

@Composable
private fun FgoSettingsSliderPreview(darkTheme: Boolean) {
    FgoGotranTheme(darkTheme = darkTheme) {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                FgoSettingsSlider(0f, {}, modifier = Modifier.fillMaxWidth())
                FgoSettingsSlider(0.5f, {}, modifier = Modifier.fillMaxWidth(), steps = 99)
                FgoSettingsSlider(3f, {}, modifier = Modifier.fillMaxWidth(), valueRange = 1f..5f, steps = 3)
                FgoSettingsSlider(1f, {}, modifier = Modifier.fillMaxWidth())
                FgoSettingsSlider(0.5f, {}, modifier = Modifier.fillMaxWidth(), enabled = false)
            }
        }
    }
}
