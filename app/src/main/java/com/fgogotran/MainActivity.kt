package com.fgogotran

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.data.SettingsRepository
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.translation.Translator
import com.fgogotran.ui.component.AutoAppUpdateDialog
import com.fgogotran.ui.component.openAppDownloadPage
import com.fgogotran.update.AppVersionManager
import com.fgogotran.ui.screen.ApiSettingsScreen
import com.fgogotran.ui.screen.GuideScreen
import com.fgogotran.ui.screen.HomeScreen
import com.fgogotran.ui.screen.SettingsScreen
import com.fgogotran.ui.theme.FgoGotranTheme
import com.fgogotran.update.AppVersionCheckResult
import com.fgogotran.update.AppVersionInfo
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single Activity hosting all app UI via Jetpack Compose navigation.
 *
 * ## Screen navigation
 * Uses a simple `when` block on [Screen] enum rather than Navigation Compose —
 * the app has only 3 screens and no deep-linking requirements, so a full
 * NavController would be overkill.
 *
 * ## Edge-to-edge
 * [enableEdgeToEdge] extends the app content behind the status bar and
 * navigation bar, matching modern Android design conventions.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var glossaryUpdateManager: GlossaryUpdateManager
    @Inject lateinit var appVersionManager: AppVersionManager
    @Inject lateinit var translator: Translator
    @Inject lateinit var appAnalytics: AppAnalytics

    private val voiceSubtitleRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchVoiceSubtitleCapture()
        } else {
            lifecycleScope.launch {
                settingsRepository.setVoiceSubtitleEnabled(false)
            }
        }
    }

    private val voiceSubtitleCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            FgoRunnerService.startVoiceSubtitle(this, result.resultCode, data)
        } else {
            lifecycleScope.launch {
                settingsRepository.setVoiceSubtitleEnabled(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            settingsRepository.debugLoggingEnabled.collect { enabled ->
                FgoLogger.setEnabled(enabled)
            }
        }
        FgoLogger.info("MainActivity", "MainActivity created")
        enableEdgeToEdge()
        lifecycleScope.launch(Dispatchers.IO) {
            glossaryUpdateManager.updateIfNeeded()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            appAnalytics.reportAppUsed()
            appAnalytics.reportCurrentBackendType()
        }
        setContent {
            FgoGotranTheme {
                MainScreen(settingsRepository, glossaryUpdateManager, appVersionManager, translator, appAnalytics)
            }
        }
        handleVoiceSubtitleCaptureIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceSubtitleCaptureIntent(intent)
    }

    private fun handleVoiceSubtitleCaptureIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_VOICE_SUBTITLE_CAPTURE, false) != true) return
        intent.removeExtra(EXTRA_REQUEST_VOICE_SUBTITLE_CAPTURE)
        if (!hasVoiceSubtitleRecognizerPermission()) {
            voiceSubtitleRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchVoiceSubtitleCapture()
    }

    private fun launchVoiceSubtitleCapture() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        voiceSubtitleCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun hasVoiceSubtitleRecognizerPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_REQUEST_VOICE_SUBTITLE_CAPTURE =
            "com.fgogotran.extra.REQUEST_VOICE_SUBTITLE_CAPTURE"
    }
}

/**
 * Top-level navigation state and routing.
 */
enum class Screen { HOME, GUIDE, SETTINGS, API_SETTINGS }

/**
 * Root composable managing 3-screen navigation.
 */
@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    glossaryUpdateManager: GlossaryUpdateManager,
    appVersionManager: AppVersionManager,
    translator: Translator,
    appAnalytics: AppAnalytics
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var pendingAutoUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }
    val currentVersionName = remember(appVersionManager) { appVersionManager.currentVersionName() }

    LaunchedEffect(appVersionManager, settingsRepository) {
        when (val result = appVersionManager.checkNow()) {
            is AppVersionCheckResult.UpdateAvailable -> {
                val ignoredVersionCode = settingsRepository.getIgnoredAppUpdateVersionCode()
                if (result.info.versionCode > ignoredVersionCode) {
                    pendingAutoUpdate = result.info
                }
            }
            AppVersionCheckResult.UpToDate -> Unit
            is AppVersionCheckResult.Failed -> Unit
        }
    }

    pendingAutoUpdate?.let { update ->
        AutoAppUpdateDialog(
            currentVersionName = currentVersionName,
            update = update,
            onDismiss = { pendingAutoUpdate = null },
            onIgnoreVersion = {
                pendingAutoUpdate = null
                scope.launch {
                    settingsRepository.setIgnoredAppUpdateVersionCode(update.versionCode)
                }
            },
            onUpdateNow = {
                pendingAutoUpdate = null
                openAppDownloadPage(context)
            }
        )
    }

    // enable backscreen
    BackHandler(enabled = currentScreen != Screen.HOME) {
        currentScreen = if (currentScreen == Screen.API_SETTINGS) Screen.SETTINGS else Screen.HOME
    }

    when (currentScreen) {
        Screen.HOME -> HomeScreen(
            settingsRepository = settingsRepository,
            onGuide = { currentScreen = Screen.GUIDE },
            onSettings = { currentScreen = Screen.SETTINGS }
        )

        Screen.GUIDE -> GuideScreen(
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.SETTINGS -> SettingsScreen(
            settingsRepository = settingsRepository,
            glossaryUpdateManager = glossaryUpdateManager,
            appVersionManager = appVersionManager,
            onClearTranslationCache = { translator.clearTranslationCache() },
            onApiSettings = { currentScreen = Screen.API_SETTINGS },
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.API_SETTINGS -> ApiSettingsScreen(
            settingsRepository = settingsRepository,
            translator = translator,
            appAnalytics = appAnalytics,
            onBack = { currentScreen = Screen.SETTINGS }
        )
    }
}
