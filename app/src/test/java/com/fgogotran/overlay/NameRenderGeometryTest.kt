package com.fgogotran.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class NameRenderGeometryTest {
    @Test
    fun `name text size is fixed per target`() {
        assertEquals(56f, NameRenderGeometry.textSize("zh-Hans"))
        assertEquals(56f, NameRenderGeometry.textSize("zh-Hant"))
        assertEquals(64f, NameRenderGeometry.textSize("en"))
    }

    @Test
    fun `plate keeps the detected width when the fixed name fits`() {
        assertEquals(
            1021f,
            NameRenderGeometry.plateRight(
                basePlateRight = 1021f,
                textLeft = 262f,
                textWidth = 700f,
                rightInset = 10f,
                maxRight = 2340f
            )
        )
    }

    @Test
    fun `plate expands to the fixed-size name when needed`() {
        assertEquals(
            1090f,
            NameRenderGeometry.plateRight(
                basePlateRight = 1021f,
                textLeft = 262f,
                textWidth = 818f,
                rightInset = 10f,
                maxRight = 2340f
            )
        )
    }

    @Test
    fun `plate never exceeds the canvas`() {
        assertEquals(
            2340f,
            NameRenderGeometry.plateRight(
                basePlateRight = 1021f,
                textLeft = 262f,
                textWidth = 3000f,
                rightInset = 10f,
                maxRight = 2340f
            )
        )
    }
}
