package com.fgogotran.chapter.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fgogotran.chapter.BuildConfig
import com.fgogotran.chapter.R
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.install.ChapterInstallCoordinator
import com.fgogotran.chapter.install.ChapterInstallRecord
import com.fgogotran.chapter.install.ChapterInstallState
import com.fgogotran.chapter.packs.StoryLanguage
import com.fgogotran.chapter.remote.NativePackInfo
import com.fgogotran.chapter.remote.NativeReleaseClient
import com.fgogotran.chapter.storage.ChapterAccess
import com.fgogotran.chapter.storage.ShizukuChapterStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InstallPhase { IDLE, LOOKUP, DOWNLOAD, INSTALL, RESTORING, DONE, RESTORED }

data class NativeInstallState(
    val gameVersion: String,
    val languageTag: String = StoryLanguage.ENGLISH.tag,
    val phase: InstallPhase = InstallPhase.IDLE,
    val packs: List<NativePackInfo> = emptyList(),
    val installed: Int = 0,
    val total: Int = 0,
    val activeLanguage: String? = null,
    val missing: List<String> = emptyList(),
    val error: String? = null,
) {
    val busy: Boolean get() = phase == InstallPhase.LOOKUP || phase == InstallPhase.DOWNLOAD || phase == InstallPhase.INSTALL || phase == InstallPhase.RESTORING
    val scripts: Int get() = packs.sumOf { it.scripts.size }
}

internal class NativeInstallViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val reader = ShizukuChapterStorage(context, viewModelScope)
    private val coordinator = ChapterInstallCoordinator.get(context)
    private val client = NativeReleaseClient(BuildConfig.NATIVE_BASE_URL, BuildConfig.NATIVE_API_KEY)
    val access = reader.access
    val configured = client.isConfigured

    private val mutableState = MutableStateFlow(
        NativeInstallState(
            gameVersion = runCatching {
                context.packageManager.getPackageInfo(FgoJpFormatProfile.GAME_PACKAGE, 0).versionName
            }.getOrNull() ?: "—"
        )
    )
    val state = mutableState.asStateFlow()
    private val mutableRecord = MutableStateFlow<ChapterInstallRecord?>(null)
    val record = mutableRecord.asStateFlow()
    private var lookupJob: Job? = null

    init {
        viewModelScope.launch { coordinator.state.collect { mutableRecord.value = it.record } }
    }

    fun foreground() {
        reader.onForeground()
        coordinator.foreground()
        lookup()
    }

    fun background() {
        lookupJob?.cancel()
        reader.onBackground()
        coordinator.background()
    }

    fun connect() = reader.connect()
    fun refresh() { reader.refresh(); lookup() }

    fun selectLanguage(language: StoryLanguage) {
        if (language.tag == mutableState.value.languageTag) return
        mutableState.value = mutableState.value.copy(languageTag = language.tag, packs = emptyList(), error = null, phase = InstallPhase.IDLE)
        lookup()
    }

    fun lookup() {
        if (!configured) return
        lookupJob?.cancel()
        val language = mutableState.value.languageTag
        val version = mutableState.value.gameVersion
        mutableState.value = mutableState.value.copy(phase = InstallPhase.LOOKUP, packs = emptyList(), error = null)
        lookupJob = viewModelScope.launch {
            try {
                val packs = withContext(Dispatchers.IO) { client.lookupAll(language, version) }
                if (mutableState.value.languageTag == language) {
                    mutableState.value = mutableState.value.copy(phase = InstallPhase.IDLE, packs = packs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (mutableState.value.languageTag == language) {
                    mutableState.value = mutableState.value.copy(phase = InstallPhase.IDLE, error = failure.message)
                }
            }
        }
    }

    fun install() {
        if (mutableState.value.busy) return
        if (access.value != ChapterAccess.READY) {
            mutableState.value = mutableState.value.copy(error = appContext.getString(R.string.chapter_native_need_shizuku))
            return
        }
        val packs = mutableState.value.packs
        if (packs.isEmpty()) return
        val version = mutableState.value.gameVersion
        mutableState.value = mutableState.value.copy(phase = InstallPhase.DOWNLOAD, error = null)
        viewModelScope.launch {
            try {
                val downloaded = mutableListOf<com.fgogotran.chapter.remote.NativeStoryPackage>()
                for (pack in packs) {
                    downloaded += withContext(Dispatchers.IO) { client.download(pack) }
                }
                ensureActive()
                mutableState.value = mutableState.value.copy(phase = InstallPhase.INSTALL, installed = 0, total = downloaded.size)
                val result = withContext(Dispatchers.IO) {
                    coordinator.switchLanguage(reader, downloaded) { done, total ->
                        mutableState.value = mutableState.value.copy(installed = done, total = total)
                    }
                }
                ensureActive()
                mutableState.value = mutableState.value.copy(
                    phase = InstallPhase.DONE,
                    activeLanguage = packs.firstOrNull()?.language,
                    missing = result.missing,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(phase = InstallPhase.IDLE, error = userMessage(failure))
            }
        }
    }

    fun restore() {
        if (mutableState.value.busy) return
        if (access.value != ChapterAccess.READY) {
            mutableState.value = mutableState.value.copy(error = appContext.getString(R.string.chapter_native_need_shizuku))
            return
        }
        val record = mutableRecord.value ?: return
        if (record.mode == null || record.state == ChapterInstallState.RESTORED) return
        mutableState.value = mutableState.value.copy(phase = InstallPhase.RESTORING, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { coordinator.restoreOriginal(true) }
                ensureActive()
                mutableState.value = mutableState.value.copy(phase = InstallPhase.RESTORED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(
                    phase = InstallPhase.IDLE,
                    error = userMessage(failure),
                )
            }
        }
    }

    private fun userMessage(failure: Exception): String {
        val raw = failure.message ?: return appContext.getString(R.string.chapter_native_failed_generic)
        return when {
            raw.contains("Force stop", ignoreCase = true) -> appContext.getString(R.string.chapter_native_stop_game)
            raw.contains("Restore the original", ignoreCase = true) -> appContext.getString(R.string.chapter_native_restore_first)
            else -> raw
        }
    }

    override fun onCleared() {
        lookupJob?.cancel()
        reader.close()
        coordinator.background()
    }
}
