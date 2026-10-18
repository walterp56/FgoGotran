package com.fgogotran.chapter.ui

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fgogotran.chapter.R
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.install.*

@Composable
internal fun ChapterAssetTestCard(model: ChapterViewModel, canBackup: Boolean, gameVersion: String) {
    val context = LocalContext.current
    val state by model.assetTest.state.collectAsStateWithLifecycle()
    val record = state.record
    val outdated = record != null && record.game.versionName != gameVersion
    var confirmation by remember { mutableStateOf<String?>(null) }
    var disposable by remember { mutableStateOf(false) }
    var pcBackup by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(object : ActivityResultContracts.CreateDocument("application/zip") {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uri -> uri?.let(model.assetTest::export) }
    val idle = !state.busy
    val clean = idle && !state.needsInspection && state.error == null
    val safe = clean && !outdated
    fun confirm(action: String) { disposable = false; pcBackup = false; confirmation = action }
    fun open(intent: Intent?) { openFailed = intent == null || runCatching { context.startActivity(intent) }.isFailure }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chapter_test_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.chapter_test_scope), style = MaterialTheme.typography.bodySmall)
            if (record != null) {
                Text(stringResource(when (record.state) {
                    ChapterInstallState.BACKED_UP -> R.string.chapter_test_backed_up
                    ChapterInstallState.PREPARED -> R.string.chapter_test_prepared
                    ChapterInstallState.STAGED -> R.string.chapter_test_staged
                    ChapterInstallState.COMMITTING -> R.string.chapter_test_committing
                    ChapterInstallState.INSTALLED -> R.string.chapter_test_installed
                    ChapterInstallState.RESTORING -> R.string.chapter_test_restoring
                    ChapterInstallState.RESTORED -> R.string.chapter_test_restored
                    ChapterInstallState.RECOVERY_REQUIRED -> R.string.chapter_test_recovery
                }), style = MaterialTheme.typography.titleSmall)
                Text("${record.id.take(8)} · ${record.game.versionName} · ${record.mode?.name ?: "ORIGINAL"}", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (record.exportSha256 != null) R.string.chapter_test_export_ok else R.string.chapter_test_export_needed), style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (state.critical) R.string.chapter_test_do_not_interrupt else R.string.chapter_test_working))
                if (!state.critical) TextButton(onClick = model.assetTest::cancelPreparation) { Text(stringResource(R.string.chapter_cancel)) }
            }
            state.error?.let { Text(stringResource(R.string.chapter_failed, it), color = MaterialTheme.colorScheme.error) }
            if (state.needsInspection) Text(stringResource(R.string.chapter_test_recovery), color = MaterialTheme.colorScheme.error)
            if (openFailed) Text(stringResource(R.string.chapter_open_failed), color = MaterialTheme.colorScheme.error)
            if (outdated) Text(stringResource(R.string.chapter_test_updated), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${FgoJpFormatProfile.GAME_PACKAGE}"))) }, enabled = idle) {
                Text(stringResource(R.string.chapter_test_game_info))
            }
            OutlinedButton(onClick = model::backupAssets, enabled = canBackup && clean && (record == null || record.state == ChapterInstallState.RESTORED ||
                (outdated && record.state == ChapterInstallState.BACKED_UP && record.mode == null && record.exportSha256 != null)), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chapter_test_backup))
            }
            if (!canBackup && record == null) Text(stringResource(R.string.chapter_test_select_group), style = MaterialTheme.typography.bodySmall)
            if (record != null) {
                OutlinedButton(onClick = { export.launch("FgoGotran-recovery-${record.id.take(8)}.zip") }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_export))
                }
                OutlinedButton(onClick = model.assetTest::inspect, enabled = idle && !outdated, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_check))
                }
                val preparable = record.state in listOf(ChapterInstallState.BACKED_UP, ChapterInstallState.PREPARED, ChapterInstallState.RESTORED)
                OutlinedButton(onClick = { model.assetTest.prepare(ChapterRebuildMode.UNCHANGED) }, enabled = safe && preparable, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_prepare))
                }
                OutlinedButton(onClick = { model.assetTest.prepare(ChapterRebuildMode.MARKER) }, enabled = safe && preparable && record.unchangedVerified, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_marker))
                }
                Text(stringResource(R.string.chapter_test_marker_note), style = MaterialTheme.typography.bodySmall)
                state.preview?.let { preview ->
                    Text("${preview.fieldId}: ${preview.original} → ${preview.replacement}", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { confirm("install") }, enabled = safe && record.state == ChapterInstallState.PREPARED && record.exportSha256 != null &&
                    (record.mode != ChapterRebuildMode.MARKER || state.preview != null), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_install))
                }
                OutlinedButton(onClick = { open(context.packageManager.getLaunchIntentForPackage(FgoJpFormatProfile.GAME_PACKAGE)) }, enabled = safe && record.state in listOf(ChapterInstallState.INSTALLED, ChapterInstallState.RESTORED), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_open_game))
                }
                if (record.mode == ChapterRebuildMode.UNCHANGED && record.state == ChapterInstallState.INSTALLED && !record.playbackConfirmed) {
                    OutlinedButton(onClick = { confirm("playback") }, enabled = safe, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chapter_test_confirm_playback)) }
                }
                OutlinedButton(onClick = { confirm("restore") }, enabled = idle && !outdated && record.mode != null && record.state != ChapterInstallState.RESTORED, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_test_restore))
                }
            }
            if (state.error != null && idle) TextButton(onClick = model.assetTest::foreground) { Text(stringResource(R.string.chapter_refresh)) }
        }
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (action == "playback") R.string.chapter_test_confirm_playback else R.string.chapter_test_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when (action) { "install" -> R.string.chapter_test_confirm_install; "restore" -> R.string.chapter_test_confirm_restore; else -> R.string.chapter_test_playback_note }))
                    if (action == "install") {
                        Row { Checkbox(disposable, { disposable = it }); Text(stringResource(R.string.chapter_test_disposable), modifier = Modifier.padding(top = 12.dp)) }
                        Row { Checkbox(pcBackup, { pcBackup = it }); Text(stringResource(R.string.chapter_test_pc_backup), modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.chapter_cancel)) } },
            confirmButton = { TextButton(enabled = action != "install" || (disposable && pcBackup), onClick = {
                confirmation = null
                when (action) { "install" -> model.assetTest.install(disposable && pcBackup); "restore" -> model.assetTest.restore(true); else -> model.assetTest.confirmPlayback() }
            }) { Text(stringResource(R.string.chapter_test_continue)) } }
        )
    }
}
