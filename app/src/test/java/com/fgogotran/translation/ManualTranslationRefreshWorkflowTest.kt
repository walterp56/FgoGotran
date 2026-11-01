package com.fgogotran.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android coordinator/cache wiring checks; real screenshot/API/cache behaviour needs a device run. */
class ManualTranslationRefreshWorkflowTest {
    private val service = source("accessibility/FgoAccessibilityService.kt")
    private val translator = source("translation/Translator.kt")

    @Test
    fun `only manual scene translation requests a fresh answer`() {
        val render = function(service, "translateAndRenderScene")
        val request = render.substringAfter("val sceneTranslation = translateSceneSource(")
            .substringBefore("val translationDuration")
        assertTrue(request.contains("forceFreshTranslation = mode == ProcessingMode.MANUAL_TAP"))
        assertFalse(request.contains("forceFreshTranslation = mode.userInitiated"))
        val scene = function(service, "translateSceneSource")
        assertTrue(scene.contains("forceFreshTranslation = forceFreshTranslation"))
        for (method in listOf("translate", "translateBatch", "translateScene")) {
            assertTrue(function(translator, method).contains("forceFreshTranslation: Boolean = false"), method)
        }
    }

    @Test
    fun `manual capture and OCR run again even when recognized source is unchanged`() {
        val screen = function(service, "processScreen")
        assertTrue(screen.indexOf("takeScreenshotCompat(") < screen.indexOf("processManualScreen("))
        val manual = function(service, "processManualScreen")
        assertTrue(manual.indexOf("scanManualScene(") < manual.indexOf("translateAndRenderScene("))
        assertFalse(manual.contains("isAlreadyRenderedSource("))
        assertFalse(manual.contains("restoreHiddenOverlay"))
        val scan = function(service, "scanManualScene")
        assertTrue(scan.contains("recognizeChoiceRegions(source, screenRegions, ProcessingMode.MANUAL_TAP, frame)"))
        assertEquals(2, Regex("recognizeDialogueRegions\\(source, screenRegions\\)").findAll(scan).count())
        val dialogue = function(service, "recognizeDialogueRegions")
        assertTrue(dialogue.contains("target = OcrRegionTarget(screenRegions.dialogue, TextRegion.DIALOGUE_BOX)"))
    }

    @Test
    fun `failed JP manual attempts cannot restore the previous overlay`() {
        val screen = function(service, "processScreen")
        assertTrue(screen.contains(
            "restoreHiddenOverlay = mode != ProcessingMode.MANUAL_TAP || !isJapaneseServer()"
        ))
        val manual = function(service, "processManualScreen")
        assertTrue(manual.contains("runnerOverlay.showTranslationFailureFeedback(fromUserTap = true)"))
        assertTrue(manual.contains("translationOverlay.hide()"))
        assertFalse(manual.contains("return true"))
        assertFalse(manual.contains("restoreAfterCapture("))
        assertFalse(service.contains("Manual source unchanged; restoring previous overlay without translation"))
    }

    @Test
    fun `automatic and semi-auto duplicate guards remain in place`() {
        for (method in listOf("processAutoScreen", "processSemiAutoStoryScreen", "processSemiAutoChoiceScreen")) {
            assertTrue(function(service, method).contains("isAlreadyRenderedSource("), method)
        }
        assertTrue(function(service, "processSemiAutoChoiceScreen").contains("restoreHiddenOverlay"))
        assertTrue(function(service, "processCropTranslation").contains("useTranslationCache = false"))
    }

    @Test
    fun `refresh bypasses reads but preserves user-controlled writes`() {
        val single = function(translator, "translate")
        assertTrue(single.contains("val cacheEnabled = config.cacheEnabled && useTranslationCache"))
        for (method in listOf("translate", "translateBatch", "translateScene")) {
            val body = function(translator, method)
            assertTrue(body.contains("val cacheReadEnabled = cacheEnabled && !forceFreshTranslation"), method)
            assertTrue(body.contains("if (cacheReadEnabled) {"), method)
            assertTrue(body.contains("if (forceFreshTranslation) CACHE_MISS_REFRESH_REQUESTED else CACHE_MISS_DISABLED"), method)
            assertFalse(body.contains("useTranslationCache = false"), method)
        }
        assertTrue(single.contains("if (cacheEnabled && canCacheResult) {\n            cacheTranslatedText("))
        assertTrue(function(translator, "translateBatch").contains("if (cacheEnabled) {\n                cacheTranslatedText("))
        assertTrue(function(translator, "translateScene").contains("if (cacheEnabled) {\n                        cacheTranslatedText("))
    }

