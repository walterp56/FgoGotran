package com.fgogotran.overlay

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RubyEndAlignmentTest {
    // Synthetic measured ink boxes: layout contracts must hold independently of font/language.
    private val normal = RubyEndAlignment.InkBounds(2f, -16f, 122f, 2f)

    private fun fit(
        endpoint: Float = 500f,
        minimumLeft: Float = 300f,
        maximumHeight: Float = 30f,
        ink: RubyEndAlignment.InkBounds = normal
    ): RubyEndAlignment.Placement = assertNotNull(RubyEndAlignment.fit(20f, endpoint, minimumLeft, maximumHeight) { size ->
        val scale = size / 20f
        RubyEndAlignment.InkBounds(ink.left * scale, ink.top * scale, ink.right * scale, ink.bottom * scale)
    })

    @Test fun `Lostbelt Sirius Light Chinese names and Japanese readings share the fixed endpoint`() {
        listOf("異聞帶〈Lostbelt〉", "大令咒〈Sirius Light〉", "她〈奧爾加瑪麗〉", "私〈わたくし〉").forEach { source ->
            val parsed = RubyRenderText.parse(source)
            assertEquals(parsed.body.length, parsed.annotations.single().end)
            val placement = fit()
            assertEquals(500f, placement.drawX + placement.ink.right, 0.001f, source)
            assertEquals(20f, placement.textSize, source)
        }
    }

    @Test fun `following punctuation never becomes the base endpoint`() {
        val parsed = RubyRenderText.parse("大令咒〈Sirius Light〉。───")
        assertEquals("大令咒".length, parsed.annotations.single().end)
        assertEquals('。', parsed.body[parsed.annotations.single().end])
    }

    @Test fun `existing left margin is used before reducing the normal size`() {
        val placement = fit(endpoint = 450f, minimumLeft = 300f)
        assertEquals(20f, placement.textSize)
        assertTrue(placement.drawX + placement.ink.left < 345f) // Existing body starts at 345.
        assertTrue(placement.drawX + placement.ink.left >= 300f)
        assertEquals(450f, placement.drawX + placement.ink.right)
    }

    @Test fun `long reading shrinks at the left edge without sliding its endpoint`() {
        val placement = fit(endpoint = 350f, minimumLeft = 300f)
        assertTrue(placement.textSize < 20f)
        assertTrue(placement.drawX + placement.ink.left >= 300f - 0.001f)
        assertEquals(350f, placement.drawX + placement.ink.right, 0.001f)
    }

    @Test fun `previous annotation clearance is preserved without moving either endpoint`() {
        val first = fit(endpoint = 450f)
        val second = fit(endpoint = 500f, minimumLeft = 453f)
        assertEquals(450f, first.drawX + first.ink.right)
        assertEquals(500f, second.drawX + second.ink.right)
        assertTrue(second.drawX + second.ink.left >= first.drawX + first.ink.right + 3f - 0.001f)
    }

    @Test fun `glyph side bearings do not shift visible alignment`() {
        listOf(
            RubyEndAlignment.InkBounds(-4f, -16f, 116f, 2f),
            RubyEndAlignment.InkBounds(8f, -16f, 128f, 2f)
        ).forEach { ink ->
            val placement = fit(ink = ink)
            assertEquals(500f, placement.drawX + ink.right, 0.001f)
            assertEquals(20f, placement.textSize)
        }
    }

    @Test fun `limited vertical space reduces only the ruby and keeps its endpoint`() {
        val placement = fit(maximumHeight = 8f)
        assertTrue(placement.ink.height <= 8f)
        assertTrue(placement.textSize < 20f)
        assertEquals(500f, placement.drawX + placement.ink.right, 0.001f)
    }

    @Test fun `rounded glyph metrics are measured again until they fit`() {
        var measurements = 0
        val placement = assertNotNull(RubyEndAlignment.fit(20f, 350f, 300f, 30f) { size ->
            measurements++
            RubyEndAlignment.InkBounds(-1f, -ceil(size * 0.8f), ceil(size * 6f), 1f)
        })
        assertTrue(measurements > 1)
        assertTrue(placement.drawX + placement.ink.left >= 300f)
        assertEquals(350f, placement.drawX + placement.ink.right)
    }

    @Test fun `impossible or invalid areas do not move the anchor or draw clipped text`() {
        for ((endpoint, left, height) in listOf(
            Triple(300f, 300f, 30f), Triple(299f, 300f, 30f), Triple(500f, 300f, 0f),
            Triple(Float.NaN, 300f, 30f), Triple(500f, 300f, Float.POSITIVE_INFINITY)
        )) {
            assertNull(RubyEndAlignment.fit(20f, endpoint, left, height) { normal })
        }
    }
}
