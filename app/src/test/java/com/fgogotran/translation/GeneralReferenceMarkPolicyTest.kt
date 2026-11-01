package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeneralReferenceMarkPolicyTest {
    private val source = "即ち、※※※※※※※※が設置された世界であり───"
    private val instruction = "Preserve each consecutive ※ run exactly, including its count; do not infer hidden words."

    @Test
    fun `Chinese and English output must preserve the full obscuration run`() {
        for (translated in listOf("也就是設置了※※※※※※※※的世界───", "也就是设置了※※※※※※※※的世界───",
            "A world in which ※※※※※※※※ was established—")) {
            assertTrue(GeneralReferenceMarkPolicy.isPreserved(source, translated))
            for (replacement in listOf("", "※※", "XXXXXXXX", "■■■■■■■■", "某個東西", "※ ※ ※ ※ ※ ※ ※ ※")) {
                assertFalse(GeneralReferenceMarkPolicy.isPreserved(source, translated.replace("※".repeat(8), replacement)))
            }
        }
    }

    @Test
    fun `multiple runs keep boundaries and order rather than only total count`() {
        val input = "前※※中※※※後"
        assertTrue(GeneralReferenceMarkPolicy.isPreserved(input, "before※※middle※※※after"))
        for (bad in listOf("前※※※※※後", "前※※※中※※後", "前※※中※※※後※")) {
            assertFalse(GeneralReferenceMarkPolicy.isPreserved(input, bad))
        }
    }

    @Test
    fun `single real JP footnotes do not activate mask policy`() {
        for (input in listOf("（※ガレス調べ）", "※バグではありません", "※根拠はありません", "行こう。")) {
            assertFalse(GeneralReferenceMarkPolicy.containsRun(input))
            assertTrue(GeneralReferenceMarkPolicy.isPreserved(input, "ordinary translation"))
            assertEquals("", buildString { appendGeneralReferenceMarkSection(listOf(input)) })
        }
    }

    @Test
    fun `general single batch crop and subtitle prompts activate the instruction only for current masks`() {
        val builder = PromptBuilder()
        val prompts = listOf(
            builder.buildUserPrompt(source, emptyList()),
            buildGeneralSingleUserPrompt(builder, source, emptyList(), translateAsChoices = true),
            buildGeneralSingleUserPrompt(builder, source, emptyList(), translateAsName = true),
            buildGeneralBatchUserPrompt(listOf(source, "行こう。")),
            builder.buildCropUserPrompt(source),
            builder.buildDialogueSubtitleRowsPrompt(listOf(source))
        )
        prompts.forEach { prompt -> assertEquals(1, Regex(Regex.escape(instruction)).findAll(prompt).count()) }
        assertFalse(builder.buildUserPrompt("行こう。", listOf(source)).contains(instruction),
            "Choices are only reference context in a single-dialogue request")
        assertEquals("Current Japanese:\n行こう。", builder.buildUserPrompt("行こう。", emptyList()))
    }

    @Test
    fun `cache scope changes only general requests containing repeated marks`() {
        val legacy = listOf("prompt", "backend", "source")
        val plain = extendGeneralPromptCacheMaterial(legacy, true)
        for (input in listOf("普通の台詞。", "※ガレス調べ", "")) {
            assertEquals(plain, extendGeneralPromptCacheMaterial(legacy, true, referenceMarkSources = listOf(input)))
        }
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            val marked = extendGeneralPromptCacheMaterial(legacy, true, targetLanguage = target,
                referenceMarkSources = listOf(source))
            assertTrue(GeneralReferenceMarkPolicy.CACHE_VERSION in marked)
            assertSame(legacy, extendGeneralPromptCacheMaterial(legacy, false, targetLanguage = target,
                referenceMarkSources = listOf(source)))
        }
    }
}
