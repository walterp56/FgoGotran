package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.TranslationReasoning
import com.fgogotran.data.TranslationRequestOptions
import org.junit.Test
import kotlin.test.assertEquals

class TranslationOutputBudgetTest {
    @Test fun cropBudgetUsesSelectedTextLengthAndAllowsDenseGlmRequests() {
        val glm = SettingsRepository.BACKEND_ZHIPU
        val options = TranslationRequestOptions.recommended(glm)
        assertEquals(512, TranslationOutputBudget.resolve(glm, "glm-4.5-air", options, 704, "scene", null, false))
        assertEquals(512, TranslationOutputBudget.resolve(glm, "glm-4.5-air", options, 512, "crop", 256, false))
        assertEquals(1024, TranslationOutputBudget.resolve(glm, "glm-4.5-air", options, 512, "crop", 257, false))
        assertEquals(1024, TranslationOutputBudget.resolve(glm, "glm-4.5-air", options, 512, "strict_retry_crop", 257, false))
    }

    @Test fun subtitleLimitsAndExplicitOverridesTakePrecedence() {
        val gpt = SettingsRepository.BACKEND_GPT
        val options = TranslationRequestOptions.recommended(gpt)
        assertEquals(1024, TranslationOutputBudget.resolve(gpt, "gpt-6-luna", options, 256, "single", null, false))
        assertEquals(512, TranslationOutputBudget.resolve(gpt, "gpt-6-luna", options, 512, "battle", null, true))
        assertEquals(512, TranslationOutputBudget.resolve(gpt, "gpt-6-luna", options, 512, "strict_retry", null, true))
        val noReasoning = options.copy(reasoning = TranslationReasoning.NONE)
        assertEquals(96, TranslationOutputBudget.resolve(gpt, "gpt-6-luna", noReasoning, 96, "api_test", null, false))
        val override = options.copy(outputBudget = 240)
        assertEquals(240, TranslationOutputBudget.resolve(gpt, "gpt-6-luna", override, 512, "crop", 300, false))
    }
}
