package com.fgogotran

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fgogotran.chapter.ui.ChapterScreen
import com.fgogotran.data.AppThemeMode
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.ui.theme.FgoGotranTheme

/** Presentation bridge only. No OCR/translation services or app repositories enter Chapter. */
class ChapterHostActivity : ComponentActivity() {
    private var systemDark by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguageManager.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshTheme()
        val theme = AppThemeMode.fromPreference(intent.getStringExtra(EXTRA_THEME))
        setContent {
            FgoGotranTheme(darkTheme = theme.isDark(systemDark)) { ChapterScreen(onExit = ::finish) }
        }
    }

    override fun onResume() { super.onResume(); refreshTheme() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); refreshTheme() }
    private fun refreshTheme() {
        systemDark = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    companion object { const val EXTRA_THEME = "chapter.theme" }
}
