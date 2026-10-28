package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class GeneralPromptCachePolicyTest {
    private val legacy = listOf("prompt", "backend", "source", "")

    @Test
    fun legacyKeysRetainExactMaterialEvenWithGeneralMetadataSupplied() {
        assertSame(legacy, extendGeneralPromptCacheMaterial(legacy, false, "new speaker", "female"))
        assertEquals(
            legacy.joinToString("\u001F"),
            extendGeneralPromptCacheMaterial(legacy, false).joinToString("\u001F")
        )
    }

    @Test
    fun GeneralContractNamespaceSeparatesOldUnvalidatedResults() {
        val material = extendGeneralPromptCacheMaterial(legacy, true)
        assertEquals(legacy, material.take(legacy.size))
        assertNotEquals(legacy.joinToString("\u001F"), material.joinToString("\u001F"))
    }

    @Test
    fun changesToResolvedSpeakerOrUsedGenderInvalidateGeneralResults() {
        val original = extendGeneralPromptCacheMaterial(legacy, true, "old target (JP: 名前)", "female")
        assertNotEquals(original, extendGeneralPromptCacheMaterial(legacy, true, "new target (JP: 名前)", "female"))
        assertNotEquals(original, extendGeneralPromptCacheMaterial(legacy, true, "old target (JP: 名前)", "male"))
        assertEquals(original, extendGeneralPromptCacheMaterial(legacy, true, "old target (JP: 名前)", "female"))
    }

    @Test
    fun `Hant requests cannot reuse Hans or pre-policy cache entries even without speaker or glossary`() {
        val hans = extendGeneralPromptCacheMaterial(legacy, true, targetLanguage = "zh-Hans")
        val hant = extendGeneralPromptCacheMaterial(legacy, true, targetLanguage = "zh-Hant")
        assertNotEquals(hans, hant)
        assertEquals(listOf("target=zh-Hant", GENERAL_HANT_REFERENCE_POLICY_VERSION), hant.takeLast(2))
        assertEquals(hans, hant.dropLast(2))
        for (alias in listOf("zh-Hant-TW", "zh-Hant-HK")) {
            assertEquals(hant, extendGeneralPromptCacheMaterial(legacy, true, targetLanguage = alias))
        }
    }

    @Test
    fun `Hans and English retain their exact general hash material`() {
        val previous = legacy + listOf("general-response-contract-v1", "speaker", "female")
        for (target in listOf("zh-Hans", "zh-CN", "en", "en-US")) {
            assertEquals(previous.joinToString("\u001F"),
                extendGeneralPromptCacheMaterial(legacy, true, "speaker", "female", target).joinToString("\u001F"))
        }
    }

    @Test
    fun `legacy routing bypasses the Hant reference namespace for every target`() {
        for (target in listOf("zh-Hans", "zh-Hant", "zh-Hant-HK", "en")) {
            assertSame(legacy, extendGeneralPromptCacheMaterial(legacy, false, "speaker", "female", target))
        }
    }
}
