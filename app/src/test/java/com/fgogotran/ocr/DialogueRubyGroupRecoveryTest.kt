package com.fgogotran.ocr

import com.fgogotran.ocr.DialogueAnnotationCleaner.Component
import com.fgogotran.ocr.DialogueRubyGroupRecovery.Reading
import com.fgogotran.ocr.DialogueRubyLayout.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogueRubyGroupRecoveryTest {
    private val main = Box(20, 45, 680, 105)
    private fun glyph(x: Int, y: Int = 18) = Component(x, y, x + 14, y + 18, 50)
    private fun plan(components: List<Component>, extra: List<Box> = emptyList()) =
        DialogueRubyGroupRecovery.plan(components, listOf(main) + extra, 700, 219)

    @Test
    fun `recognizes a complete widely spaced group rather than just the detected suffix`() {
        val groups = plan(listOf(glyph(30), glyph(80), glyph(130)), listOf(Box(128, 17, 146, 38)))
        assertEquals(1, groups.size)
        assertEquals(listOf(1), groups.single().originalBoxIndices)
        assertTrue(groups.single().bounds.left < 30)
        assertTrue(groups.single().bounds.right > 144)
        assertTrue(groups.single().expectedBand)
    }

    @Test
    fun `supports the large gap between so and to`() {
        val groups = plan(listOf(glyph(30), glyph(130)))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().componentCount)
    }

    @Test
    fun `complete existing ruby box adds no recognition`() {
        assertTrue(plan(listOf(glyph(30), glyph(80)), listOf(Box(28, 16, 96, 39))).isEmpty())
    }

    @Test
    fun `ordinary dialogue without upper ink adds no recognition`() {
        assertTrue(plan(listOf(glyph(30, 60), glyph(80, 60))).isEmpty())
    }

    @Test
    fun `a band alone cannot classify upper text without a main row`() {
        assertTrue(DialogueRubyGroupRecovery.plan(listOf(glyph(30)), emptyList(), 700, 219).isEmpty())
    }

    @Test
    fun `tall merged ruby and main detector box is not a replacement anchor`() {
        assertTrue(DialogueRubyGroupRecovery.plan(
            listOf(glyph(30)), listOf(Box(20, 10, 680, 110)), 700, 219
        ).isEmpty())
    }

    @Test
    fun `punctuation-only upper dots cannot manufacture a reading`() {
        val dots = (0..5).map { Component(30 + it * 35, 24, 37 + it * 35, 31, 49) }
        assertTrue(plan(dots).isEmpty())
    }

    @Test
    fun `main-band dots and dashes are left to punctuation recovery`() {
        assertTrue(plan(listOf(
            Component(30, 70, 36, 76, 36), Component(80, 72, 160, 75, 240)
        )).isEmpty())
    }

    @Test
    fun `first main row fragments cannot become ruby for the second main row`() {
        val groups = DialogueRubyGroupRecovery.plan(
            listOf(Component(100, 60, 110, 90, 70), Component(150, 61, 170, 92, 100)),
            listOf(main, Box(20, 132, 680, 196)), 700, 219
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `tiny fragmented so to cores form one complete crop in the hinted band`() {
        val components = listOf(
            Component(84, 110, 88, 114, 9), Component(191, 110, 196, 117, 18),
            Component(76, 111, 79, 114, 5), Component(80, 116, 83, 119, 6),
            Component(187, 120, 188, 125, 5), Component(195, 126, 201, 127, 6)
        )
        val groups = DialogueRubyGroupRecovery.plan(components, listOf(Box(20, 132, 680, 196)), 700, 219)
        assertEquals(1, groups.size)
        assertTrue(groups.single().bounds.left <= 76)
        assertTrue(groups.single().bounds.right >= 201)
        assertTrue(groups.single().bounds.bottom >= 127)
    }

    @Test
    fun `image-derived watakushi fragments stay one reading including the small ku stroke`() {
        val components = listOf(
            Component(398, 24, 403, 40, 34), Component(420, 24, 427, 40, 32),
            Component(450, 24, 456, 27, 11), Component(466, 25, 470, 40, 33),
            Component(412, 28, 415, 38, 18), Component(429, 30, 436, 31, 7)
        )
        val groups = plan(components)
        assertEquals(1, groups.size)
        assertTrue(groups.single().bounds.left <= 398)
        assertTrue(groups.single().bounds.right >= 470)
    }

    @Test
    fun `antialiased upper dot row cannot bridge a recovered reading`() {
        val components = (0..5).map { Component(30 + it * 20, 24, 34 + it * 20, 28, 9) }
        assertTrue(plan(components).isEmpty())
    }

    @Test
    fun `includes a trailing long vowel across a large gap`() {
        val groups = plan(listOf(glyph(30), Component(130, 26, 146, 28, 32)))
        assertEquals(1, groups.size)
        assertTrue(groups.single().bounds.right > 146)
    }

    @Test
    fun `includes nearby upper dakuten without creating another group`() {
        val groups = plan(listOf(glyph(30), Component(43, 12, 46, 15, 6), Component(47, 12, 50, 15, 6)))
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().componentCount)
        assertTrue(groups.single().bounds.top < 12)
    }

    @Test
    fun `dense separate readings with an unusually large gap stay separate`() {
        assertEquals(2, plan(listOf(glyph(30), glyph(48), glyph(130), glyph(148))).size)
    }

    @Test
    fun `two independently covered annotations are not joined into one recovery`() {
        val components = listOf(glyph(30), glyph(50), glyph(85), glyph(105))
        assertTrue(plan(components, listOf(Box(28, 16, 66, 39), Box(83, 16, 121, 39))).isEmpty())
    }

    @Test
    fun `a detector box crossing a proposed group is retained without a duplicate`() {
        assertTrue(plan(listOf(glyph(30)), listOf(Box(25, 16, 90, 39))).isEmpty())
    }

    @Test
    fun `supports the second reference band and does not mix rows`() {
        val components = listOf(glyph(30), glyph(80), glyph(30, 110), glyph(80, 110))
        val groups = DialogueRubyGroupRecovery.plan(components, listOf(main, Box(20, 137, 680, 197)), 700, 219)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.expectedBand })
        assertTrue(groups[0].bounds.bottom < groups[1].bounds.top)
    }

    @Test
    fun `geometry still recovers shifted ruby outside the fixed hints`() {
        val groups = DialogueRubyGroupRecovery.plan(
            listOf(glyph(30, 66), glyph(80, 66)), listOf(Box(20, 93, 680, 153)), 700, 219
        )
        assertEquals(1, groups.size)
        assertFalse(groups.single().expectedBand)
    }

    @Test
    fun `groups and fixed band hints scale with the dialogue bitmap`() {
        val parts = listOf(glyph(30), glyph(130))
        val scaledParts = parts.map { Component(it.left * 2, it.top * 2, it.right * 2, it.bottom * 2, it.pixelCount * 4) }
        val groups = DialogueRubyGroupRecovery.plan(
            scaledParts, listOf(Box(main.left * 2, main.top * 2, main.right * 2, main.bottom * 2)), 1400, 438
        )
        val expected = plan(parts).single().bounds
        assertEquals(Box(expected.left * 2, expected.top * 2, expected.right * 2, expected.bottom * 2), groups.single().bounds)
        assertTrue(groups.single().expectedBand)
    }

    @Test
    fun `crops stay inside the exact dialogue region`() {
        val groups = plan(listOf(glyph(0), glyph(30)))
        assertEquals(0, groups.single().bounds.left)
        assertTrue(groups.single().bounds.bottom <= 219)
        assertTrue(groups.single().bounds.right <= 700)
    }

    @Test
    fun `accepts a full reading retaining an existing suffix`() {
        assertNull(DialogueRubyGroupRecovery.rejectionReason(Reading("キャラクター", 0.9f), listOf(Reading("クタ", 0.91f))))
    }

    @Test
    fun `retains fragments in left to right order`() {
        assertNull(DialogueRubyGroupRecovery.rejectionReason(Reading("キャラクター", 0.9f), listOf(Reading("キャ", 0.9f), Reading("クタ", 0.9f))))
        assertEquals("original_fragment_not_retained", DialogueRubyGroupRecovery.rejectionReason(
            Reading("キャラクター", 0.9f), listOf(Reading("クタ", 0.9f), Reading("キャ", 0.9f))
        ))
    }

    @Test
    fun `poor group recognition preserves originals`() {
        assertEquals("low_confidence", DialogueRubyGroupRecovery.rejectionReason(Reading("キャラクター", 0.49f), emptyList()))
        assertEquals("weaker_than_original", DialogueRubyGroupRecovery.rejectionReason(Reading("キャラクター", 0.6f), listOf(Reading("クタ", 0.95f))))
        assertEquals("no_recognition", DialogueRubyGroupRecovery.rejectionReason(null, listOf(Reading("クタ", 0.9f))))
        assertEquals("low_confidence", DialogueRubyGroupRecovery.rejectionReason(Reading("キャ", Float.NaN), emptyList()))
    }

    @Test
    fun `a candidate cannot overwrite a previously accepted character`() {
        assertEquals("original_fragment_not_retained", DialogueRubyGroupRecovery.rejectionReason(
            Reading("キャラクター", 0.9f), listOf(Reading("クテ", 0.9f))
        ))
    }

    @Test
    fun `changed annotation bounds prevent duplicate insertion while main rows do not`() {
        val group = Box(28, 16, 146, 40)
        val original = Box(128, 17, 146, 38)
        assertFalse(DialogueRubyGroupRecovery.hasUnownedOverlap(group, listOf(original), listOf(original, main)))
        assertTrue(DialogueRubyGroupRecovery.hasUnownedOverlap(group, listOf(original), listOf(Box(125, 17, 148, 38))))
        assertFalse(DialogueRubyGroupRecovery.hasUnownedOverlap(group, emptyList(), listOf(Box(30, 35, 680, 95))))
    }

    @Test
    fun `punctuation-only or uncertain readings do not become ruby`() {
        for (text in listOf("……", "───", "・・・", "ー", "ABC", "123", "キャ？", "")) {
            assertNotNull(DialogueRubyGroupRecovery.rejectionReason(Reading(text, 0.99f), emptyList()), text)
        }
    }

    @Test
    fun `geometry is colour independent and the shared palette supplies components`() {
        for (color in listOf(0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(),
            0xFFFF69B4.toInt(), 0xFF8753D1.toInt(), 0xFFB86349.toInt())) {
            val pixels = IntArray(700 * 219) { 0xFF142B48.toInt() }
            // Hollow, connected letter-like outlines; emphasis dots are not these glyphs.
            for (x in listOf(30, 130)) for (y in 18 until 36) for (dx in 0 until 14) {
                if (y == 18 || y == 35 || dx == 0 || dx == 13) pixels[y * 700 + x + dx] = color
            }
            val cleaned = DialogueAnnotationCleaner.clean(pixels, 700, 219)
            assertEquals(1, plan(cleaned.components).size, color.toUInt().toString(16))
        }
    }

    private fun choiceAnalysis(
        offsetY: Int = 0, parts: List<Component> = listOf(glyph(30), glyph(80), glyph(130))
    ): ChoicePunctuationRecovery.ButtonAnalysis {
        fun shifted(box: Box) = ChoicePunctuationRecovery.Bounds(
            box.left, box.top + offsetY, box.right, box.bottom + offsetY
        )
        return ChoicePunctuationRecovery.ButtonAnalysis(
            bounds = shifted(Box(0, 0, 700, 150)),
            interior = shifted(Box(10, 12, 690, 138)),
            components = parts.map { ChoicePunctuationRecovery.Component(
                ChoicePunctuationRecovery.Bounds(it.left, it.top + offsetY, it.right, it.bottom + offsetY), it.pixelCount
            ) },
            mainBand = shifted(main)
        )
    }

    @Test
    fun `choice ruby groups use the button band at any screen Y`() {
        for (offset in listOf(0, 171, 570, 850)) {
            val analysis = choiceAnalysis(offset)
            val boxes = listOf(Box(main.left, main.top + offset, main.right, main.bottom + offset),
                Box(128, 17 + offset, 146, 38 + offset))
            val groups = DialogueRubyGroupRecovery.planChoices(listOf(analysis), boxes, 700, 1100)
            assertEquals(1, groups.size, "offset=$offset")
            assertEquals(listOf(1), groups.single().originalBoxIndices)
            assertTrue(groups.single().bounds.left < 30)
            assertTrue(groups.single().bounds.right > 144)
            assertTrue(groups.single().expectedBand)
        }
    }

    @Test
    fun `six choices keep ruby components and detector indices inside their own button`() {
        val analyses = (0 until 6).map { choiceAnalysis(it * 160) }
        val boxes = (0 until 6).flatMap { index ->
            val offset = index * 160
            listOf(Box(main.left, main.top + offset, main.right, main.bottom + offset),
                Box(128, 17 + offset, 146, 38 + offset))
        }
        val groups = DialogueRubyGroupRecovery.planChoices(analyses, boxes, 700, 960)
        assertEquals(6, groups.size)
        groups.forEachIndexed { index, group ->
            assertEquals(listOf(index * 2 + 1), group.originalBoxIndices)
            assertTrue(group.bounds.top >= analyses[index].interior.top)
            assertTrue(group.bounds.bottom <= analyses[index].interior.bottom)
        }
    }

    @Test
    fun `complete choice ruby and ordinary choices need no supplementary recognition`() {
        val complete = choiceAnalysis()
        assertTrue(DialogueRubyGroupRecovery.planChoices(
            listOf(complete), listOf(main, Box(28, 16, 146, 39)), 700, 150
        ).isEmpty())
        val ordinary = choiceAnalysis(parts = listOf(glyph(30, 60), glyph(80, 60)))
        assertTrue(DialogueRubyGroupRecovery.planChoices(listOf(ordinary), listOf(main), 700, 150).isEmpty())
    }

    @Test
    fun `choice ruby includes wide gaps long vowels and nearby dakuten`() {
        val parts = listOf(glyph(30), glyph(130), Component(230, 26, 246, 28, 32),
            Component(43, 12, 46, 15, 6), Component(47, 12, 50, 15, 6))
        val groups = DialogueRubyGroupRecovery.planChoices(listOf(choiceAnalysis(parts = parts)), listOf(main), 700, 150)
        assertEquals(1, groups.size)
        assertEquals(5, groups.single().componentCount)
        assertTrue(groups.single().bounds.right > 246)
        assertTrue(groups.single().bounds.top <= 12)
    }

    @Test
    fun `choice dots cannot manufacture ruby and missing main evidence cannot anchor it`() {
        val dots = (0..5).map { Component(30 + it * 35, 24, 37 + it * 35, 31, 49) }
        assertTrue(DialogueRubyGroupRecovery.planChoices(
            listOf(choiceAnalysis(parts = dots)), listOf(main), 700, 150
        ).isEmpty())
        assertTrue(DialogueRubyGroupRecovery.planChoices(
            listOf(choiceAnalysis().copy(mainBand = null)), listOf(main), 700, 150
        ).isEmpty())
        assertTrue(DialogueRubyGroupRecovery.planChoices(
            listOf(choiceAnalysis()), listOf(Box(20, 10, 680, 110)), 700, 150
        ).isEmpty())
    }

    @Test
    fun `independently detected choice readings are not recombined`() {
        val analysis = choiceAnalysis(parts = listOf(glyph(30), glyph(50), glyph(85), glyph(105)))
        assertTrue(DialogueRubyGroupRecovery.planChoices(
            listOf(analysis), listOf(main, Box(28, 16, 66, 39), Box(83, 16, 121, 39)), 700, 150
        ).isEmpty())
    }

    @Test
    fun `choice recovery geometry scales with existing engine scaling`() {
        val analysis = choiceAnalysis()
        fun twice(bounds: ChoicePunctuationRecovery.Bounds) = ChoicePunctuationRecovery.Bounds(
            bounds.left * 2, bounds.top * 2, bounds.right * 2, bounds.bottom * 2
        )
        val scaled = analysis.copy(
            bounds = twice(analysis.bounds), interior = twice(analysis.interior),
            components = analysis.components.map { it.copy(bounds = twice(it.bounds), pixelCount = it.pixelCount * 4) },
            mainBand = twice(analysis.mainBand!!)
        )
        val expected = DialogueRubyGroupRecovery.planChoices(listOf(analysis), listOf(main), 700, 150).single().bounds
        val actual = DialogueRubyGroupRecovery.planChoices(
            listOf(scaled), listOf(Box(main.left * 2, main.top * 2, main.right * 2, main.bottom * 2)), 1400, 300
        ).single().bounds
        assertEquals(Box(expected.left * 2, expected.top * 2, expected.right * 2, expected.bottom * 2), actual)
    }
}
