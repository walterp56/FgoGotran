package com.fgogotran.overlay

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProportionalDialogueFitTest {
    @Test fun `base ruby original and spacing use a single common factor`() {
        val sizes = ProportionalDialogueFit.sizes(50f, 31f, 2f, 0.8f)
        assertEquals(40f, sizes.body)
        assertEquals(16f, sizes.ruby)
        assertEquals(24.8f, sizes.original, 0.001f)
        assertEquals(1.6f, sizes.drawingScale)
    }

    @Test fun `preferred size is retained when the whole annotated block fits`() {
        val fit = assertNotNull(ProportionalDialogueFit.largest(53f, 31f, 1f) { it })
        assertEquals(53f, fit.sizes.body)
        assertEquals(21.2f, fit.sizes.ruby)
        assertEquals(31f, fit.sizes.original)
    }

    @Test fun `long complete text fits two rows below the former body and original limits`() {
        val source = "台詞".repeat(160)
        val fit = assertNotNull(ProportionalDialogueFit.largest(53f, 31f, 1f) { sizes ->
            val rows = ceil(source.length * sizes.body * 0.55f / 1700f).toInt()
            rows.takeIf { it <= 2 }
        })
        assertEquals(2, fit.value)
        assertTrue(fit.sizes.body < 22f)
        assertTrue(fit.sizes.original < 14f)
        assertEquals(0.4f, fit.sizes.ruby / fit.sizes.body, 0.00001f)
        assertEquals(31f / 53f, fit.sizes.original / fit.sizes.body, 0.00001f)
    }

    @Test fun `a wide reading reduces the body as well instead of shrinking ruby alone`() {
        val fit = assertNotNull(ProportionalDialogueFit.largest(50f, 31f, 1f) { sizes ->
            // Existing panel margin stays fixed; a one-word base and its longer reading scale together.
            RubyEndAlignment.exact(sizes.ruby, 25f + sizes.body, 0f, 100f,
                RubyEndAlignment.InkBounds(0f, -sizes.ruby * 0.8f, sizes.ruby * 5f, sizes.ruby * 0.2f))
        })
        assertTrue(fit.sizes.body <= 25f)
        assertTrue(fit.sizes.body > 24f)
        assertEquals(fit.sizes.body * 0.4f, fit.value.textSize, 0.00001f)
    }

    @Test fun `one unfitted annotation rejects the entire size and every reading survives the retry`() {
        val readings = listOf(3f, 8f)
        val fit = assertNotNull(ProportionalDialogueFit.largest(53f, 31f, 1f) { sizes ->
            val plans = readings.map { width ->
                RubyEndAlignment.exact(sizes.ruby, 100f, 0f, 20f,
                    RubyEndAlignment.InkBounds(0f, -sizes.ruby * 0.8f, sizes.ruby * width, sizes.ruby * 0.2f))
            }
            plans.takeIf { it.all { plan -> plan != null } }
        })
        assertTrue(fit.sizes.body < 53f)
        assertEquals(readings.size, fit.value.size)
        fit.value.forEach { assertEquals(fit.sizes.ruby, assertNotNull(it).textSize) }
    }

    @Test fun `vertical fit includes scaled ruby and row spacing for both translated rows`() {
        val fit = assertNotNull(ProportionalDialogueFit.largest(50f, 31f, 1f) { sizes ->
            val total = 2f * (sizes.body + sizes.ruby + 5f * sizes.drawingScale)
            total.takeIf { it <= 120f }
        })
        assertTrue(fit.value <= 120f)
        assertTrue(fit.sizes.body in 39.5f..40f)
        assertEquals(fit.sizes.body * 0.4f, fit.sizes.ruby)
    }

    @Test fun `two tall monolingual rows reserve a full reading above the second row`() {
        val fit = assertNotNull(ProportionalDialogueFit.largest(53f, viewportScale = 1f) { sizes ->
            val bodyHeight = sizes.body * 1.13f
            val readingHeight = sizes.ruby * 1.1f
            val gap = sizes.body * 0.1f + sizes.drawingScale
            val pitch = maxOf(sizes.body * 1.57f, bodyHeight + readingHeight + gap)
            val total = bodyHeight + pitch
            total.takeIf { it <= 130f && pitch - bodyHeight >= readingHeight + gap - 0.001f }
        })
        assertTrue(fit.sizes.body < 53f)
        assertTrue(fit.value <= 130f)
        assertEquals(fit.sizes.body * 0.4f, fit.sizes.ruby)
    }

    @Test fun `exact ruby placement rejects overflow and never changes its requested size`() {
        val ink = RubyEndAlignment.InkBounds(-2f, -16f, 98f, 4f)
        assertNull(RubyEndAlignment.exact(20f, 80f, 0f, 20f, ink))
        assertNull(RubyEndAlignment.exact(20f, 150f, 0f, 10f, ink))
        val plan = assertNotNull(RubyEndAlignment.exact(20f, 150f, 0f, 20f, ink))
        assertEquals(20f, plan.textSize)
        assertEquals(150f, plan.drawX + plan.ink.right)
    }

    @Test fun `impossible geometry never reports a partially fitted block as success`() {
        assertNull(ProportionalDialogueFit.largest<Unit>(53f, 31f, 1f) { null })
        assertNull(ProportionalDialogueFit.largest<Unit>(Float.NaN, 31f, 1f) { error("invalid size") })
        assertNull(RubyEndAlignment.exact(20f, 100f, 0f, 20f,
            RubyEndAlignment.InkBounds(0f, -10f, Float.NaN, 0f)))
    }
}
