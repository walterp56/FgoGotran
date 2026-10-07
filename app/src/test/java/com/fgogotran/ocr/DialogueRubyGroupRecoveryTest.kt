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

    private fun fixed(components: List<Component>, boxes: List<Box> = emptyList()) =
        DialogueRubyGroupRecovery.planFixedDialogue(components, boxes, 700, 219)

    @Test
    fun `fixed bands recognize complete groups even with overlapping merged main boxes`() {
        val groups = fixed(listOf(glyph(30), glyph(130), glyph(30, 110), glyph(130, 110)),
            listOf(Box(20, 25, 680, 114), Box(20, 96, 680, 185)))
        assertEquals(listOf(1, 2), groups.map { it.dialogueRow })
        assertTrue(groups.all { it.componentCount == 2 })
    }

    @Test
    fun `fixed complete detector box is recognized once as a complete band group`() {
        val groups = fixed(listOf(glyph(30), glyph(80)), listOf(Box(28, 16, 96, 39)))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().componentCount)
    }

    @Test
    fun `fixed ownership does not require the detector to find a main row`() {
        assertEquals(2, fixed(listOf(glyph(30, 110))).single().dialogueRow)
    }

    @Test
    fun `independent detected readings in a fixed band stay separate`() {
        val groups = fixed(listOf(glyph(30), glyph(50), glyph(85), glyph(105)),
            listOf(Box(28, 16, 66, 39), Box(83, 16, 121, 39)))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.dialogueRow == 1 })
    }

    @Test
    fun `fixed groups preserve long vowel and detached dakuten beyond the upper band edge`() {
        val groups = fixed(listOf(glyph(30), Component(130, 26, 146, 28, 32),
            Component(43, 12, 46, 15, 6), Component(47, 12, 50, 15, 6)))
        assertEquals(1, groups.size)
        assertEquals(4, groups.single().componentCount)
        assertTrue(groups.single().bounds.top <= 12)
        assertTrue(groups.single().bounds.right >= 146)
    }

    @Test
    fun `fixed main strokes crossing the lower ruby band edge are not ruby`() {
        val mainInk = Component(40, 44, 66, 92, 300)
        val ruby = glyph(30)
        val groups = fixed(listOf(mainInk, ruby))
        assertEquals(listOf(ruby), groups.single().components)
        val mainRows = DialogueRubyGroupRecovery.mainRows(listOf(mainInk, ruby), groups, 700, 219)
        assertEquals(1, mainRows.single().first)
        assertTrue(mainRows.single().second.top < 44)
        assertTrue(mainRows.single().second.bottom > 92)
    }

    @Test
    fun `main ga dakuten at the band edge cannot join the nearby od reading`() {
        // Measured native-pixel components in Screenshot_20261008_012850_FateGO.jpg.
        val mainDakuten = Component(128, 43, 136, 49, 24)
        val ruby = listOf(Component(166, 25, 175, 40, 52), Component(209, 24, 216, 40, 32),
            Component(217, 31, 222, 35, 9))
        val group = fixed(ruby + mainDakuten).single()
        assertEquals(ruby.toSet(), group.components.toSet())
        assertTrue(group.bounds.bottom < 43)
        assertTrue(mainDakuten !in group.components)
    }

    @Test
    fun `fixed tiny so to components retain every stroke across the measured wide gap`() {
        val parts = listOf(Component(84, 110, 88, 114, 9), Component(187, 120, 188, 125, 5),
            Component(191, 110, 196, 117, 18), Component(76, 111, 79, 114, 5),
            Component(80, 116, 83, 119, 6), Component(195, 126, 201, 127, 6))
        val group = fixed(parts).single()
        assertEquals(2, group.dialogueRow)
        assertEquals(parts.toSet(), group.components.toSet())
    }

    @Test
    fun `image-derived Sirius Light strokes cannot become duplicate overlapping groups`() {
        val parts = listOf(Component(637, 37, 645, 40, 9), Component(660, 24, 663, 35, 17),
            Component(669, 24, 673, 38, 25), Component(681, 27, 683, 33, 11),
            Component(687, 24, 691, 28, 10), Component(691, 27, 697, 38, 17),
            Component(710, 26, 717, 37, 26), Component(736, 29, 742, 38, 18),
            Component(754, 24, 763, 41, 32), Component(774, 24, 781, 41, 33),
            Component(639, 24, 643, 27, 7), Component(636, 30, 640, 33, 6),
            Component(782, 32, 786, 35, 7))
        val group = fixed(parts).single()
        assertEquals(1, group.dialogueRow)
        assertEquals(parts.toSet(), group.components.toSet())
    }

    @Test
    fun `fixed first and second bands scale and do not renumber a lone second row`() {
        val parts = listOf(glyph(30, 110), glyph(130, 110))
        val expected = fixed(parts).single()
        val scaled = DialogueRubyGroupRecovery.planFixedDialogue(parts.map {
            Component(it.left * 2, it.top * 2, it.right * 2, it.bottom * 2, it.pixelCount * 4)
        }, emptyList(), 1400, 438).single()
        assertEquals(2, scaled.dialogueRow)
        assertEquals(Box(expected.bounds.left * 2, expected.bounds.top * 2,
            expected.bounds.right * 2, expected.bounds.bottom * 2), scaled.bounds)
        val mainRows = DialogueRubyGroupRecovery.mainRows(listOf(Component(30, 131, 400, 179, 800)),
            emptyList(), 700, 219)
        assertEquals(2, mainRows.single().first)
    }

    @Test
    fun `fixed main-only copy masks selected pixels without changing original or enclosing main ink`() {
        val width = 100
        val pixels = IntArray(width * 219) { 0xFF142B48.toInt() }
        val rubyIndices = (20 until 32).flatMap { y -> (30 until 38).map { x -> y * width + x } }
        rubyIndices.forEach { pixels[it] = -1 }
        // Another component inside the same rectangle must not be erased by rectangle masking.
        val preservedIndex = 25 * width + 34
        val selectedIndices = rubyIndices.filter { it != preservedIndex }
        val selected = Component(30, 20, 38, 32, selectedIndices.size, selectedIndices)
        val group = DialogueRubyGroupRecovery.Group(Box(28, 18, 40, 34), emptyList(), 1, true, 1, listOf(selected))
        val masked = DialogueRubyGroupRecovery.mainPixels(pixels, width, listOf(group))
        assertTrue(selectedIndices.all { masked[it] == 0xFF142B48.toInt() })
        assertEquals(-1, masked[preservedIndex])
        assertTrue(rubyIndices.all { pixels[it] == -1 })
    }

    @Test
    fun `fixed main rows keep leading internal and trailing punctuation and standalone pauses`() {
        val components = listOf(Component(0, 75, 7, 80, 20), Component(20, 45, 300, 92, 600),
            Component(310, 72, 390, 75, 240), Component(500, 75, 530, 79, 100),
            Component(20, 160, 90, 165, 200))
        val rows = DialogueRubyGroupRecovery.mainRows(components, emptyList(), 700, 219)
        assertEquals(listOf(1, 2), rows.map { it.first })
        assertEquals(0, rows.first().second.left)
        assertTrue(rows.first().second.right > 530)
        assertTrue(rows.last().second.left <= 20 && rows.last().second.right >= 90)
    }

    @Test
    fun `fixed geometry keeps five distinct readings in the invocation screenshot layout`() {
        val parts = listOf(glyph(70), glyph(100), glyph(370), glyph(388), glyph(406),
            glyph(30, 110), glyph(48, 110), glyph(230, 110), glyph(260, 110),
            glyph(500, 110), glyph(518, 110), glyph(536, 110), glyph(554, 110))
        val groups = fixed(parts)
        assertEquals(listOf(1, 1, 2, 2, 2), groups.map { it.dialogueRow })
        assertEquals(13, groups.sumOf { it.componentCount })
    }

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
