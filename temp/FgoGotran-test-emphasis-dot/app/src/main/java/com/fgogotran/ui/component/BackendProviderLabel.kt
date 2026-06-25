package com.fgogotran.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository

@Composable
fun BackendProviderLabel(
    backend: String,
    label: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement
    ) {
        providerIcon(backend)?.let { icon ->
            ProviderIcon(icon)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, style = textStyle)
    }
}

@Composable
private fun ProviderIcon(icon: ProviderIconSpec) {
    Image(
        painter = painterResource(id = icon.drawableRes),
        contentDescription = null,
        modifier = Modifier
            .size(width = icon.width, height = icon.height)
    )
}

private data class ProviderIconSpec(
    @DrawableRes val drawableRes: Int,
    val width: Dp = 22.dp,
    val height: Dp = 22.dp
)

private fun providerIcon(backend: String): ProviderIconSpec? {
    return when (SettingsRepository.normalizeBackend(backend)) {
        SettingsRepository.BACKEND_DEEPSEEK -> ProviderIconSpec(
            drawableRes = R.drawable.ic_deepseek_mark,
            width = 24.dp,
            height = 18.dp
        )
        SettingsRepository.BACKEND_ZHIPU -> ProviderIconSpec(
            drawableRes = R.drawable.ic_zhipu_mark,
            width = 38.dp,
            height = 13.dp
        )
        SettingsRepository.BACKEND_QWEN -> ProviderIconSpec(R.drawable.ic_qwen_mark)
        SettingsRepository.BACKEND_CLAUDE -> ProviderIconSpec(R.drawable.ic_claude_mark)
        SettingsRepository.BACKEND_GPT -> ProviderIconSpec(R.drawable.ic_openai_mark)
        SettingsRepository.BACKEND_GEMINI -> ProviderIconSpec(R.drawable.ic_gemini_mark)
        SettingsRepository.BACKEND_CUSTOM_OPENAI -> ProviderIconSpec(R.drawable.ic_custom_api_mark)
        else -> null
    }
}
