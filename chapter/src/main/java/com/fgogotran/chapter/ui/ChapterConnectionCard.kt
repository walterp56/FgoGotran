package com.fgogotran.chapter.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fgogotran.chapter.R
import com.fgogotran.chapter.storage.ChapterAccess

@Composable
internal fun ChapterConnectionCard(access: ChapterAccess, connect: () -> Unit, refresh: () -> Unit, idle: Boolean) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    fun open(intent: Intent) { failed = runCatching { context.startActivity(intent) }.isFailure }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (access) {
                ChapterAccess.MISSING -> R.string.chapter_access_missing
                ChapterAccess.STOPPED -> R.string.chapter_access_stopped
                ChapterAccess.UNSUPPORTED -> R.string.chapter_access_unsupported
                ChapterAccess.PERMISSION -> R.string.chapter_access_permission
                ChapterAccess.DENIED -> R.string.chapter_access_denied
                ChapterAccess.CONNECTING -> R.string.chapter_access_connecting
                ChapterAccess.READY -> R.string.chapter_access_ready
                ChapterAccess.ERROR -> R.string.chapter_access_error
            }))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = connect, enabled = idle && access !in listOf(ChapterAccess.READY, ChapterAccess.CONNECTING,
                    ChapterAccess.MISSING, ChapterAccess.STOPPED, ChapterAccess.UNSUPPORTED)) { Text(stringResource(R.string.chapter_connect)) }
                TextButton(onClick = {
                    open(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                }, enabled = idle) { Text(stringResource(R.string.chapter_shizuku)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = refresh, enabled = idle) { Text(stringResource(R.string.chapter_refresh)) }
                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }, enabled = idle) {
                    Text(stringResource(R.string.chapter_setup))
                }
            }
            if (failed) Text(stringResource(R.string.chapter_open_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
