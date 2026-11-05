package com.fgogotran.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MlKitSolidMaskRecoveryTest {
    @Test
    fun rebuildsSplitLineFromVisualPositionsAndExactGeometry() {
        val row = maskedNameRow()
        val lines = listOf(
            line(
                text = "あの「",
                left = 4,
                right = 48,
                fragments = listOf(
                    fragment("あ", 5, 15),
                    fragment("の", 18, 28),
                    fragment("「", 39, 47)
                )
            ),
            line(
                text = "■■が。",
                left = 170,
                right = 215,
                fragments = listOf(
                    fragment("■", 170, 180),
                    fragment("■", 182, 192),
                    fragment("が", 194, 204),
                    fragment("。", 205, 215)
                )
            )
        )

        val result = MlKitSolidMaskRecovery.recover(lines, listOf(row))

        assertEquals(1, result.lines.size)
        assertEquals("あの■■■■■・■・■■■■が。", result.lines.single().text)
        assertEquals(1, result.changes.size)
        assertEquals(10, result.changes.single().recoveredMaskCount)
        assertEquals(2, result.changes.single().recoveredSeparatorCount)
    }

    @Test
    fun doesNotDuplicateAnAlreadyCorrectGeometrySequence() {
        val expected = "あの■■■■■・■・■■■■が。"
        val original = line(
            text = expected,
            left = 4,
            right = 215,
            fragments = listOf(fragment(expected, 4, 215))
        )

        val result = MlKitSolidMaskRecovery.recover(listOf(original), listOf(maskedNameRow()))

        assertEquals(listOf(original), result.lines)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun leavesUnrelatedTextUnchanged() {
        val original = line(
            text = "数多の分野、人類史における知性の光が。",
            left = 4,
            right = 250,
            top = 60,
            bottom = 76,
            fragments = listOf(fragment("数多の分野、人類史における知性の光が。", 4, 250, 60, 76))
        )

        val result = MlKitSolidMaskRecovery.recover(listOf(original), listOf(maskedNameRow()))

        assertEquals(listOf(original), result.lines)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun detectorFindsTheFixedTenSquaresAndTwoSeparators() {
        val width = 180
        val height = 40
        val pixels = IntArray(width * height) { BLACK }
        listOf(10, 22, 34, 46, 58, 86, 114, 126, 138, 150).forEach { left ->
            fillRect(pixels, width, left, 12, left + 10, 22, WHITE)
        }
        fillRect(pixels, width, 75, 15, 79, 19, WHITE)
        fillRect(pixels, width, 103, 15, 107, 19, WHITE)

        val rows = PaddleSolidMaskDetector.detectRows(pixels, width, height)

        assertEquals(1, rows.size)
        assertEquals(10, rows.single().masks.size)
        assertEquals(2, rows.single().separators.size)
    }

    private fun maskedNameRow(): PaddleSolidMaskRow {
        val masks = listOf(40, 52, 64, 76, 88, 115, 142, 154, 166, 178).map { left ->
            PaddleSolidMask(left, 10, left + 10, 20, 0.98f)
        }
        return PaddleSolidMaskRow(
            masks = masks,
            separators = listOf(
                PaddleSolidMaskSeparator(104, 14, 108, 18, 0.95f),
                PaddleSolidMaskSeparator(131, 14, 135, 18, 0.95f)
            )
        )
    }

    private fun line(
        text: String,
        left: Int,
        right: Int,
        top: Int = 8,
        bottom: Int = 24,
        fragments: List<MlKitTextFragment>
    ): MlKitDetectedLine {
        return MlKitDetectedLine(text, left, top, right, bottom, 0.9f, fragments)
    }

    private fun fragment(
        text: String,
        left: Int,
        right: Int,
        top: Int = 8,
        bottom: Int = 24
    ): MlKitTextFragment {
        return MlKitTextFragment(text, left, top, right, bottom, 0.9f)
    }

    private fun fillRect(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int
    ) {
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[y * width + x] = color
            }
        }
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -0x1
    }
}
