package com.fgogotran.dialoguesubtitle

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialogueSubtitleRenderSizingTest {
    private val ink = DialogueSubtitleLineMetrics(0f, 400f, -45, 11, 0)

    @Test fun `transparent window and both row plates stay above OCR at supported scales`() {
        for ((width, height, textStart) in listOf(Triple(1920, 1080, 135f), Triple(2340, 1080, 345f),
            Triple(1280, 720, 90f), Triple(2560, 1440, 180f))) {
            val geometry = DialogueSubtitleStyle.geometry(width, height)
            val source = DialogueSubtitleLayout.map(DialogueSubtitleLayout.text, width, height)
            val scale = height / 1080f
            val scaledInk = ink.copy(right = 400f * scale, ascent = -ceil(45f * scale).toInt(),
                descent = ceil(11f * scale).toInt())
            val top = DialogueSubtitleStyle.rowPlate(geometry, 0, scaledInk, true)!!
            val bottom = DialogueSubtitleStyle.rowPlate(geometry, 1, scaledInk, true)!!
            assertTrue(geometry.bounds.top >= 0 && geometry.bounds.bottom < source.top)
            assertEquals(textStart, geometry.bounds.left + geometry.textOriginX)
            assertEquals(source.right, geometry.bounds.right)
            assertTrue(top.bottom < bottom.top)
            assertTrue(top.right < geometry.bounds.width)
            assertTrue(bottom.bottom <= geometry.bounds.height)
        }
    }

    @Test fun `background width and height follow text ink rather than the window`() {
        val geometry = DialogueSubtitleStyle.geometry(2340, 1080)
        val short = DialogueSubtitleStyle.rowPlate(geometry, 0, ink.copy(right = 100f), true)!!
        val long = DialogueSubtitleStyle.rowPlate(geometry, 0, ink.copy(right = 1_000f), true)!!
        assertEquals(900f, (long.right - long.left) - (short.right - short.left))
        assertTrue(short.bottom - short.top < geometry.bounds.height / 2)
        val smaller = DialogueSubtitleStyle.rowPlate(geometry, 0, ink.copy(ascent = -24, descent = 8), true)!!
        assertTrue(smaller.bottom - smaller.top < short.bottom - short.top)
    }

    @Test fun `carrying the same row upward preserves its plate size and changes only its anchor`() {
        val geometry = DialogueSubtitleStyle.geometry(2340, 1080)
        val top = DialogueSubtitleStyle.rowPlate(geometry, 0, ink, true)!!
        val bottom = DialogueSubtitleStyle.rowPlate(geometry, 1, ink, true)!!
        assertEquals(331, geometry.bounds.left)
        assertEquals(709, geometry.bounds.top + geometry.baseline(0))
        assertEquals(795, geometry.bounds.top + geometry.baseline(1))
        assertEquals(86, geometry.rowPitch)
        assertEquals(top.left, bottom.left)
        assertEquals(top.right, bottom.right)
        assertEquals(geometry.rowPitch.toFloat(), bottom.top - top.top)
        assertEquals(geometry.rowPitch.toFloat(), bottom.bottom - top.bottom)
    }

    @Test fun `fitting rejects wrapped clipped overflowing and overlapping row plates`() {
        val geometry = DialogueSubtitleStyle.geometry(2340, 1080)
        assertNull(DialogueSubtitleStyle.rowPlate(geometry, 0, ink, false))
        assertNull(DialogueSubtitleStyle.rowPlate(geometry, 2, ink, true))
        assertNull(DialogueSubtitleStyle.rowPlate(geometry, 0, ink.copy(right = geometry.bounds.width.toFloat()), true))
        assertNull(DialogueSubtitleStyle.rowPlate(geometry, 0, ink.copy(ascent = -70), true))
        assertNull(DialogueSubtitleStyle.rowPlate(geometry, 1, ink.copy(descent = 40), true))
        assertNull(DialogueSubtitleStyle.rowPlate(geometry, 0, ink.copy(baselineOffset = 80), true))
    }

    @Test fun `largest full text fit only shrinks when needed`() {
        assertEquals(52f, DialogueSubtitleStyle.fitTextSize(52f) { it <= 52f })
        val fitted = DialogueSubtitleStyle.fitTextSize(52f) { it <= 32f }!!
        assertTrue(fitted <= 32f && fitted > 31.99f)
        assertNull(DialogueSubtitleStyle.fitTextSize(52f) { false })
        assertNull(DialogueSubtitleStyle.fitTextSize(0f) { true })
    }

    @Test fun `a failed subpixel measurement does not hide an ordinary fitting caption`() {
        val tried = mutableListOf<Float>()
        val fitted = DialogueSubtitleStyle.fitTextSize(52f) {
            tried += it
            // Real font measurements may be empty below a pixel, but a readable size fits.
            it >= 1f && it <= 32f
        }!!
        assertTrue(fitted <= 32f && fitted > 31.99f)
        assertTrue(tried.all { it >= 1f })
        assertTrue(tried.size <= 22)
    }

    @Test fun `no drawable size returns failure after bounded measurement work`() {
        var calls = 0
        assertNull(DialogueSubtitleStyle.fitTextSize(52f) { calls++; false })
        assertTrue(calls <= 8)
    }

    @Test fun `normal Traditional caption ink fits without reserving the fonts tallest unused glyph`() {
        val geometry = DialogueSubtitleStyle.geometry(2340, 1080)
        // Bundled Traditional font global bbox at 52 px is about -106..29; ordinary
        // three-character CJK caption ink is about -44..5. Device QA checks actual metrics.
        val actualInk = DialogueSubtitleLineMetrics(0f, 156f, -44, 5, 0)
        for (slot in 0..1) {
            assertTrue(DialogueSubtitleStyle.rowPlate(geometry, slot, actualInk, true) != null)
            assertNull(DialogueSubtitleStyle.rowPlate(geometry, slot, actualInk.copy(ascent = -106, descent = 29), true))
        }
    }

    @Test fun `short captions keep the larger preferred size with actual caption ink bounds`() {
        // Conservative actual-ink fixtures from the bundled fonts; Android device QA remains separate.
        for ((width, height) in listOf(1920 to 1080, 2340 to 1080, 1280 to 720, 2560 to 1440)) {
            val geometry = DialogueSubtitleStyle.geometry(width, height)
            val preferred = DialogueSubtitleStyle.textSizePx(width, height)
            assertEquals(52f * height / 1080f, preferred, 0.001f)
            for ((text, ratio) in listOf("穿過耀眼的隧道、" to 1f,
                "Beyond the bright tunnel lies an unfamiliar laboratory." to 0.55f)) {
                fun plates(size: Float): List<DialogueSubtitlePlate?> {
                    val measuredWidth = text.length * size * ratio
                    val rowInk = DialogueSubtitleLineMetrics(0f, measuredWidth,
                        -ceil(0.90f * size).toInt(), ceil(0.24f * size).toInt(), 0)
                    return (0..1).map { DialogueSubtitleStyle.rowPlate(geometry, it, rowInk,
                        measuredWidth <= geometry.contentWidth) }
                }
                assertEquals(preferred, DialogueSubtitleStyle.fitTextSize(preferred) { size ->
                    plates(size).all { it != null }
                })
                val (top, bottom) = plates(preferred).map { it!! }
                assertTrue(top.bottom < bottom.top)
            }
        }
    }

    @Test fun `long Chinese and English rows retain every character in one corresponding slot`() {
        // Deterministic callbacks verify fitting policy; Android fallback fonts need device QA.
        val geometry = DialogueSubtitleStyle.geometry(2340, 1080)
        for ((text, ratio) in listOf("這是一段完整的角色台詞，保留所有文字。".repeat(4) to 1f,
            "This complete English sentence must retain every word. ".repeat(5) to 0.55f)) {
            fun fits(size: Float): Boolean {
                val width = text.length * size * ratio
                val rowInk = DialogueSubtitleLineMetrics(0f, width, -ceil(size).toInt(), ceil(size / 4).toInt(), 0)
                return (0..1).all { DialogueSubtitleStyle.rowPlate(geometry, it, rowInk,
                    width <= geometry.contentWidth) != null }
            }
            val preferred = DialogueSubtitleStyle.textSizePx(2340, 1080)
            val size = DialogueSubtitleStyle.fitTextSize(preferred, ::fits)!!
            assertTrue(size < preferred && fits(size))
        }
    }

    @Test fun `unusually tall actual ink still shrinks to keep both row backgrounds separate`() {
        val geometry = DialogueSubtitleStyle.geometry(2340, 1080)
        fun plates(size: Float) = (0..1).map { slot ->
            DialogueSubtitleStyle.rowPlate(geometry, slot, DialogueSubtitleLineMetrics(
                0f, 5 * size, -ceil(size * 1.3f).toInt(), ceil(size * 0.3f).toInt(), 0), true)
        }
        assertTrue(plates(52f).all { it == null })
        val size = DialogueSubtitleStyle.fitTextSize(52f) { plates(it).all { plate -> plate != null } }!!
        assertTrue(size < 52f)
        val (upper, lower) = plates(size).map { it!! }
        assertTrue(upper.bottom < lower.top)
        assertTrue(lower.bottom <= geometry.bounds.height)
    }
}
