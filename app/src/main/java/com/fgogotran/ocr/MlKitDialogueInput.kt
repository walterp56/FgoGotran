package com.fgogotran.ocr

import android.graphics.Bitmap

/** Noise suppression only: no emphasis metadata, resizing, ruby removal, or recognition pass. */
internal object MlKitDialogueInput {
    class Prepared(
        val bitmap: Bitmap,
        val removedDots: Int = 0,
        val removedRows: Int = 0,
        private val ownsBitmap: Boolean = false
    ) : AutoCloseable {
        override fun close() {
            if (ownsBitmap && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun prepare(bitmap: Bitmap, contentKind: OcrContentKind): Prepared {
        // Choices already use ChoiceOcrContext's own colour/geometry rules. Names, arbitrary
        // crops and subtitles must not inherit assumptions about a story dialogue's layout.
        if (contentKind != OcrContentKind.DIALOGUE && contentKind != OcrContentKind.FIXED_JP_DIALOGUE) {
            return Prepared(bitmap)
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val cleanup = DialogueAnnotationCleaner.clean(pixels, bitmap.width, bitmap.height,
            maskVerifiedAlignedDots = true)
        if (!cleanup.changed) return Prepared(bitmap)
        return Prepared(
            Bitmap.createBitmap(cleanup.pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888),
            cleanup.maskedComponents,
            cleanup.maskedRows,
            ownsBitmap = true
        )
    }
}
