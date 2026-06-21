package com.fgogotran

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.translation.Translator
import com.fgogotran.ui.screen.ApiSettingsScreen
import com.fgogotran.ui.screen.GuideScreen
import com.fgogotran.ui.screen.HomeScreen
import com.fgogotran.ui.screen.SettingsScreen
import com.fgogotran.ui.theme.FgoGotranTheme
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.AndroidEntryPoint
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
    @Inject lateinit var translator: Translator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FgoLogger.info("MainActivity", "MainActivity created")
        enableEdgeToEdge()
        setContent {
            FgoGotranTheme {
                MainScreen(settingsRepository, glossaryUpdateManager, translator)
            }
        }
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
    translator: Translator
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    // enable backscreen
    BackHandler(enabled = currentScreen != Screen.HOME) {
        currentScreen = if (currentScreen == Screen.API_SETTINGS) Screen.SETTINGS else Screen.HOME
    }

    when (currentScreen) {
        Screen.HOME -> HomeScreen(
            onGuide = { currentScreen = Screen.GUIDE },
            onSettings = { currentScreen = Screen.SETTINGS }
        )

        Screen.GUIDE -> GuideScreen(
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.SETTINGS -> SettingsScreen(
            settingsRepository = settingsRepository,
            glossaryUpdateManager = glossaryUpdateManager,
            onClearTranslationCache = { translator.clearTranslationCache() },
            onApiSettings = { currentScreen = Screen.API_SETTINGS },
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.API_SETTINGS -> ApiSettingsScreen(
            settingsRepository = settingsRepository,
            translator = translator,
            onBack = { currentScreen = Screen.SETTINGS }
        )
    }
}
