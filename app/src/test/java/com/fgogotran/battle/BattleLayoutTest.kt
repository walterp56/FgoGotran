package com.fgogotran.battle

import com.fgogotran.overlay.FgoReferenceRect
import com.fgogotran.overlay.FgoViewportGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleLayoutTest {
    @Test fun `reference screen uses exact coordinates`() {
        assertEquals(FgoReferenceRect(0, 0, 1920, 1080), FgoViewportGeometry.viewport(1920, 1080))
        assertEquals(BattleLayout.subtitle, BattleLayout.map(BattleLayout.subtitle, 1920, 1080))
    }

    @Test fun `wide phones use centered reference with extended side artwork`() {
        assertEquals(FgoReferenceRect(210, 0, 2130, 1080), FgoViewportGeometry.viewport(2340, 1080))
        assertEquals(FgoReferenceRect(310, 688, 2030, 866), BattleLayout.map(BattleLayout.subtitle, 2340, 1080))
    }

    @Test fun `source recording crop matches observed subtitle position`() {
        val subtitle = BattleLayout.map(BattleLayout.subtitle, 1920, 888)
        assertEquals(FgoReferenceRect(253, 566, 1667, 712), subtitle)
        // The recording has 96-pixel top/bottom bars outside the actual game image.
        assertTrue(690 - 96 in subtitle.top..subtitle.bottom)
        assertTrue(785 - 96 in subtitle.top..subtitle.bottom)
        val viewport = FgoViewportGeometry.viewport(1920, 888)
        assertTrue(subtitle.left >= viewport.left && subtitle.right <= viewport.right)
        assertTrue(subtitle.top >= viewport.top && subtitle.bottom <= viewport.bottom)
    }

    @Test fun `narrower landscape displays letterbox the same canvas`() {
        assertEquals(FgoReferenceRect(0, 75, 1600, 975), FgoViewportGeometry.viewport(1600, 1050))
        val rect = BattleLayout.map(BattleLayout.subtitle, 1600, 1050)
        assertTrue(rect.top > 75 && rect.bottom < 975)
    }

    @Test fun `battle caption typography follows the centered FGO viewport`() {
        assertTextSize(44f, 1920, 1080)
        assertTextSize(44f * 2f / 3f, 1280, 720)
        assertTextSize(44f * 4f / 3f, 2560, 1440)
        assertTextSize(44f, 2340, 1080)
    }

    @Test fun `battle caption reflows visual line breaks without changing punctuation`() {
        assertEquals(
            "「那麼，基列萊特。這樣我們彼此都痛快了吧。我才不會說什麼互相廝殺呢。因為是我單方面地把你殺掉啊！」",
            BattleSubtitleStyle.displayText(
                "「那麼，基列萊特。這樣我們彼此都痛快了吧。\n" +
                    "我才不會說什麼互相廝殺呢。因為是我單方面地把你\n殺掉啊！」"
            )
        )
        assertEquals("Fate Grand Order", BattleSubtitleStyle.displayText(" Fate \r\n Grand Order "))
        assertEquals("第一句。第二句！", BattleSubtitleStyle.displayText("第一句。\n\n第二句！"))
    }

    @Test fun `battle caption width grows before wrapping at the safe maximum`() {
        assertEquals(423, BattleSubtitleStyle.captionWidthPx(400.1f, 11, 1720))
        assertEquals(1720, BattleSubtitleStyle.captionWidthPx(1900f, 12, 1720))
        assertEquals(1, BattleSubtitleStyle.captionWidthPx(0f, 0, 1720))
        assertEquals(0, BattleSubtitleStyle.captionWidthPx(400f, 12, 0))
    }

    private fun assertTextSize(expected: Float, width: Int, height: Int) {
        assertTrue(
            abs(BattleSubtitleStyle.textSizePx(width, height) - expected) < 0.01f,
            "Expected ${expected}px for ${width}x$height"
        )
    }
}
