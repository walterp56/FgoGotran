package com.fgogotran

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fgogotran.data.SettingsRepository
import com.fgogotran.translation.TranslationCacheDao
import com.fgogotran.ui.screen.HistoryScreen
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
    @Inject lateinit var cacheDao: TranslationCacheDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FgoLogger.info("MainActivity", "MainActivity created")
        enableEdgeToEdge()
        setContent {
            FgoGotranTheme {
                MainScreen(settingsRepository, cacheDao)
            }
        }
    }
}

/**
 * Top-level navigation state and routing.
 */
enum class Screen { HOME, SETTINGS, HISTORY }

/**
 * Root composable managing 3-screen navigation.
 */
@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    cacheDao: TranslationCacheDao
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current

    // enable backscreen
    BackHandler(enabled = currentScreen != Screen.HOME) {
        currentScreen = Screen.HOME
    }

    when (currentScreen) {
        Screen.HOME -> HomeScreen(
            onSettings = { currentScreen = Screen.SETTINGS },
            onHistory = { currentScreen = Screen.HISTORY }
        )

        Screen.SETTINGS -> SettingsScreen(
            settingsRepository = settingsRepository,
            onBack = { currentScreen = Screen.HOME }
        )

        Screen.HISTORY -> HistoryScreen(
            cacheDao = cacheDao,
            onBack = { currentScreen = Screen.HOME }
        )
    }
}
