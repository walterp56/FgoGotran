package com.fgogotran.ui.component

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
        if (backend == SettingsRepository.BACKEND_DEEPSEEK) {
            DeepSeekProviderIcon()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, style = textStyle)
    }
}

@Composable
private fun DeepSeekProviderIcon() {
    Image(
        painter = painterResource(id = R.drawable.ic_deepseek_mark),
        contentDescription = null,
        modifier = Modifier
            .size(width = 24.dp, height = 18.dp)
    )
}
