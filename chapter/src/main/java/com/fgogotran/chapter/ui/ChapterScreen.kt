package com.fgogotran.chapter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fgogotran.chapter.R
import com.fgogotran.chapter.install.ChapterInstallState
import com.fgogotran.chapter.packs.StoryLanguage
import com.fgogotran.chapter.storage.ChapterAccess

@Composable
fun ChapterScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeInstallViewModel(context.applicationContext) as T
        }
    }
    val model: NativeInstallViewModel = viewModel(factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val access by model.access.collectAsStateWithLifecycle()
    val record by model.record.collectAsStateWithLifecycle()
    var showRestore by remember { mutableStateOf(false) }
    ChapterPageLifecycle(model::foreground, model::background)
    BackHandler(onBack = onExit)

    ChapterPageFrame(stringResource(R.string.chapter_native_title), onExit) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chapter_native_scope_short), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.chapter_game, state.gameVersion), style = MaterialTheme.typography.bodySmall)

                var menu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Text(stringResource(R.string.chapter_native_language, languageLabel(state.languageTag)))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        StoryLanguage.entries.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(languageLabel(language.tag)) },
                                onClick = { model.selectLanguage(language); menu = false },
                            )
                        }
                    }
                }

                when {
                    !model.configured -> Text(
                        stringResource(R.string.chapter_native_unconfigured),
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.phase == InstallPhase.LOOKUP -> Text(
                        stringResource(R.string.chapter_native_checking),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.packs.isNotEmpty() -> Text(
                        stringResource(R.string.chapter_native_pack, state.scripts),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        stringResource(R.string.chapter_native_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val installed = record?.state == ChapterInstallState.INSTALLED
                val ready = model.configured && access == ChapterAccess.READY && state.packs.isNotEmpty() && !state.busy
                Button(onClick = model::install, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text(if (installed) "切换语言" else stringResource(R.string.chapter_native_install_now))
                }

                if (state.hasBackup && !state.busy) {
                    OutlinedButton(onClick = { showRestore = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("恢复原版（回到日服）")
                    }
                }
                if (showRestore) {
                    AlertDialog(
                        onDismissRequest = { showRestore = false },
                        title = { Text("恢复日服原版？") },
                        text = { Text("请先完全关闭 FGO。将把已安装章节写回原版日文，官方索引不会改变。") },
                        confirmButton = {
                            TextButton(onClick = { showRestore = false; model.restore() }) { Text("恢复") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestore = false }) { Text("取消") }
                        },
                    )
                }
                when (state.phase) {
                    InstallPhase.DOWNLOAD -> Text(stringResource(R.string.chapter_native_downloading), style = MaterialTheme.typography.bodySmall)
                    InstallPhase.INSTALL -> Text(
                        stringResource(R.string.chapter_native_installing) + "  ${state.installed}/${state.total}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    InstallPhase.DONE -> Text(stringResource(R.string.chapter_native_done), color = MaterialTheme.colorScheme.primary)
                    InstallPhase.RESTORING -> Text(stringResource(R.string.chapter_native_restoring), style = MaterialTheme.typography.bodySmall)
                    InstallPhase.RESTORED -> Text(stringResource(R.string.chapter_native_restored), color = MaterialTheme.colorScheme.primary)
                    else -> Unit
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.missing.isNotEmpty()) {
                    Text(
                        "还需 ${state.missing.size} 个章节的游戏数据：${state.missing.joinToString("、")}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                state.activeLanguage?.let {
                    Text("当前语言：$it  ·  ${state.installed}/${state.total}", style = MaterialTheme.typography.bodySmall)
                }

            }
        }

        if (access != ChapterAccess.READY) {
            ChapterConnectionCard(access, model::connect, model::refresh, !state.busy)
        }
    }
}

@Composable
private fun languageLabel(tag: String): String = when (StoryLanguage.parse(tag)) {
    StoryLanguage.ENGLISH -> stringResource(R.string.chapter_language_en)
    StoryLanguage.SIMPLIFIED_CHINESE -> stringResource(R.string.chapter_language_zh_hans)
    StoryLanguage.TRADITIONAL_CHINESE -> stringResource(R.string.chapter_language_zh_hant)
}

@Composable
internal fun ChapterPageFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.chapter_back)) }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            HorizontalDivider()
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
