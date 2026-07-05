package com.fgogotran.ui.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.fgogotran.update.AppVersionInfo
import com.fgogotran.update.AppVersionManager

@Composable
fun AppUpdateDialog(
    currentVersionName: String,
    update: AppVersionInfo,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("发现新版本") },
        text = {
            AppUpdateDialogBody(
                currentVersionName = currentVersionName,
                update = update
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("暂不更新")
                }
                Button(onClick = onUpdateNow) {
                    Text("立即更新")
                }
            }
        }
    )
}

@Composable
fun AutoAppUpdateDialog(
    currentVersionName: String,
    update: AppVersionInfo,
    onDismiss: () -> Unit,
    onIgnoreVersion: () -> Unit,
    onUpdateNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("发现新版本") },
        text = {
            AppUpdateDialogBody(
                currentVersionName = currentVersionName,
                update = update
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
            ) {
                TextButton(onClick = onIgnoreVersion) {
                    Text("此版本不再提醒")
                }
                TextButton(onClick = onDismiss) {
                    Text("暂不更新")
                }
                Button(onClick = onUpdateNow) {
                    Text("立即更新")
                }
            }
        }
    )
}

@Composable
private fun AppUpdateDialogBody(
    currentVersionName: String,
    update: AppVersionInfo
) {
    val notes = update.changelog
        .map { it.trim() }
        .filter { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("当前版本：$currentVersionName")
        Text("最新版本：${update.versionName}")
        update.releaseDate.trim().takeIf { it.isNotBlank() }?.let { releaseDate ->
            Text("发布日期：${releaseDate.substringBefore('T')}")
        }

        Text(
            text = "更新内容",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (notes.isEmpty()) {
            Text("请前往下载页面查看更新内容。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEach { item ->
                    Text("- $item")
                }
            }
        }
    }
}

fun openAppDownloadPage(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(AppVersionManager.DOWNLOAD_PAGE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
