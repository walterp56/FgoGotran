package com.fgogotran.chapter.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoInstalledManifestCodec
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.storage.ShizukuChapterStorage
import com.fgogotran.chapter.storage.ChapterAccess
import com.fgogotran.chapter.install.ChapterInstallCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChapterScreenState(
    val gameVersion: String,
    val busy: Boolean = false,
    val error: String? = null,
    val catalogue: ChapterBrowser.Catalogue? = null,
    val group: String? = null,
    val container: DecodedNativeContainer? = null,
    val script: ChapterBrowser.ScriptView? = null
)

/** Own composition root: no old OCR, translator, settings repository or global DI. */
class ChapterViewModel(context: Context) : ViewModel() {
    private val reader = ShizukuChapterStorage(context, viewModelScope)
    private val browser = ChapterBrowser(reader)
    val access = reader.access
    val assetTest = ChapterInstallCoordinator.get(context)
    private val mutableState = MutableStateFlow(ChapterScreenState(runCatching {
        context.packageManager.getPackageInfo(FgoJpFormatProfile.GAME_PACKAGE, 0).versionName ?: "?"
    }.getOrDefault("—")))
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            access.collect { status ->
                if (status != ChapterAccess.READY && mutableState.value.catalogue != null) {
                    cancel()
                    mutableState.value = ChapterScreenState(mutableState.value.gameVersion)
                }
            }
        }
    }

    fun foreground() { reader.onForeground(); assetTest.foreground() }
    fun background() {
        cancel()
        reader.onBackground()
        assetTest.background()
        mutableState.value = ChapterScreenState(mutableState.value.gameVersion)
    }
    fun refresh() = reader.refresh()
    fun connect() = reader.connect()
    fun backupAssets() = assetTest.backup(reader)
    fun cancel() { generation++; work?.cancel(); work = null; mutableState.value = mutableState.value.copy(busy = false) }

    fun scan() = runWork {
        ChapterScreenState(mutableState.value.gameVersion, catalogue = browser.scan())
    }
    fun load(entry: FgoInstalledManifestCodec.Entry) {
        val catalogue = mutableState.value.catalogue ?: return
        runWork { mutableState.value.copy(group = entry.logicalPath, container = browser.load(catalogue, entry), script = null) }
    }
    fun view(source: NativeScriptSource) = runWork {
        mutableState.value.copy(script = browser.view(source))
    }

    fun back(): Boolean {
        cancel()
        val current = mutableState.value
        return when {
            current.script != null -> { mutableState.value = current.copy(script = null, error = null); true }
            current.container != null -> { mutableState.value = current.copy(container = null, group = null, error = null); true }
            else -> false
        }
    }

    private fun runWork(block: suspend () -> ChapterScreenState) {
        cancel()
        val token = generation
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                val result = block()
                ensureActive()
                if (token == generation) mutableState.value = result.copy(busy = false, error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == generation) mutableState.value = mutableState.value.copy(busy = false, error = failure.message ?: "Unsupported asset")
            }
        }
    }

    override fun onCleared() { reader.close(); assetTest.background() }
}
