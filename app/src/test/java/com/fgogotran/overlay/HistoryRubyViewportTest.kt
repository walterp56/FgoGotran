package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRubyViewportTest {
    private fun bounds(y: Float, fontSize: Float = 54f) = HistoryRubyViewport.textBounds(
        viewportWidth = 1920, viewportHeight = 1080, textOriginX = 60f, textOriginY = y,
        rubyOverhang = fontSize * 0.50f
    )

    private fun fitRuby(minimumTop: Float, preferredSize: Float = 21.6f, gap: Float = 5.4f) =
        RubyEndAlignment.fit(preferredSize, 900f, -60f, 2f - gap - minimumTop) { size ->
            val ratio = size / 21.6f
            RubyEndAlignment.InkBounds(0f, -18f * ratio, 84f * ratio, 2f * ratio)
        }

    @Test fun `first choice can use existing list top padding without changing body geometry`() {
        val textOriginY = 42f // Existing 14 dp history padding at the reference viewport.
        val viewport = bounds(textOriginY)
        // The old item-local clip started at zero, making the annotation disappear.
        assertNull(fitRuby(minimumTop = 0f))
        val ruby = assertNotNull(fitRuby(viewport.top))
        assertEquals(21.6f, ruby.textSize)
        assertEquals(900f, ruby.drawX + ruby.ink.right)
        val rubyInkTop = textOriginY + 2f - 5.4f - ruby.ink.height
        assertTrue(rubyInkTop >= 0f)
        assertTrue(rubyInkTop < textOriginY)
    }

    @Test fun `consecutive entries use the existing blank gap without covering previous text`() {
        val previousBodyBottom = 200f
        val currentBodyOrigin = previousBodyBottom + 36f // Existing item bottom padding.
        val ruby = assertNotNull(fitRuby(bounds(currentBodyOrigin).top))
        val rubyInkTop = currentBodyOrigin + 2f - 5.4f - ruby.ink.height
        assertTrue(rubyInkTop > previousBodyBottom)
        assertEquals(21.6f, ruby.textSize)
    }

    @Test fun `scrolling clips annotations at the viewport without changing their font budget`() {
        for (bodyY in listOf(42f, 16f, 3f, -8f, 1084f)) {
            val viewport = bounds(bodyY)
            val ruby = assertNotNull(fitRuby(viewport.top))
            assertEquals(21.6f, ruby.textSize)
            // Local coordinates transform back to the same unchanged list viewport.
            assertEquals(1080f, bodyY + viewport.bottom)
            assertEquals(0f, 60f + viewport.left)
            assertEquals(1920f, 60f + viewport.right)
        }
    }

    @Test fun `bilingual original keeps its smaller normal ruby size when space is available`() {
        val viewport = bounds(y = 200f, fontSize = 42f)
        val ruby = assertNotNull(fitRuby(viewport.top, preferredSize = 16.8f, gap = 4.2f))
        assertEquals(16.8f, ruby.textSize)
        assertEquals(900f, ruby.drawX + ruby.ink.right)
    }

    @Test fun `tight bilingual spacing still protects the previous body text`() {
        val previousBodyBottom = -12f
        val ruby = assertNotNull(fitRuby(minimumTop = previousBodyBottom + 1f))
        val rubyInkTop = 2f - 5.4f - ruby.ink.height
        assertTrue(rubyInkTop >= previousBodyBottom + 1f - 0.001f)
        assertTrue(ruby.textSize < 21.6f)
        assertEquals(900f, ruby.drawX + ruby.ink.right)
    }

    @Test fun `choice annotation remains intact after history display parsing`() {
        val choice = "……以上就是，在阿瓦隆的始末〈來龍去脈〉。"
        val parsed = RubyRenderText.parse(choice)
        assertEquals("……以上就是，在阿瓦隆的始末。", parsed.body)
        assertEquals("來龍去脈", parsed.annotations.single().reading)
        assertEquals(choice, parsed.annotate(parsed.body))
    }
}
