package com.fgogotran.ui.component

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fgogotran.MainActivity
import com.fgogotran.data.SettingsRepository
import com.fgogotran.localization.AppLanguageManager
import com.fgogotran.overlay.DialogueTextSpeed
import com.fgogotran.ui.screen.TranslationPreferencesScreen
import com.fgogotran.ui.theme.FgoGotranTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt in only on a disposable emulator; restores the settings it changes, including on failure. */
class TranslationPreferencesUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun preferencesPersistAndBothBackActionsProtectUnsavedMasterDetails() {
        assumeTrue("Use -e preferencesUi true on a disposable emulator",
            InstrumentationRegistry.getArguments().getString("preferencesUi") == "true")
        val context = instrumentation.targetContext
        val oldUiLanguage = AppLanguageManager.getLanguage(context)
        AppLanguageManager.setLanguage(context, AppLanguageManager.LANGUAGE_ENGLISH)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val repo = activity.settingsRepository
        val profile = runBlocking { repo.playerProfile.first() }
        val target = runBlocking { repo.targetLanguage.first() }
        val original = runBlocking { repo.showOriginalGameText.first() }
        val ruby = runBlocking { repo.translationIncludeRuby.first() }
        val storyContext = runBlocking { repo.translationContextEnabled.first() }
        val scenes = runBlocking { repo.translationContextSceneCount.first() }
        val playback = runBlocking { repo.dialoguePlaybackSettings.first() }
        val exits = AtomicInteger()
        var generation = 0
        fun mount() {
            val id = ++generation
            instrumentation.runOnMainSync {
                activity.setContent {
                    FgoGotranTheme(darkTheme = true) {
                        key(id) {
                            TranslationPreferencesScreen(repo, onBack = {
                                exits.incrementAndGet()
                                activity.setContent { Text("Returned") }
                            })
                        }
                    }
                }
            }
            compose.waitForIdle()
        }
        try {
            runBlocking {
                repo.setPlayerProfile("Initial Master", SettingsRepository.PLAYER_GENDER_MALE)
                repo.setTargetLanguage("en")
                repo.setShowOriginalGameText(false)
                repo.setTranslationIncludeRuby(true)
                repo.setTranslationContextEnabled(false)
            }
            mount()
            compose.onAllNodesWithText("Observatory").assertCountEquals(2)
            text("English").performClick()
            compose.onNodeWithText("Simplified Chinese").performClick()
            eventually { runBlocking { repo.targetLanguage.first() } == "zh-Hans" }
            compose.onAllNodesWithText("观星台").assertCountEquals(2)
            text("Simplified Chinese").performClick()
            compose.onNodeWithText("English").performClick()
            eventually { runBlocking { repo.targetLanguage.first() } == "en" }
            compose.onAllNodesWithText("Observatory").assertCountEquals(2)
            text("English").performClick()
            compose.onNodeWithText("Traditional Chinese").performClick()
            eventually { runBlocking { repo.targetLanguage.first() } == "zh-Hant" }
            compose.onAllNodesWithText("觀星臺").assertCountEquals(2)
            compose.onAllNodesWithText("觀星臺")[0].performScrollTo().assertIsDisplayed()
            screenshot("language-and-ruby")
            described("Bilingual display").performClick()
            eventually { runBlocking { repo.showOriginalGameText.first() } }
            described("Include small text above words").performClick()
            eventually { !runBlocking { repo.translationIncludeRuby.first() } }
            reveal(hasContentDescription("靈基外骨骼啟動。\n出擊！\n霊基外骨骼〈オルテナウス〉起動。\n行きます！"))
                .assertIsDisplayed()
            screenshot("dialogue-playback")

            slider(0f, 5f).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            eventually { runBlocking { repo.dialoguePlaybackSettings.first().speed } == DialogueTextSpeed.HALF }
            slider(0f, 3f).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
            eventually { runBlocking { repo.dialoguePlaybackSettings.first().pauseLevel } == 3 }
            text("Turn on story context to adjust this value.").assertIsDisplayed()
            screenshot("context-off")
            slider(1f, 5f).assertIsNotEnabled()
            described("Use story context").performClick()
            eventually { runBlocking { repo.translationContextEnabled.first() } }
            slider(1f, 5f).performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
            eventually { runBlocking { repo.translationContextSceneCount.first() } == 3 }

            reveal(hasSetTextAction()).performTextReplacement("Draft Master")
            text("Unsaved changes").assertIsDisplayed()
            text("Back").performClick()
            compose.onNodeWithText("Unsaved Master details").assertIsDisplayed()
            screenshot("unsaved-master")
            assertEquals(0, exits.get())
            compose.onNodeWithText("Keep editing").performClick()
            assertEquals("Initial Master", runBlocking { repo.playerProfile.first().name })
            reveal(hasSetTextAction()).assertTextContains("Draft Master")
            // Same dispatcher used by system back, without the test keyboard consuming the key.
            compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Unsaved Master details").assertIsDisplayed()
            compose.onNodeWithText("Discard and go back").performClick()
            eventually { exits.get() == 1 }
            assertEquals("Initial Master", runBlocking { repo.playerProfile.first().name })

            mount()
            reveal(hasSetTextAction()).performTextReplacement("  Saved Master  ")
            text("Master gender").performClick()
            compose.onNodeWithText("Female").performClick()
            text("Save Master details").performClick()
            text("Saved").assertIsDisplayed()
            val saved = runBlocking { repo.playerProfile.first() }
            assertEquals("Saved Master", saved.name)
            assertEquals(SettingsRepository.PLAYER_GENDER_FEMALE, saved.gender)
            text("Save Master details").assertIsEnabled()
            text("Back").performClick()
            eventually { exits.get() == 2 }
            mount()
            reveal(hasSetTextAction()).assertTextContains("Saved Master")
        } catch (error: Throwable) {
            runCatching { screenshot("failure") }
            throw error
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            runBlocking {
                repo.setPlayerProfile(profile.name, profile.gender)
                repo.setTargetLanguage(target)
                repo.setShowOriginalGameText(original)
                repo.setTranslationIncludeRuby(ruby)
                repo.setTranslationContextEnabled(storyContext)
                repo.setTranslationContextSceneCount(scenes)
                repo.setDialogueTextSpeed(playback.speed)
                repo.setDialoguePunctuationPause(playback.pauseLevel)
            }
            AppLanguageManager.setLanguage(context, oldUiLanguage)
        }
    }

    private fun text(value: String) = reveal(hasText(value))
    private fun described(value: String) = reveal(hasContentDescription(value))
    private fun slider(min: Float, max: Float) = reveal(SemanticsMatcher("slider $min..$max") {
        it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == min..max
    })

    private fun reveal(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val node = compose.onNode(matcher)
        var parent = node.fetchSemanticsNode().parent
        while (parent != null) {
            if (parent.config.getOrNull(SemanticsActions.ScrollBy) != null) {
                node.performScrollTo()
                break
            }
            parent = parent.parent
        }
        return node
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Preference did not persist" }
            SystemClock.sleep(50)
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        // Platform window enter animations use real time, outside Compose's test clock.
        SystemClock.sleep(300)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(instrumentation.targetContext.cacheDir, "translation-preferences-ui").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
