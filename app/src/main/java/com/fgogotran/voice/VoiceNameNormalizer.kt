package com.fgogotran.voice

import java.text.Normalizer

internal object VoiceNameNormalizer {
    fun normalize(name: String): String {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
            .trim()
            .trim('「', '」', '『', '』', '【', '】', '《', '》', '[', ']', '（', '）', '(', ')')
            .replace(Regex("[\\u30FB\\uFF65\\u00B7\\u2022\\u2219]"), "")
            .replace(Regex("\\s+"), "")
    }
}
