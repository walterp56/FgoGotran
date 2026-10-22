package com.fgogotran.chapter.architecture

import java.io.File
import kotlin.test.*

class NativeEntryBoundaryTest {
    private val root = File(requireNotNull(System.getProperty("chapter.projectDir")))
    private fun source(path: String) = File(root, "src/main/java/com/fgogotran/chapter/$path").readText()

    @Test fun `normal entry installs and restores only through the verified native release flow`() {
        val landing = source("ui/ChapterScreen.kt")
        listOf("ChapterBrowser(", "LegacyStoryPackRecoveryCoordinator.get", "StoryPackController", "StoryPackLibrary",
            "StoryPackPreviewer", "OpenDocument", "preparePacks", "compile(").forEach {
            assertFalse(landing.contains(it), "Normal entry must not initialise $it")
        }
        assertTrue(landing.contains("NativeInstallViewModel"))
        assertTrue(landing.contains("chapter_native_install_now"))
        assertTrue(landing.contains("chapter_native_restore"))
    }

    @Test fun `retired source workflow is absent from production`() {
        val sources = File(root, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        for (name in listOf("StoryPackCompiler.kt", "StoryTextCompiler.kt", "StoryPackLibrary.kt", "StoryPackController.kt",
            "StoryPackPreview.kt", "StoryPackArchive.kt", "StoryPackInstallSession.kt", "StoryPackCard.kt", "StoryPackInstallCard.kt",
            "ChapterDeveloperScreen.kt", "ChapterDeveloperViewModel.kt", "ChapterAssetTestCard.kt", "ChapterRecoveryScreen.kt")) {
            assertTrue(sources.none { it.name == name }, "$name must not be packaged")
        }
        val lifecycle = source("ui/ChapterPageLifecycle.kt")
        assertTrue(lifecycle.contains("onDispose")); assertTrue(lifecycle.contains("stop()"))
    }
}
