package com.fgogotran.story

import java.io.File
import kotlin.test.*
import com.fgogotran.story.AutoStoryScenePolicy.Stage

class AutoStoryScenePolicyTest {
    @Test fun `reported diamond frame remains dialogue even with a choice candidate`() {
        for (candidate in listOf(false, true)) for (fallback in listOf(false, true)) {
            assertEquals(Stage.DIALOGUE, AutoStoryScenePolicy.select(true, candidate, fallback))
        }
    }

    @Test fun `without a diamond choices never borrow lingering dialogue even if fallback accepts it`() {
        assertEquals(Stage.CHOICES, AutoStoryScenePolicy.select(false, true, false))
        assertEquals(Stage.CHOICES, AutoStoryScenePolicy.select(false, true, true))
        assertEquals(Stage.WAITING, AutoStoryScenePolicy.select(false, false, false))
        assertEquals(Stage.DIALOGUE, AutoStoryScenePolicy.select(false, false, true))
    }

    @Test fun `request contract keeps speaker dialogue and choices mutually exclusive`() {
        assertEquals(Stage.DIALOGUE, AutoStoryScenePolicy.contentStage(true, true, false))
        assertEquals(Stage.DIALOGUE, AutoStoryScenePolicy.contentStage(false, true, false))
        assertEquals(Stage.CHOICES, AutoStoryScenePolicy.contentStage(false, false, true))
        assertNull(AutoStoryScenePolicy.contentStage(false, true, true))
        assertNull(AutoStoryScenePolicy.contentStage(true, true, true))
        assertNull(AutoStoryScenePolicy.contentStage(true, false, true))
        assertNull(AutoStoryScenePolicy.contentStage(true, false, false))
        assertNull(AutoStoryScenePolicy.contentStage(false, false, false))
    }

    @Test fun `old results cannot cross stages even when previous dialogue text is unchanged`() {
        val choices = AutoStoryScenePolicy.select(false, true, true)
        val dialogue = AutoStoryScenePolicy.select(true, true, false)
        assertFalse(AutoStoryScenePolicy.acceptsResult(Stage.DIALOGUE, choices))
        assertFalse(AutoStoryScenePolicy.acceptsResult(Stage.CHOICES, dialogue))
        assertFalse(AutoStoryScenePolicy.acceptsResult(Stage.CHOICES, Stage.WAITING))
        val markerGone = AutoStoryScenePolicy.select(false, false, false)
        assertFalse(AutoStoryScenePolicy.acceptsResult(Stage.DIALOGUE, markerGone))
        assertFalse(AutoStoryScenePolicy.acceptsResult(Stage.WAITING, Stage.WAITING))
        assertTrue(AutoStoryScenePolicy.acceptsResult(Stage.DIALOGUE, dialogue))
        assertTrue(AutoStoryScenePolicy.acceptsResult(Stage.CHOICES, choices))
    }

    private val service by lazy {
        listOf(File("src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt"),
            File("app/src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt"))
            .first(File::isFile).readText().replace("\r\n", "\n")
    }

    private fun function(name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(service).drop(1)
            .first { it.startsWith("$name(") }

    @Test fun `strict dialogue returns before any choice work in both AUTO coordinators`() {
        for ((name, strict, dialogueCall) in listOf(
            Triple("scanAutoScene", "if (dialogueComplete && !dialogueCompleteByFallback)", "return scanAutoDialogueScene("),
            Triple("scanVoiceOnlyAutoScene", "if (completion.complete && !completion.byFallback)", "return scanVoiceOnlyCompletedDialogueScene(")
        )) {
            val body = function(name)
            val branch = body.substringAfter(strict).substringBefore("val ")
            assertTrue(branch.contains(dialogueCall), name)
            assertTrue(body.indexOf(dialogueCall) < body.indexOf("detectChoiceBounds("), name)
            assertFalse(body.contains("mayContainChoiceButtons("), name)
            assertFalse(body.contains("mergeManualSceneRegions("), name)
            for (forbidden in listOf("recognizeChoiceRegions(", "detectChoiceBounds(", "frame.choices(")) {
                assertFalse(branch.contains(forbidden), "$name: $forbidden")
            }
        }
    }

    @Test fun `single-scene contract precedes API and stage freshness precedes display`() {
        val render = function("translateAndRenderScene")
        assertTrue(render.indexOf("AutoStoryScenePolicy.contentStage(") < render.indexOf("translateSceneSource("))
        assertTrue(render.contains("|| autoStage != null")) // Fast AUTO cache hits also confirm the scene kind.
        assertTrue(render.indexOf("isSourceVisuallyCurrent(") < render.indexOf("translationOverlay.updateImage("))
        val freshness = function("isSourceVisuallyCurrent")
        assertTrue(freshness.contains("if (strictDiamond) emptyList() else"))
        assertTrue(freshness.contains("&& markerReport.evidence"))
        assertTrue(freshness.contains("AutoStoryScenePolicy.acceptsResult(expectedAutoStage, observed)"))
        for (forbidden in listOf("ocrEngine.recognize(", "translateSceneSource(", "translator.")) {
            assertFalse(freshness.contains(forbidden))
        }
        val transition = function("observeAutoStoryStage")
        assertTrue(transition.contains("translationOverlay.hide()"))
        assertFalse(transition.contains("hideForCapture()")) // Saved pixels must not return after a stage change.
        assertFalse(transition.contains("storyTapHandoff.clear()")) // Existing choice settling remains intact.
        assertFalse(transition.contains("lastAutoRenderedSourceText =")) // Animation is not a new source.
    }
}
