package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BilingualDialogueGeometryTest {
    private val english = BilingualDialogueGeometry.Metrics(
        translation = BilingualDialogueGeometry.TextMetrics(38f, 0f, 49f),
        original = BilingualDialogueGeometry.TextMetrics(27f, 0f, 31f),
        rubyHeight = 19f, rubyGap = 3f, translationToOriginalGap = 4f, beforeRubyGap = 2f
    )
    private val chinese = BilingualDialogueGeometry.Metrics(
        translation = BilingualDialogueGeometry.TextMetrics(46.64f, 3.64f, 53.64f),
        original = BilingualDialogueGeometry.TextMetrics(27.28f, 0.28f, 31.28f),
        rubyHeight = 24f, rubyGap = 3f, translationToOriginalGap = 4f, beforeRubyGap = 2f
    )

    @Test fun `render band expands within blue panel and leaves name and controls clear`() {
        val legacy = DialogueRect(245f, 830f, 2015f, 1055f)
        val plane = BilingualDialogueGeometry.renderPlane(legacy, 1f)
        assertTrue(plane.top < legacy.top)
        assertTrue(plane.bottom > legacy.bottom)
        assertTrue(plane.top >= 828f) // Existing speaker-name band's bottom.
        assertTrue(plane.bottom < 1060f) // Measured blue dialogue-panel bottom edge.
        assertEquals(legacy.left, plane.left)
        assertEquals(legacy.right, plane.right) // Right-side control strip stays outside.
    }

    @Test fun `first row keeps its normal baseline when reserved headroom already fits`() {
        val layout = BilingualDialogueGeometry.layout(1, 1, english, -26f)
        assertEquals(38f, layout.rows.first().baseline)
        assertEquals(49f + 4f + 27f, layout.rows.last().baseline, 0.001f)
    }

    @Test fun `plain and annotated scenes share every baseline and reserved gap in both targets`() {
        val plain = "The Great Command Spell.\nI watched her vanish."
        val annotated = "The Great Command Spell〈Sirius Light〉.\nI watched her〈Olga Marie〉 vanish."
        assertEquals(plain, RubyRenderText.body(annotated))
        for (metrics in listOf(english, chinese)) {
            val withoutRuby = BilingualDialogueGeometry.layout(plain.lines().size, 2, metrics, -26f)
            val withRuby = BilingualDialogueGeometry.layout(RubyRenderText.body(annotated).lines().size, 2, metrics, -26f)
            assertEquals(withoutRuby, withRuby)
            assertTrue(withoutRuby.rows.filterNot { it.original }.all {
                it.inkTop - metrics.rubyGap - metrics.rubyHeight >= it.minimumRubyTop - 0.001f
            })
        }
    }

    @Test fun `second English ruby keeps normal size without overlapping the original row`() {
        val layout = BilingualDialogueGeometry.layout(2, 2, english, -26f)
        val secondTranslation = layout.rows[2]
        val previousOriginal = layout.rows[1]
        assertEquals(previousOriginal.inkBottom + english.beforeRubyGap, secondTranslation.minimumRubyTop, 0.001f)
        val placement = assertNotNull(RubyEndAlignment.fit(21.2f, 900f, 0f,
            secondTranslation.inkTop - english.rubyGap - secondTranslation.minimumRubyTop) { size ->
            val ratio = size / 21.2f
            RubyEndAlignment.InkBounds(0f, -14f * ratio, 105f * ratio, 4f * ratio)
        })
        assertEquals(21.2f, placement.textSize)
        assertTrue(layout.bottom <= 203f) // 1057 panel bottom minus the existing 854 anchor.
    }

    @Test fun `compact Chinese four-row layout fits the preferred size including every ruby slot`() {
        val fitted = assertNotNull(PreciseTextSizeSearch.largestFitting(24f, 53f, 0.25f) { size ->
            BilingualDialogueGeometry.layout(2, 2, chinese.scaled(size / 53f), -26f).takeIf { it.bottom <= 203f }
        })
        assertEquals(53f, fitted.textSize)
        assertClearance(fitted.value, chinese)
    }

    @Test fun `height fitting still includes ruby reservations when the available panel is tight`() {
        val fitted = assertNotNull(PreciseTextSizeSearch.largestFitting(24f, 53f, 0.25f) { size ->
            val layout = BilingualDialogueGeometry.layout(2, 2, chinese.scaled(size / 53f), -26f)
            layout.takeIf { it.bottom <= 190f }
        })
        assertTrue(fitted.textSize < 53f)
        assertTrue(fitted.textSize > 45f)
        assertTrue(fitted.value.bottom <= 190f)
        assertClearance(fitted.value, chinese.scaled(fitted.textSize / 53f))
    }

    @Test fun `three-row scenes use only the compact ink gaps and full ruby slot`() {
        for (metrics in listOf(english, chinese)) {
            val layout = BilingualDialogueGeometry.layout(2, 1, metrics, -26f)
            val firstTranslation = layout.rows[0]
            val original = layout.rows[1]
            val nextTranslation = layout.rows[2]
            assertEquals(4f, original.inkTop - firstTranslation.inkBottom, 0.001f)
            assertEquals(metrics.rubyHeight + 5f, nextTranslation.inkTop - original.inkBottom, 0.001f)
            assertTrue(layout.bottom < 180f)
        }
    }

    @Test fun `a visible Latin descender keeps its real body extent and compact source gap`() {
        val metrics = chinese.copy(translation = chinese.translation.copy(inkBottom = 64f))
        val layout = BilingualDialogueGeometry.layout(2, 2, metrics, -26f)
        assertEquals(4f, layout.rows[1].inkTop - layout.rows[0].inkBottom, 0.001f)
        assertClearance(layout, metrics)
    }

    @Test fun `one or two translated and original lines retain order and leave every row clear`() {
        for (metrics in listOf(english, chinese)) {
            for (translations in 1..2) for (originals in 1..2) {
                val layout = BilingualDialogueGeometry.layout(translations, originals, metrics, -26f)
                assertEquals(translations + originals, layout.rows.size)
                assertEquals((0 until translations).toList(), layout.rows.filterNot { it.original }.map { it.lineIndex })
                assertEquals((0 until originals).toList(), layout.rows.filter { it.original }.map { it.lineIndex })
                assertClearance(layout, metrics)
                assertEquals(layout.rows.last().inkBottom, layout.bottom)
            }
        }
    }

    @Test fun `insufficient first-line headroom moves all scenes to the same safe baseline`() {
        val layout = BilingualDialogueGeometry.layout(1, 1, english, -10f)
        assertTrue(layout.rows.first().baseline > english.translation.baseline)
        assertClearance(layout, english)
    }

    @Test fun `phone tablet and letterboxed viewport mappings keep the expanded band in the panel`() {
        val reference = BilingualDialogueGeometry.renderPlane(DialogueRect(35f, 830f, 1805f, 1055f), 1f)
        for ((width, height) in listOf(2340 to 1080, 1280 to 720, 3840 to 2160, 3840 to 2400)) {
            val panel = FgoViewportGeometry.map(FgoReferenceRect(35, 798, 1805, 1060), width, height)
            val band = FgoViewportGeometry.map(FgoReferenceRect(reference.left.toInt(), reference.top.toInt(),
                reference.right.toInt(), reference.bottom.toInt()), width, height)
            assertTrue(band.top > panel.top)
            assertTrue(band.bottom < panel.bottom)
            assertEquals(panel.left, band.left)
            assertEquals(panel.right, band.right)
        }
    }

    @Test fun `row and ruby reservations scale with the game viewport`() {
        val phone = BilingualDialogueGeometry.layout(2, 2, english, -26f)
        for (scale in listOf(2f / 3f, 2f, 4f)) {
            val scaled = BilingualDialogueGeometry.layout(2, 2, english.scaled(scale), -26f * scale)
            phone.rows.zip(scaled.rows).forEach { (a, b) ->
                assertEquals(a.baseline * scale, b.baseline, 0.001f)
                assertEquals(a.minimumRubyTop * scale, b.minimumRubyTop, 0.001f)
            }
            assertEquals(phone.bottom * scale, scaled.bottom, 0.001f)
        }
    }

    private fun assertClearance(layout: BilingualDialogueGeometry.Layout, metrics: BilingualDialogueGeometry.Metrics) {
        layout.rows.zipWithNext().forEach { (previous, row) ->
            val top = row.inkTop - if (row.original) 0f else metrics.rubyHeight + metrics.rubyGap
            val gap = if (row.original) metrics.translationToOriginalGap else metrics.beforeRubyGap
            assertEquals(previous.inkBottom + gap, top, 0.001f)
        }
    }

    private fun BilingualDialogueGeometry.Metrics.scaled(scale: Float): BilingualDialogueGeometry.Metrics {
        fun BilingualDialogueGeometry.TextMetrics.scaled() = copy(baseline = baseline * scale,
            inkTop = inkTop * scale, inkBottom = inkBottom * scale)
        return copy(translation = translation.scaled(), original = original.scaled(), rubyHeight = rubyHeight * scale,
            rubyGap = rubyGap * scale, translationToOriginalGap = translationToOriginalGap * scale,
            beforeRubyGap = beforeRubyGap * scale)
    }
}