    @Test
    fun `dialogue and choice shortcuts cannot avoid a requested fresh API answer`() {
        val single = function(translator, "translate")
        assertTrue(single.contains("if (!forceFreshTranslation) translationMemory.lookupNormalized(normalizedText)"))
        assertTrue(single.contains("if (!forceFreshTranslation) findCharacterNameTranslation("))
        assertTrue(single.contains("if (!forceFreshTranslation && !EmphasisText.has(normalizedText) && !TextNormalizer.hasRubyAnnotations(normalizedText))"))
        val batch = function(translator, "translateBatch")
        assertTrue(batch.contains("val officialMemory = if (forceFreshTranslation) null else translationMemory.lookupNormalized(normalizedText)"))
        for (lookup in listOf("findCharacterNameTranslation", "findTermTranslation")) {
            assertTrue(batch.contains("if (forceFreshTranslation || hasRuby) null else $lookup("), lookup)
        }
        val scene = function(translator, "translateScene")
        assertEquals(2, Regex("if \\(!forceFreshTranslation\\) translationMemory.lookupNormalized\\(normalized\\)")
            .findAll(scene).count())
        assertTrue(scene.contains("if (!forceFreshTranslation && dialogueResult == null &&"))
        assertEquals(2, Regex("if \\(!forceFreshTranslation && choiceResults\\[index] == null &&")
            .findAll(scene).count())
    }

    @Test
    fun `every scene dialogue and choice delegation retains refresh including fallbacks`() {
        val calls = translationCalls(function(translator, "translateScene"))
        val textCalls = calls.filterNot { it.contains("input.name.orEmpty()") }
        assertEquals(8, textCalls.size, "Include single, batch, model-specific and existing fallback paths")
        for (call in textCalls) {
            assertTrue(call.contains("forceFreshTranslation = forceFreshTranslation"), call)
        }
        val nameCalls = calls.filter { it.contains("input.name.orEmpty()") }
        assertEquals(3, nameCalls.size)
        assertTrue(nameCalls.none { it.contains("forceFreshTranslation") }, "Name-box policy is unchanged")
    }

    @Test
    fun `both batch fallback paths retain refresh`() {
        val calls = translationCalls(function(translator, "translateBatch"))
        assertEquals(2, calls.size, "Both API failure and unsafe-item fallback must remain fresh")
        for (call in calls) {
            assertTrue(call.contains("forceFreshTranslation = forceFreshTranslation"), call)
        }
    }

    @Test
    fun `replacement uses existing database and memory entries without changing cache identity`() {
        val write = function(translator, "cacheTranslatedText")
        assertTrue(write.contains("cacheDao.insert("))
        assertTrue(write.contains("jpTextHash = hash"))
        assertTrue(write.contains("putMemoryCachedTranslation(hash, simplified)"))
        assertTrue(write.indexOf("if (safety.status != TranslationSafetyStatus.OK)") < write.indexOf("cacheDao.insert("))
        assertTrue(source("translation/TranslationCache.kt").contains("@Insert(onConflict = OnConflictStrategy.REPLACE)"))
        assertFalse(function(translator, "cacheKey").contains("forceFreshTranslation"))
        val lookup = function(translator, "lookupCachedTranslation")
        assertTrue(lookup.contains("getMemoryCachedTranslation(hash)"))
        assertTrue(lookup.contains("cacheDao.getCached(hash)"))
    }

    @Test
    fun `name references ruby prompt construction and context remain independent of refresh`() {
        val scene = function(translator, "translateScene")
        val names = scene.substringAfter("normalizedName?.let { normalized ->")
            .substringBefore("normalizedDialogue?.let { normalized ->")
        assertTrue(names.contains("findExactVisibleCharacterNameTranslation("))
        assertFalse(names.contains("forceFreshTranslation"))
        assertTrue(scene.contains("if (cacheEnabled && nameForLlm != null && nameHash != null && nameResult == null)"))
        val request = function(service, "translateSceneSource")
        assertTrue(request.contains("TranslationRubyPolicy.prepare(fullInput, includeRuby)"))
        assertTrue(request.contains("excludeDialogueSourceKey = sceneSource.historyDialogueSourceKey()"))
        for (method in listOf("buildSingleUserPrompt", "buildBatchUserPrompt", "buildSceneUserPrompt")) {
            assertFalse(function(translator, method).contains("forceFreshTranslation"), method)
        }
        assertFalse(source("translation/PromptBuilder.kt").contains("forceFreshTranslation"))
    }

    private fun source(relativePath: String): String = listOf(
        File("src/main/java/com/fgogotran/$relativePath"),
        File("app/src/main/java/com/fgogotran/$relativePath")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(source: String, name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(source).drop(1)
            .first { it.startsWith("${if (name in listOf("translate", "translateBatch", "translateScene")) name + "Internal" else name}(") }

    private fun translationCalls(body: String): List<String> =
        Regex("\\btranslate(?:Batch)?Internal\\(").findAll(body)
            .filter { it.range.first > 0 }
            .map { match ->
                var depth = 1
                var end = match.range.last + 1
                while (depth > 0 && end < body.length) {
                    when (body[end++]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                }
                check(depth == 0) { "Unclosed translation call" }
                body.substring(match.range.first, end)
            }.toList()
}
