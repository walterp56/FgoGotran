package com.fgogotran.story

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source-wiring guards for the Story Library OCR; device behaviour needs a device run. */
class StoryOcrFastPathWiringTest {
    private val service = listOf(
        File("src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt"),
        File("app/src/main/java/com/fgogotran/accessibility/FgoAccessibilityService.kt")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(service).drop(1)
            .first { it.startsWith("$name(") }

    private fun source(relative: String): String = listOf(
        File(relative),
        File("app/$relative")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    @Test
    fun `the story switch selects the fast dialogue OCR and keeps the full pipeline as fallback`() {
        val dialogue = function("recognizeDialogueRegions")
        assertTrue(dialogue.contains("storyFastDialogueRegions(source, screenRegions, nameOcrRegion, namePlate)"))
        assertTrue(dialogue.indexOf("storyFastDialogueRegions(") < dialogue.indexOf("recognizeExactScreenRegion("))
        assertTrue(dialogue.contains("target = OcrRegionTarget(screenRegions.dialogue, TextRegion.DIALOGUE_BOX)"))
        // Voice-only keeps its own name cache and therefore its own path.
        assertTrue(dialogue.contains("if (!useVoiceOnlyNameCache) {"))

        val fast = function("storyFastDialogueRegions")
        assertTrue(fast.contains("storyOcrScanner.scanDialogue(source, screenRegions.dialogue, nameOcrRegion)"))
        assertTrue(fast.contains("storyChapterResolver.probe("))
        assertTrue(fast.contains("kind = StoryChapterResolver.LineKind.DIALOGUE"))
        // A rejected read must hand the same frame back to the full pipeline.
        assertTrue(fast.substringAfter("if (!storyChapterResolver.probe(").contains("return null"))
    }

    @Test
    fun `choices use the fast path only when every panel is recognised and matched`() {
        // Two overloads share this name; the scan one calls the fast path first.
        assertTrue(service.contains("storyFastChoiceRegions(source, choiceBounds)?.let { return it }"))
        assertTrue(
            service.indexOf("storyFastChoiceRegions(source, choiceBounds)") <
                service.indexOf("targets = choiceBounds.map { OcrRegionTarget(it, TextRegion.CHOICE_BUTTON) }")
        )

        val fast = function("storyFastChoiceRegions")
        assertTrue(fast.contains("storyOcrScanner.scanChoices(source, choiceBounds)"))
        assertTrue(fast.contains("kind = StoryChapterResolver.LineKind.CHOICE"))
        assertTrue(fast.contains("if (!matched) {"))
        assertTrue(fast.contains("ChoiceRecognitionResult(choiceBounds, regions)"))
        // One weak panel is not half a list.
        assertTrue(fast.contains("if (lines.size != choiceBounds.size) return null"))
    }

    @Test
    fun `the name cover and clip warning follow the measured glyph box`() {
        val render = function("storyRegionForRender")
        assertTrue(
            render.contains(
                "val textBounds = namePlate?.let { plate -> measureNameTextBounds(source, region.lines, plate) }"
            )
        )
        assertTrue(render.contains("sourceNameTextBounds = textBounds"))
        assertTrue(render.contains("lines = lines"))
        // The warning must see the tight box, so the measurement runs first.
        assertTrue(render.indexOf("measureNameTextBounds(") < render.indexOf("warnIfNameClipped("))
    }

    @Test
    fun `the fast choice and name boxes follow the glyphs`() {
        val scanner = source("src/main/java/com/fgogotran/ocr/StoryOcrScanner.kt")
        // The service samples the text colour inside the reported box, so it must not be the panel.
        assertTrue(scanner.contains("val reported = crops.map { StoryOcrGeometry.inkBounds(source, it) ?: it }"))
        assertTrue(scanner.contains("StoryOcrAssembler.choices(results, buttons, reported)"))
        assertTrue(scanner.contains("StoryOcrGeometry.inkBounds(source, band) ?: band"))
        assertTrue(scanner.contains("StoryOcrAssembler.name(results.getOrNull(rows.size), band)"))
        // Recognition takes the choice columns from the glyphs: the fixed insets cut a long
        // centred line, and the truncated read then still came back with full confidence.
        assertTrue(
            scanner.contains(
                "StoryOcrGeometry.choiceCrop(button, StoryOcrGeometry.choiceTextColumns(source, button))"
            )
        )
        assertTrue(scanner.contains("ocrEngine.recognizeStoryBands(source, crops)"))
    }

    @Test
    fun `the fast path needs the switch the active pack and the preference`() {
        val gate = function("storyFastOcrEnabled")
        for (required in listOf(
            "settingsRepository.storyChapterFastOcr.first()",
            "settingsRepository.storyChapterDbEnabled.first()",
            "settingsRepository.storyChapterId.first().isNotBlank()"
        )) {
            assertTrue(gate.contains(required), required)
        }
        assertFalse(function("storyFastDialogueRegions").contains("ocrEngine.recognize("))
        assertFalse(function("storyFastChoiceRegions").contains("ocrEngine.recognize("))
    }
}
