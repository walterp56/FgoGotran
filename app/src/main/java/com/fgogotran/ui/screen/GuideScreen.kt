package com.fgogotran.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用指南") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GuideWebsiteCard(onOpenWebsite = { openFgoGotranWebsite(context) })

            GuideScopeCard()

            GuideSectionCard(
                number = "1",
                title = "先调 FGO 文字速度",
                body = "剧情文字完整显示后，OCR 和自动翻译会更稳定。"
            ) {
                GuideSettingRow(label = "文字送り", value = "MAX")
                GuideSettingRow(label = "ページ送り", value = "MAX")
                GuideSettingRow(label = "句読点待ち時間", value = "0")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
                GuideInfoRow(label = "设置位置", text = "マイルーム → ゲームオプション → テキスト表示速度")
            }

            GuideSectionCard(
                number = "2",
                title = "选择阅读方式",
                body = "不用一直换模式，先按自己的阅读习惯选一个。"
            ) {
                GuideModeRow(
                    mode = "手动",
                    text = "适合想完全掌控节奏的你。点 GO 后翻译当前画面。"
                )
                GuideModeRow(
                    mode = "半自动",
                    text = "适合剧情阅读。对话自动刷新；遇到选项时，再点悬浮按钮翻译选项。"
                )
                GuideModeRow(
                    mode = "全自动",
                    text = "适合想轻松阅读的你。应用会尽量自动刷新对话和选项。"
                )
                GuideModeRow(
                    mode = "裁剪",
                    text = "适合菜单、公告、漏识别画面。框选区域会重新翻译。"
                )
            }

            GuideSectionCard(
                number = "3",
                title = "看懂悬浮按钮",
                body = "按钮文字会显示当前模式，长按可打开翻译菜单。"
            ) {
                GuideBadgeRow(label = "GO", text = "点击按钮翻译当前画面。")
                GuideBadgeRow(label = "半", text = "半自动阅读，点击按钮翻译选项。")
                GuideBadgeRow(label = "AUTO", text = "全自动运行；此模式下点击按钮不会手动翻译。")
                GuideBadgeRow(label = "红色外圈", text = "手动或半自动点击后，本次识别/翻译未成功，可再点一次。")
                GuideBadgeRow(label = "LOG", text = "查看本次识别和翻译历史。")
            }

            GuideSectionCard(
                number = "4",
                title = "隐私与统计",
                body = "FgoGotran 只收集匿名使用统计，用来了解版本、模式和 API 类型的使用情况。"
            ) {
                GuideInfoRow(
                    label = "不会上传",
                    text = "截图、游戏文本、译文、API Key、玩家名、账号信息或设备识别码。"
                )
            }
        }
    }
}

@Composable
private fun GuideScopeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("使用范围", style = MaterialTheme.typography.titleMedium)
            Text(
                "FgoGotran 的翻译只在正在运行的 FGO 画面上生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            GuideInfoRow(label = "支持", text = "FGO 游戏内实时画面、剧情、选项，以及裁剪模式框选的画面区域。")
            GuideInfoRow(label = "不支持", text = "图片、截图、录屏、视频源，或其他应用里的 FGO 素材翻译。")
        }
    }
}

@Composable
private fun GuideWebsiteCard(onOpenWebsite: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "FgoGotran 网站",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "查看完整教程、下载页面和最新信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenWebsite
            ) {
                Text("打开 FgoGotran 网站")
            }
        }
    }
}

@Composable
private fun GuideSectionCard(
    number: String,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StepBadge(number)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StepBadge(number: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            number,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GuideModeRow(mode: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        ModeChip(mode)
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
    }
}

@Composable
private fun ModeChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun GuideBadgeRow(label: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = Color(0xFF23405F),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
    }
}

@Composable
private fun GuideSettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GuideInfoRow(label: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun openFgoGotranWebsite(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(FGOGOTRAN_WEBSITE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

private const val FGOGOTRAN_WEBSITE_URL = "https://fgogotran.com/"
