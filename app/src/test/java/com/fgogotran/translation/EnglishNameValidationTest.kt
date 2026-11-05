package com.fgogotran.translation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnglishNameValidationTest {
    @Test
    fun `english state rejects japanese and chinese text`() {
        assertFalse(EnglishNameValidation.isValidState("観測手・左", 48))
        assertFalse(EnglishNameValidation.isValidState("観测手・左", 48))
        assertFalse(EnglishNameValidation.isValidState("观测手", 48))
        assertTrue(EnglishNameValidation.isValidState("Left Observer", 48))
    }

    @Test
    fun `english full name rejects an untouched japanese state`() {
        assertFalse(
            EnglishNameValidation.isValidFullName(
                sourceName = "ネモ・マリーン（観測手・左)",
                translatedName = "Nemo Marine（観测手・左）",
                baseTranslation = "Nemo Marine",
                maxLength = 48
            )
        )
    }

    @Test
    fun `english full name accepts a translated state with the same structure`() {
        assertTrue(
            EnglishNameValidation.isValidFullName(
                sourceName = "ネモ・マリーン（観測手・左)",
                translatedName = "Nemo Marine (Left Observer)",
                baseTranslation = "Nemo Marine",
                maxLength = 48
            )
        )
        assertTrue(
            EnglishNameValidation.isValidFullName(
                sourceName = "ネモ・マリーン（観測手・左)",
                translatedName = "Nemo Marine（Left Observer）",
                baseTranslation = "Nemo Marine",
                maxLength = 48
            )
        )
    }

    @Test
    fun `english full name requires the resolved base and the source bracket shape`() {
        assertFalse(
            EnglishNameValidation.isValidFullName(
                sourceName = "ネモ・マリーン（観測手・左)",
                translatedName = "Left Observer",
                baseTranslation = "Nemo Marine",
                maxLength = 48
            )
        )
        assertFalse(
            EnglishNameValidation.isValidFullName(
                sourceName = "ネモ・マリーン（観測手・左)",
                translatedName = "Nemo Marine Left Observer",
                baseTranslation = "Nemo Marine",
                maxLength = 48
            )
        )
    }
}
