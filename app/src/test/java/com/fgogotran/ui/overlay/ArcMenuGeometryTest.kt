package com.fgogotran.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArcMenuGeometryTest {
    @Test fun `new dialogue action is included for every capacity without mixing action groups`() {
        for (capacity in 1..4) {
            val pages = ArcMenuGeometry.pageIndices(capacity, listOf(5, 4))
            assertEquals((0..8).toList(), pages.flatten())
            assertTrue(pages.all { it.size in 1..capacity })
            assertTrue(pages.all { page -> page.all { it < 5 } || page.all { it >= 5 } })
        }
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4), listOf(5, 6, 7, 8)),
            ArcMenuGeometry.pageIndices(4, listOf(5, 4)))
    }

    @Test
    fun `normal phone and high resolution emulator retain four actions per page`() {
        for (bounds in listOf(ArcMenuRect(8f, 8f, 772f, 352f), ArcMenuRect(8f, 8f, 952f, 592f))) {
            for (anchor in anchors(bounds)) {
                val layout = assertNotNull(ArcMenuGeometry.layout(bounds, anchor.first, anchor.second, 64f, 65f, 46f))
                assertEquals(4, layout.capacity)
                assertSafe(bounds, layout)
            }
        }
    }

    @Test
    fun `larger labels reduce capacity without shrinking the pills`() {
        val bounds = ArcMenuRect(8f, 8f, 772f, 312f)
        val normal = assertNotNull(ArcMenuGeometry.layout(bounds, 750f, 160f, 64f, 65f, 46f))
        val large = assertNotNull(ArcMenuGeometry.layout(bounds, 750f, 160f, 112f, 105f, 64f))
        assertEquals(4, normal.capacity)
        assertTrue(large.capacity < normal.capacity)
        large.actions.forEach {
            assertEquals(112f, it.width, EPSILON)
            assertEquals(105f, it.height, EPSILON)
        }
        assertSafe(bounds, large)
    }

    @Test
    fun `all four capacities are available on progressively shorter screens`() {
        val capacities = (120..600 step 4).mapNotNull { height ->
            ArcMenuGeometry.layout(ArcMenuRect(8f, 8f, 772f, height.toFloat()), 750f, 60f, 112f, 105f, 64f)?.capacity
        }.toSet()
        assertEquals(setOf(1, 2, 3, 4), capacities)
    }

    @Test
    fun `short landscape falls back to two points on the arc diameter`() {
        val bounds = ArcMenuRect(8f, 8f, 772f, 118f)
        val layout = assertNotNull(ArcMenuGeometry.layout(bounds, 760f, 100f, 128f, 105f, 64f))
        assertEquals(1, layout.capacity)
        assertSafe(bounds, layout)
    }

    @Test
    fun `geometry stays inside inset bounds at every button edge and outside anchor`() {
        val bounds = ArcMenuRect(40f, 20f, 740f, 320f)
        for (anchor in anchors(bounds) + listOf(-100f to -100f, 1000f to 1000f)) {
            assertSafe(bounds, assertNotNull(ArcMenuGeometry.layout(bounds, anchor.first, anchor.second, 96f, 91f, 60f)))
        }
    }

    @Test
    fun `phone tablet and emulator size matrix has no clipped or overlapping actions`() {
        for (width in listOf(320f, 360f, 640f, 780f, 960f, 1280f)) {
            for (height in listOf(180f, 240f, 320f, 360f, 600f)) {
                val bounds = ArcMenuRect(8f, 8f, width - 8f, height - 8f)
                for ((pillWidth, pillHeight, navSize) in listOf(
                    Triple(64f, 54f, 46f), Triple(80f, 72f, 48f),
                    Triple(112f, 105f, 64f), Triple(144f, 140f, 72f)
                )) {
                    for (anchor in anchors(bounds)) {
                        val layout = assertNotNull(ArcMenuGeometry.layout(bounds, anchor.first, anchor.second,
                            pillWidth, pillHeight, navSize), "$width x $height; $pillWidth x $pillHeight; $anchor")
                        assertSafe(bounds, layout)
                    }
                }
            }
        }
    }

    @Test
    fun `a window smaller than one action is rejected without a clamp exception`() {
        assertNull(ArcMenuGeometry.layout(ArcMenuRect(0f, 0f, 30f, 30f), 0f, 0f, 64f, 54f, 46f))
    }

    @Test
    fun `normal pages preserve the feature and mode groups`() {
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6, 7)), ArcMenuGeometry.pageIndices(4))
    }

    @Test
    fun `two action pages preserve the approved action order`() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6, 7)), ArcMenuGeometry.pageIndices(2))
    }

    @Test
    fun `every capacity includes each action exactly once and never mixes groups`() {
        for (capacity in 1..4) {
            val pages = ArcMenuGeometry.pageIndices(capacity)
            assertEquals((0..7).toList(), pages.flatten())
            assertTrue(pages.all { it.size in 1..capacity })
            assertTrue(pages.all { page -> page.all { it < 4 } || page.all { it >= 4 } })
            // The UI's next-page operation cycles without omitting a page.
            var page = 0
            val visited = mutableListOf<Int>()
            repeat(pages.size) {
                visited += page
                page = (page + 1) % pages.size
            }
            assertEquals(pages.indices.toList(), visited)
            assertEquals(0, page)
        }
    }

    @Test
    fun `invalid capacities cannot silently hide menu actions`() {
        assertFailsWith<IllegalArgumentException> { ArcMenuGeometry.pageIndices(0) }
        assertFailsWith<IllegalArgumentException> { ArcMenuGeometry.pageIndices(5) }
    }

    private fun anchors(bounds: ArcMenuRect): List<Pair<Float, Float>> = listOf(
        bounds.left to bounds.top, bounds.right to bounds.top,
        bounds.left to bounds.bottom, bounds.right to bounds.bottom,
        bounds.left to (bounds.top + bounds.bottom) / 2f,
        bounds.right to (bounds.top + bounds.bottom) / 2f,
        (bounds.left + bounds.right) / 2f to (bounds.top + bounds.bottom) / 2f
    )

    private fun assertSafe(bounds: ArcMenuRect, layout: ArcMenuLayout) {
        assertEquals(layout.capacity, layout.actions.size)
        val rects = layout.actions + layout.navigation
        rects.forEach {
            assertTrue(it.left >= bounds.left - EPSILON && it.right <= bounds.right + EPSILON, "$it outside $bounds")
            assertTrue(it.top >= bounds.top - EPSILON && it.bottom <= bounds.bottom + EPSILON, "$it outside $bounds")
        }
        rects.forEachIndexed { index, first ->
            rects.drop(index + 1).forEach { second ->
                assertTrue(first.right + 4f <= second.left + EPSILON || second.right + 4f <= first.left + EPSILON ||
                    first.bottom + 4f <= second.top + EPSILON || second.bottom + 4f <= first.top + EPSILON,
                    "overlapping or insufficient gap: $first and $second")
            }
        }
    }

    companion object { private const val EPSILON = 0.001f }
}
