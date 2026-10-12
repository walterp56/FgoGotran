package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FgoViewportGeometryTest {
    @Test
    fun `reference scale follows the centered game viewport without a size cap`() {
        listOf(
            Triple(1920, 1080, 1f),
            Triple(2340, 1080, 1f),
            Triple(1280, 720, 2f / 3f),
            Triple(1024, 576, 8f / 15f),
            Triple(2560, 1440, 4f / 3f),
            Triple(3840, 2160, 2f),
            Triple(3840, 2400, 2f),
            Triple(7680, 4320, 4f)
        ).forEach { (width, height, expected) ->
            assertEquals(expected, FgoViewportGeometry.referenceScale(width, height), 0.00001f)
        }
    }

    @Test
    fun `invalid dimensions do not produce a negative reference scale`() {
        listOf(0 to 1080, 1920 to 0, -1 to 1080, 1920 to -1).forEach { (width, height) ->
            assertEquals(0f, FgoViewportGeometry.referenceScale(width, height))
        }
    }

    @Test
    fun `existing phone viewport and dialogue reference anchors are unchanged`() {
        assertEquals(FgoReferenceRect(210, 0, 2130, 1080), FgoViewportGeometry.viewport(2340, 1080))
        assertEquals(
            FgoReferenceRect(245, 830, 2015, 1055),
            FgoViewportGeometry.map(FgoReferenceRect(35, 830, 1805, 1055), 2340, 1080)
        )
    }

    @Test
    fun `four K tablet uses two times scale with centered letterboxing`() {
        assertEquals(FgoReferenceRect(0, 120, 3840, 2280), FgoViewportGeometry.viewport(3840, 2400))
        assertEquals(
            FgoReferenceRect(70, 1780, 3610, 2230),
            FgoViewportGeometry.map(FgoReferenceRect(35, 830, 1805, 1055), 3840, 2400)
        )
        assertEquals(
            FgoReferenceRect(0, 1590, 2170, 1776),
            FgoViewportGeometry.map(FgoReferenceRect(0, 735, 1085, 828), 3840, 2400)
        )
    }

    @Test
    fun `one through six reference choice layouts keep their full width at four K`() {
        // Fixtures from the unchanged FgoViewportLayout reference slots (top, bottom).
        val layouts = listOf(
            listOf(343 to 478),
            listOf(250 to 385, 437 to 572),
            listOf(156 to 291, 343 to 478, 531 to 666),
            listOf(36 to 171, 214 to 349, 392 to 527, 571 to 706),
            listOf(14 to 149, 158 to 293, 302 to 437, 447 to 582, 591 to 726),
            listOf(14 to 149, 158 to 293, 302 to 437, 447 to 582, 591 to 726, 735 to 870)
        )
        val scale = FgoViewportGeometry.referenceScale(3840, 2400)
        val expectedWidth = 1470f * scale
        layouts.forEachIndexed { index, slots ->
            assertEquals(index + 1, slots.size)
            slots.forEach { (top, bottom) ->
                val mapped = FgoViewportGeometry.map(FgoReferenceRect(220, top, 1690, bottom), 3840, 2400)
                assertEquals(2940, mapped.width)
                assertEquals(270, mapped.height)
                assertEquals(top * 2 + 120, mapped.top)
                // The renderer's existing width ratios must accept the full-sized mapped slot.
                assertTrue(mapped.width.toFloat() in expectedWidth * 0.78f..expectedWidth * 1.08f)
            }
        }
        // Demonstrates why the former 1.4 cap rejected a legitimate 2940-pixel choice.
        assertTrue(2940f > 1470f * 1.4f * 1.08f)
    }

    @Test
    fun `four K names and expanded plate retain their reference proportions`() {
        val scale = FgoViewportGeometry.referenceScale(3840, 2400)
        assertEquals(112f, NameRenderGeometry.textSize("zh-Hans") * scale)
        assertEquals(112f, NameRenderGeometry.textSize("zh-Hant") * scale)
        assertEquals(128f, NameRenderGeometry.textSize("en") * scale)
        assertEquals(
            1760f,
            NameRenderGeometry.plateRight(
                basePlateRight = 811f * scale,
                textLeft = 52f * scale,
                textWidth = 818f * scale,
                rightInset = 10f * scale,
                maxRight = 3840f
            )
        )
        assertEquals(
            3840f,
            NameRenderGeometry.plateRight(
                basePlateRight = 811f * scale,
                textLeft = 52f * scale,
                textWidth = 3000f * scale,
                rightInset = 10f * scale,
                maxRight = 3840f
            )
        )
    }
}
