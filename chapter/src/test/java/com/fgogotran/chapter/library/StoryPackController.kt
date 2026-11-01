package com.fgogotran.chapter.library

// Historical source-library workflow retained for offline regression tests only.

import com.fgogotran.chapter.immutableList
import com.fgogotran.chapter.packs.*
import java.io.InputStream
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StoryPackWork { LOAD, IMPORT, PREVIEW, SAVE_SELECTION }
data class StoryPackLibraryState(
    val entries: List<StoryCatalogueEntry> = emptyList(),
    val language: StoryLanguage = StoryLanguage.TRADITIONAL_CHINESE,
    val selected: Set<String> = emptySet(),
    val work: StoryPackWork? = null,
    val error: String? = null,
    val imported: StoryCatalogueEntry? = null,
    val preview: StoryPackPreview? = null,
    val selectionRevision: Long = 0
) {
    val busy: Boolean get() = work != null
    val available: List<StoryCatalogueEntry> get() = (StoryCatalogue(entries).latest().filter { it.language == language } +
        entries.filter { it.language == language && it.sha256 in selected }).distinctBy { it.sha256 }.sortedWith(compareBy({ it.chapterId }, { it.revision }))
    val selection: List<StoryCatalogueEntry> get() = entries.filter { it.language == language && it.sha256 in selected }
    val selectionMissing: Boolean get() = selection.size != selected.size
}

/** Feature-owned presentation state. Selection persistence is optional; previews are always session-local. */
class StoryPackController(
    private val library: StoryPackLibrary, private val previewer: StoryPackPreviewPort,
    private val scope: CoroutineScope,
    private val selections: StoryPackSelectionPersistence? = null
) {
    private val mutableState = MutableStateFlow(StoryPackLibraryState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var foreground = false

    fun foreground() {
        if (foreground) return
        foreground = true
        reload()
    }
    fun background() { foreground = false; cancel() }

    fun cancel() {
        generation++; job?.cancel(); job = null
        mutableState.value = mutableState.value.copy(work = null, preview = null)
    }

    fun language(language: StoryLanguage) {
        cancel()
        saveSelection(language, emptySet())
    }

    fun select(sha256: String, selected: Boolean) {
        val current = mutableState.value
        if (current.busy) return
        require(current.available.any { it.sha256 == sha256 }) { "Select an imported latest revision" }
        val entry = current.available.single { it.sha256 == sha256 }
        val next = current.selected.toMutableSet().apply {
            if (selected) { removeAll(current.entries.filter { it.chapterId == entry.chapterId }.map { it.sha256 }.toSet()); add(sha256) } else remove(sha256)
        }
        saveSelection(current.language, next)
    }

    fun clearSelection() = saveSelection(mutableState.value.language, emptySet())

    private fun saveSelection(language: StoryLanguage, selected: Set<String>) {
        val before = mutableState.value
        val persistence = selections
        if (persistence == null) mutableState.value = before.copy(language = language, selected = Collections.unmodifiableSet(LinkedHashSet(selected)), imported = null, error = null, preview = null)
        else launch(StoryPackWork.SAVE_SELECTION) { check ->
            check()
            val saved = persistence.saveSelection(StoryPackSelection(before.selectionRevision, before.language, before.selected), language, selected)
            mutableState.value.copy(language = saved.language, selected = saved.archives, selectionRevision = saved.revision, preview = null)
        }
    }

    fun reload() = launch(StoryPackWork.LOAD) { check ->
        val entries = library.list(check)
        val current = mutableState.value
        val available = StoryCatalogue(entries).latest().filter { it.language == current.language }.map { it.sha256 }.toSet()
        val saved = selections?.loadSelection()
        current.copy(entries = entries, language = saved?.language ?: current.language,
            selected = saved?.archives ?: Collections.unmodifiableSet(current.selected.intersect(available)), selectionRevision = saved?.revision ?: 0, preview = null)
    }

    fun import(open: () -> InputStream) = launch(StoryPackWork.IMPORT) { check ->
        val imported = library.import(open, check)
        val entries = library.list(check)
        val saved = selections?.loadSelection()
        mutableState.value.copy(entries = entries, selected = saved?.archives ?: emptySet(), language = saved?.language ?: mutableState.value.language,
            selectionRevision = saved?.revision ?: 0, imported = imported, preview = null)
    }

    fun preview() {
        val selection = immutableList(mutableState.value.selection)
        if (selection.isEmpty() || mutableState.value.busy || mutableState.value.selectionMissing) return
        launch(StoryPackWork.PREVIEW) { check ->
            val packs = library.load(selection, check)
            val report = previewer.preview(packs)
            mutableState.value.copy(preview = report, imported = null)
        }
    }

    private fun launch(work: StoryPackWork, block: suspend (() -> Unit) -> StoryPackLibraryState) {
        cancel()
        val token = generation
        mutableState.value = mutableState.value.copy(work = work, error = null, imported = null)
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    block { context.ensureActive() }
                }
                ensureActive()
                if (token == generation) mutableState.value = result.copy(work = null, error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) mutableState.value = mutableState.value.copy(work = null, error = (failure.message ?: "Invalid local story pack").take(240), preview = null)
            }
        }
    }
}
