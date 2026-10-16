package com.fgogotran.chapter.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fgogotran.chapter.R
import com.fgogotran.chapter.storage.ChapterAccess
import com.fgogotran.chapter.install.ChapterInstallPolicy

@Composable
fun ChapterScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val factory = remember { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChapterViewModel(context.applicationContext) as T
    } }
    val model: ChapterViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val access by model.access.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.foreground()
                Lifecycle.Event.ON_STOP -> model.background()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.foreground()
        onDispose { owner.lifecycle.removeObserver(observer); model.background() }
    }
    val back = { if (!model.back()) onExit() }
    BackHandler(onBack = back)
    var search by rememberSaveable(state.group) { mutableStateOf("") }
    var native by rememberSaveable(state.script?.document?.source?.binding?.scriptId?.value) { mutableStateOf(false) }
    var intentError by remember { mutableStateOf(false) }
    fun open(intent: Intent) { intentError = runCatching { context.startActivity(intent) }.isFailure }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = back) { Text(stringResource(R.string.chapter_back)) }
                Text(stringResource(R.string.chapter_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.chapter_read_only), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.chapter_scope))
                            Text(stringResource(R.string.chapter_game, state.gameVersion), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
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
                                Button(onClick = model::connect, enabled = access !in listOf(ChapterAccess.READY, ChapterAccess.CONNECTING, ChapterAccess.MISSING, ChapterAccess.STOPPED, ChapterAccess.UNSUPPORTED)) {
                                    Text(stringResource(R.string.chapter_connect))
                                }
                                TextButton(onClick = {
                                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    if (intent != null) open(intent) else open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                                }) { Text(stringResource(R.string.chapter_shizuku)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = model::refresh) { Text(stringResource(R.string.chapter_refresh)) }
                                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) }) { Text(stringResource(R.string.chapter_setup)) }
                            }
                            if (intentError) Text(stringResource(R.string.chapter_open_failed), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (state.busy) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = model::cancel) { Text(stringResource(R.string.chapter_cancel)) }
                }
                state.error?.let { message -> item {
                    Text(stringResource(R.string.chapter_failed, message), color = MaterialTheme.colorScheme.error)
                } }
                item {
                    ChapterAssetTestCard(model, canBackup = access == ChapterAccess.READY && state.group == ChapterInstallPolicy.GROUP && !state.busy, gameVersion = state.gameVersion)
                }
                if (state.container == null) {
                    item {
                        Button(onClick = model::scan, enabled = access == ChapterAccess.READY && !state.busy) { Text(stringResource(R.string.chapter_scan)) }
                        Text(stringResource(R.string.chapter_group_note), style = MaterialTheme.typography.bodySmall)
                    }
                    state.catalogue?.let { catalogue ->
                        if (catalogue.groups.isEmpty()) item { Text(stringResource(R.string.chapter_empty)) }
                        items(catalogue.groups, key = { it.logicalPath }) { entry ->
                            OutlinedButton(onClick = { model.load(entry) }, enabled = !state.busy && access == ChapterAccess.READY, modifier = Modifier.fillMaxWidth()) {
                                Text("${entry.logicalPath} · ${entry.size} B")
                            }
                        }
                    }
                } else if (state.script == null) {
                    item {
                        Text(state.group.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.chapter_scripts, state.container!!.scripts.size))
                        OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text(stringResource(R.string.chapter_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    items(state.container!!.scripts.filter { it.binding.scriptId.value.contains(search, ignoreCase = true) }, key = { "${it.binding.scriptId.value}:${it.binding.locator.payload.sha256.hex}" }) { source ->
                        OutlinedButton(onClick = { model.view(source) }, enabled = !state.busy && access == ChapterAccess.READY, modifier = Modifier.fillMaxWidth()) { Text(source.binding.scriptId.value) }
                    }
                } else {
                    val script = state.script!!
                    item {
                        Text(script.document.source.binding.scriptId.value, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.chapter_fields, script.document.fields.size))
                        TextButton(onClick = { native = !native }) { Text(stringResource(if (native) R.string.chapter_show_fields else R.string.chapter_show_native)) }
                    }
                    if (native) items(script.nativeText.lines().withIndex().toList(), key = { it.index }) { line ->
                        SelectionContainer { Text(line.value, style = MaterialTheme.typography.bodySmall) }
                    } else items(script.document.fields, key = { it.id.value }) { field ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${field.kind} · ${field.id.value}", style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(field.sourceText) }
                                field.ruby.forEach { ruby -> Text(stringResource(R.string.chapter_ruby, field.sourceText.substring(ruby.baseRange.start, ruby.baseRange.endExclusive), ruby.reading), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Text(stringResource(R.string.chapter_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
