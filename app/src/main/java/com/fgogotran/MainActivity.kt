package com.fgogotran

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.fgogotran.analytics.AppAnalytics
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.translation.Translator
import com.fgogotran.ui.component.AutoAppUpdateDialog
import com.fgogotran.ui.component.openAppDownloadPage
import com.fgogotran.update.AppVersionManager
import com.fgogotran.ui.screen.ApiSettingsScreen
import com.fgogotran.ui.screen.DiagnosticLogScreen
import com.fgogotran.ui.screen.GuideScreen
import com.fgogotran.ui.screen.HomeScreen
import com.fgogotran.ui.screen.SettingsScreen
import com.fgogotran.ui.screen.VoiceSettingsScreen
import com.fgogotran.ui.theme.FgoGotranTheme
import com.fgogotran.update.AppVersionCheckResult
import com.fgogotran.update.AppVersionInfo
import com.fgogotran.util.FgoLogger
import com.fgogotran.voice.AiVoiceService
import com.fgogotran.voice.VoiceDataUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single Activity hosting all app UI via Jetpack Compose navigation.
 *
 * ## Screen navigation
 * Uses a simple `when` block on [Screen] enum rather than Navigation Compose —
 * the app has only a handful of screens and no deep-linking requirements, so a full
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
    @Inject lateinit var voiceDataUpdateManager: VoiceDataUpdateManager
    @Inject lateinit var appVersionManager: AppVersionManager
    @Inject lateinit var translator: Translator
    @Inject lateinit var appAnalytics: AppAnalytics
    @Inject lateinit var diagnosticEventStore: DiagnosticEventStore
    @Inject lateinit var aiVoiceService: AiVoiceService

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
            voiceDataUpdateManager.updateIfNeeded()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            appAnalytics.reportAppUsed()
            appAnalytics.reportCurrentBackendType()
        }
        setContent {
            FgoGotranTheme {
                MainScreen(
                    settingsRepository,
                    appVersionManager,
                    translator,
                    appAnalytics,
                    diagnosticEventStore,
                    aiVoiceService
                )
            }
        }
    }
}

/**
 * Top-level navigation state and routing.
 */
enum class Screen { HOME, GUIDE, SETTINGS, API_SETTINGS, VOICE_SETTINGS, DIAGNOSTIC_LOG }

/**
 * Root composable managing top-level navigation.
 */
@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    appVersionManager: AppVersionManager,
    translator: Translator,
    appAnalytics: AppAnalytics,
    diagnosticEventStore: DiagnosticEventStore,
    aiVoiceService: AiVoiceService
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var pendingAutoUpdate by remember { mutableStateOf<AppVersionInfo?>(null) }
    val currentVersionName = remember(appVersionManager) { appVersionManager.currentVersionName() }

    LaunchedEffect(appVersionManager, settingsRepository) {
        when (val result = appVersionManager.checkForAutoPrompt()) {
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
        currentScreen = when (currentScreen) {
            Screen.API_SETTINGS,
            Screen.VOICE_SETTINGS,
            Screen.DIAGNOSTIC_LOG -> Screen.SETTINGS
            else -> Screen.HOME
        }
    }

    when (currentScreen) {
        Screen.HOME -> HomeScreen(
            settingsRepository = settingsRepository,
            diagnosticEventStore = diagnosticEventStore,
            onGuide = { currentScreen = Screen.GUIDE },
            onSettings = { currentScreen = Screen.SETTINGS }
        )

        Screen.GUIDE -> GuideScreen(
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.SETTINGS -> SettingsScreen(
            settingsRepository = settingsRepository,
            appVersionManager = appVersionManager,
            onClearTranslationCache = { translator.clearTranslationCache() },
            onApiSettings = { currentScreen = Screen.API_SETTINGS },
            onVoiceSettings = { currentScreen = Screen.VOICE_SETTINGS },
            onDiagnosticLog = { currentScreen = Screen.DIAGNOSTIC_LOG },
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.API_SETTINGS -> ApiSettingsScreen(
            settingsRepository = settingsRepository,
            translator = translator,
            appAnalytics = appAnalytics,
            onBack = { currentScreen = Screen.SETTINGS }
        )

        Screen.VOICE_SETTINGS -> VoiceSettingsScreen(
            settingsRepository = settingsRepository,
            translator = translator,
            aiVoiceService = aiVoiceService,
            onBack = { currentScreen = Screen.SETTINGS }
        )

        Screen.DIAGNOSTIC_LOG -> DiagnosticLogScreen(
            diagnosticEventStore = diagnosticEventStore,
            onBack = { currentScreen = Screen.SETTINGS }
        )
    }
}
