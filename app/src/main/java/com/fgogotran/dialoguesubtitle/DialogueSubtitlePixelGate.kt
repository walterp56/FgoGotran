package com.fgogotran.dialoguesubtitle

/** A text-ink signature confined to the new panel's OCR region, not the animated artwork. */
class DialogueSubtitlePixelGate {
    private var previous: IntArray? = null
    private var lastReadAt = Long.MIN_VALUE
    var hasTextInk = false
        private set
    fun shouldRecognize(width: Int, height: Int, pixel: (Int, Int) -> Int, now: Long, pending: Boolean): Boolean {
        val bounds = DialogueSubtitleLayout.map(DialogueSubtitleLayout.text, width, height)
        val signature = IntArray(64 * 8)
        val step = (height / 1080f * 3).toInt().coerceAtLeast(1)
        for (y in bounds.top until bounds.bottom step step) for (x in bounds.left until bounds.right step step) {
            val color = pixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            if (minOf(r, g, b) < 175 || maxOf(r, g, b) - minOf(r, g, b) > 60) continue
            val column = ((x - bounds.left) * 64 / bounds.width).coerceIn(0, 63)
            val row = ((y - bounds.top) * 8 / bounds.height).coerceIn(0, 7)
            signature[row * 64 + column]++
        }
        val changed = previous?.let { old -> signature.indices.count { kotlin.math.abs(signature[it] - old[it]) >= 2 } >= 3 } ?: true
        hasTextInk = signature.sum() >= 6
        val read = pending || changed || lastReadAt == Long.MIN_VALUE || now - lastReadAt >= 800L
        if (read) { previous = signature; lastReadAt = now }
        return read
    }
    fun reset() { previous = null; lastReadAt = Long.MIN_VALUE; hasTextInk = false }
}
