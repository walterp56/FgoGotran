package com.fgogotran.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fgogotran.R
import com.fgogotran.data.SettingsRepository
import com.fgogotran.storylibrary.StoryCatalogRules
import com.fgogotran.storylibrary.StoryCatalogChapter
import com.fgogotran.storylibrary.StoryChapterCategory
import com.fgogotran.storylibrary.StoryChapterRepository
import com.fgogotran.storylibrary.StoryInstallPhase
import com.fgogotran.storylibrary.StoryInstallUiState
import com.fgogotran.ui.theme.FgoUiColors
import com.fgogotran.ui.theme.FgoUiStyle
import kotlinx.coroutines.launch

private enum class StoryCategoryFilter(val labelRes: Int) {
    NEWEST(R.string.story_library_category_newest),
    MAIN_STORY(R.string.story_library_category_main_story),
    EVENTS(R.string.story_library_category_events),
    INTERLUDES(R.string.story_library_category_interludes);

    fun matches(chapter: StoryCatalogChapter): Boolean = when (this) {
        NEWEST -> StoryCatalogRules.isNewest(chapter)
        MAIN_STORY -> StoryCatalogRules.category(chapter) == StoryChapterCategory.MAIN_STORY
        EVENTS -> StoryCatalogRules.category(chapter) == StoryChapterCategory.EVENTS
        INTERLUDES -> StoryCatalogRules.category(chapter) == StoryChapterCategory.INTERLUDES
    }
}

