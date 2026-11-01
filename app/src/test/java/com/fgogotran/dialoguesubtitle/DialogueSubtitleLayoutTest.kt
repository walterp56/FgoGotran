package com.fgogotran.dialoguesubtitle

import com.fgogotran.battle.BattleLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DialogueSubtitleLayoutTest {
    @Test fun `recording dialogue plate passes but returned voice menu fails`() {
        // RGB samples from the supplied recording at 2s and 28s, x=82/1830 on the centered canvas.
        val active = listOf(0x051a3a, 0x031531, 0x05152f, 0x04142e, 0x091c3c, 0x03152f, 0x0a1a36, 0x061832)
        val menu = listOf(0x43607c, 0xfad0ae, 0x0c6686, 0xb6816b, 0xf9f7f3, 0x3abcfe, 0x8a92aa, 0x707580)
        fun present(colors: List<Int>): Boolean {
            var index = 0
            return DialogueSubtitleLayout.panelPresent(2340, 1080) { _, _ -> colors[index++] }
        }
        assertTrue(present(active))
        assertFalse(present(menu))
        assertFalse(DialogueSubtitleLayout.panelPresent(1080, 2340) { _, _ -> 0x051a3a })
    }

    @Test fun `OCR band is separate from battle and correctly centered on wide screens`() {
        assertNotEquals(BattleLayout.subtitle, DialogueSubtitleLayout.text)
        val bounds = DialogueSubtitleLayout.map(DialogueSubtitleLayout.text, 2340, 1080)
        assertEquals(316, bounds.left)
        assertEquals(833, bounds.top)
        assertEquals(2015, bounds.right)
    }
}
