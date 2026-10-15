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
}