/**
 * Story Library page: pick a translated chapter, download its database from the
 * CDN, apply it, and switch between the chapter database and API translation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryLibraryScreen(
    settingsRepository: SettingsRepository,
    repository: StoryChapterRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val catalog by repository.catalogState.collectAsStateWithLifecycle()
    val install by repository.installState.collectAsStateWithLifecycle()
    val enabled by settingsRepository.storyChapterDbEnabled.collectAsStateWithLifecycle(initialValue = false)
    val fuzzyMatch by settingsRepository.storyChapterFuzzyMatch.collectAsStateWithLifecycle(initialValue = true)
    val activeChapterId by settingsRepository.storyChapterId.collectAsStateWithLifecycle(initialValue = "")
    val activeVersion by settingsRepository.storyChapterVersion.collectAsStateWithLifecycle(initialValue = "")
    val targetLocale by settingsRepository.targetLanguage.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED
    )
    var filter by rememberSaveable { mutableStateOf(StoryCategoryFilter.NEWEST) }

    LaunchedEffect(Unit) { repository.refreshCatalog() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = FgoUiColors.topAppBarColors(),
                title = { Text(stringResource(R.string.story_library_title)) },
                navigationIcon = {
                    TextButton(colors = FgoUiColors.textButtonColors(), onClick = onBack) {
                        Text(stringResource(R.string.story_library_back), color = FgoUiColors.blueText)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.story_library_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoryCategoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(stringResource(option.labelRes)) }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                border = FgoUiStyle.cardBorder,
                colors = FgoUiColors.cardColors()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.story_library_switch_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.story_library_switch_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = activeChapterId.isNotBlank(),
                            onCheckedChange = { checked -> scope.launch { repository.setEnabled(checked) } }
                        )
                    }
                    if (activeChapterId.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.story_library_active_chapter,
                                activeChapterId,
                                activeVersion
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.story_chapter_fuzzy_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.story_chapter_fuzzy_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fuzzyMatch,
                            enabled = activeChapterId.isNotBlank(),
                            onCheckedChange = { checked ->
                                scope.launch { settingsRepository.setStoryChapterFuzzyMatch(checked) }
                            }
                        )
                    }
                }
            }

            when {
                catalog.error.isNotBlank() -> {
                    Text(
                        text = stringResource(R.string.story_library_error, catalog.error),
                        color = MaterialTheme.colorScheme.error
                    )
                    OutlinedButton(
                        colors = FgoUiColors.outlinedButtonColors(),
                        shape = FgoUiStyle.buttonShape,
                        onClick = { scope.launch { repository.refreshCatalog(force = true) } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.story_library_refresh)) }
                }

                !catalog.loaded -> {
                    Text(
                        text = stringResource(R.string.story_library_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    val chapters = catalog.chapters.filter { filter.matches(it) }
                    if (chapters.isEmpty()) {
                        Text(
                            text = stringResource(R.string.story_library_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    chapters.forEachIndexed { index, chapter ->
                        if (index > 0) HorizontalDivider()
                        StoryChapterCard(
                            chapter = chapter,
                            targetLocale = targetLocale,
                            activeChapterId = activeChapterId,
                            activeVersion = activeVersion,
                            install = install,
                            onDownload = { scope.launch { repository.downloadAndApply(chapter) } },
                            onApply = { scope.launch { repository.applyInstalled(chapter) } },
                            onRemove = { scope.launch { repository.removeChapter(chapter.chapterId) } }
                        )
                    }
                    TextButton(
                        colors = FgoUiColors.textButtonColors(),
                        onClick = { scope.launch { repository.refreshCatalog(force = true) } }
                    ) { Text(stringResource(R.string.story_library_refresh)) }
                }
            }
        }
    }
}

@Composable
private fun StoryChapterCard(
    chapter: StoryCatalogChapter,
    targetLocale: String,
    activeChapterId: String,
    activeVersion: String,
    install: StoryInstallUiState,
    onDownload: () -> Unit,
    onApply: () -> Unit,
    onRemove: () -> Unit
) {
    val isActive = activeChapterId == chapter.chapterId
    val upToDate = isActive && activeVersion == chapter.contentVersion
    val busy = install.chapterId == chapter.chapterId &&
        (install.phase == StoryInstallPhase.DOWNLOADING ||
            install.phase == StoryInstallPhase.VERIFYING ||
            install.phase == StoryInstallPhase.INSTALLING)
    val failed = install.chapterId == chapter.chapterId && install.phase == StoryInstallPhase.FAILED
    val title = remember(chapter, targetLocale) { StoryCatalogRules.title(chapter, targetLocale) }
    val coverage = remember(chapter, targetLocale) {
        (StoryCatalogRules.coverage(chapter, targetLocale) * 100).toInt()
    }
    val categoryLabel = when (StoryCatalogRules.category(chapter)) {
        StoryChapterCategory.MAIN_STORY -> stringResource(R.string.story_library_category_main_story)
        StoryChapterCategory.EVENTS -> stringResource(R.string.story_library_category_events)
        StoryChapterCategory.INTERLUDES -> stringResource(R.string.story_library_category_interludes)
        null -> chapter.category
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = FgoUiStyle.cardBorder,
        colors = FgoUiColors.cardColors()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (StoryCatalogRules.isNewest(chapter)) {
                    Text(
                        text = stringResource(R.string.story_library_badge_newest),
                        style = MaterialTheme.typography.labelMedium,
                        color = FgoUiColors.blueText
                    )
                }
            }
            Text(
                text = "$categoryLabel · " + stringResource(
                    if (StoryCatalogRules.isFinished(chapter)) R.string.story_library_status_finished
                    else R.string.story_library_status_progressing
                ) + " · " + stringResource(R.string.story_library_coverage, coverage, chapter.entryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                busy -> Text(
                    text = when (install.phase) {
                        StoryInstallPhase.DOWNLOADING -> {
                            val percent = if (install.totalBytes > 0) {
                                (install.bytesRead * 100 / install.totalBytes).toInt().coerceIn(0, 100)
                            } else 0
                            stringResource(R.string.story_library_downloading, percent)
                        }
                        StoryInstallPhase.VERIFYING -> stringResource(R.string.story_library_verifying)
                        else -> stringResource(R.string.story_library_installing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FgoUiColors.blueText
                )

                failed -> Text(
                    text = stringResource(R.string.story_library_error, install.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                upToDate -> Text(
                    text = stringResource(R.string.story_library_installed, activeVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = FgoUiColors.success
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !StoryCatalogRules.isDownloadable(chapter) -> {
                        Text(
                            text = stringResource(R.string.story_library_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    busy -> Unit

                    !isActive -> Button(
                        onClick = onDownload,
                        shape = FgoUiStyle.buttonShape
                    ) { Text(stringResource(R.string.story_library_download_apply)) }

                    !upToDate -> {
                        Button(onClick = onDownload, shape = FgoUiStyle.buttonShape) {
                            Text(stringResource(R.string.story_library_update))
                        }
                        OutlinedButton(
                            colors = FgoUiColors.outlinedButtonColors(),
                            shape = FgoUiStyle.buttonShape,
                            onClick = onApply
                        ) { Text(stringResource(R.string.story_library_apply)) }
                    }

                    else -> OutlinedButton(
                        colors = FgoUiColors.outlinedButtonColors(),
                        shape = FgoUiStyle.buttonShape,
                        onClick = onApply
                    ) { Text(stringResource(R.string.story_library_apply)) }
                }
                if (isActive && !busy) {
                    TextButton(colors = FgoUiColors.textButtonColors(), onClick = onRemove) {
                        Text(stringResource(R.string.story_library_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
