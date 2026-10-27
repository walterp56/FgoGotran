package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BilingualOriginalTextTest {
    @Test fun `reported two-row Japanese has no merged rendering alternative`() {
        val source = "大気中の魔力密度が爆発的に上昇し、\n二つ目の高濃度魔力反応が出現！"
        val candidates = BilingualOriginalText.candidates(source, 2) { error("Two rows must not reflow") }
        assertEquals(listOf(source), candidates)
        assertEquals(2, candidates.single().lines().size)
    }

    @Test fun `source normalization preserves explicit rows and inline ruby`() {
        val source = "  星見台〈カルデア〉へ。\r\n\r\n  まだ、\tまだ……！  "
        assertEquals(listOf("星見台〈カルデア〉へ。\nまだ、 まだ……！"),
            BilingualOriginalText.candidates(source, 2) { error("Two rows must not reflow") })
    }

    @Test fun `single-row source stays one row and blank source has no candidate`() {
        assertEquals(listOf("はじめまして。"),
            BilingualOriginalText.candidates("はじめまして。", 2) { error("One row must not reflow") })
        assertTrue(BilingualOriginalText.candidates(" \r\n\t", 2) { error("Blank source must not reflow") }.isEmpty())
    }

    @Test fun `more than two OCR rows can still use the existing complete line planner`() {
        val source = listOf("大気中の魔力密度が", "爆発的に上昇し、", "二つ目の反応が出現！")
        var planningCalls = 0
        val candidates = BilingualOriginalText.candidates(source.joinToString("\n"), 2) { rows ->
            planningCalls++
            DialogueLinePlanner.plan(rows, 20f, { it.length.toFloat() })
        }
        assertEquals(1, planningCalls)
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            assertEquals(source.joinToString(""), candidate.filterNot(Char::isWhitespace))
            assertTrue(candidate.lines().size <= 2)
        }
    }

    @Test fun `smaller Japanese starts at 26 reference pixels in every viewport`() {
        for (scale in listOf(2f / 3f, 1f, 2f, 4f)) {
            val fit = assertNotNull(BilingualOriginalText.largestFitting(scale) { it })
            assertEquals(26f * scale, fit.textSize, 0.001f)
            assertEquals(fit.textSize, fit.value)
        }
    }

    @Test fun `four-row Chinese and English fit by reducing only Japanese before translation`() {
        for ((bodyHeight, rubyHeight, availableHeight) in listOf(
            Triple(50f, 24f, 185f), Triple(49f, 19f, 175f)
        )) {
            val fit = assertNotNull(ProportionalDialogueFit.largest(53f, viewportScale = 1f) { sizes ->
                BilingualOriginalText.largestFitting(1f) { original ->
                    val metrics = BilingualDialogueGeometry.Metrics(
                        translation = BilingualDialogueGeometry.TextMetrics(40f, 0f, bodyHeight),
                        original = BilingualDialogueGeometry.TextMetrics(original * 0.88f, 0f, original),
                        rubyHeight = rubyHeight, rubyGap = 3f, translationToOriginalGap = 4f, beforeRubyGap = 2f
                    )
                    BilingualDialogueGeometry.layout(2, 2, metrics, -26f)
                        .takeIf { it.bottom <= availableHeight }?.let { original to it }
                }?.value
            })
            assertEquals(53f, fit.sizes.body)
            assertEquals(21.2f, fit.sizes.ruby, 0.001f)
            val (originalSize, layout) = fit.value
            assertTrue(originalSize in 14f..25.9f)
            assertTrue(layout.bottom <= availableHeight)
            assertEquals(listOf(false, true, false, true), layout.rows.map { it.original })
            assertEquals(listOf(0, 0, 1, 1), layout.rows.map { it.lineIndex })
        }
    }

    @Test fun `long Japanese can fit below the former source floor without reducing translated text`() {
        val sourceRows = listOf("日本語".repeat(50), "原文".repeat(50))
        val fit = assertNotNull(ProportionalDialogueFit.largest(53f, viewportScale = 1f) { sizes ->
            BilingualOriginalText.largestFitting(1f) { original ->
                original.takeIf { sourceRows.all { row -> row.length * original <= 1700f } }
            }?.value?.let { sizes.body to it }
        })
        assertEquals(53f, fit.value.first)
        assertTrue(fit.value.second < 14f)
        assertTrue(fit.value.second > 11f)
    }

    @Test fun `translation overflow still scales body and ruby without tying Japanese to that factor`() {
        val fit = assertNotNull(ProportionalDialogueFit.largest(53f, viewportScale = 1f) { sizes ->
            if (sizes.body > 40f) null else BilingualOriginalText.largestFitting(1f) { original -> original }?.value
        })
        assertTrue(fit.sizes.body in 39.7f..40f)
        assertEquals(fit.sizes.body * 0.4f, fit.sizes.ruby, 0.001f)
        assertEquals(26f, fit.value)
    }

    @Test fun `source fitting finds a normal bracket when subpixel ink would be empty`() {
        val fit = assertNotNull(BilingualOriginalText.largestFitting(1f) { size ->
            size.takeIf { it in 8f..20f }
        })
        assertTrue(fit.textSize in 19.8f..20f)
    }

    @Test fun `unrenderable source has no clipping or merged fallback`() {
        assertNull(BilingualOriginalText.largestFitting<String>(1f) { null })
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(BilingualOriginalText.largestFitting(scale) { it })
        }
    }
}
