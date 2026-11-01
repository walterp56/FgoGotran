package com.fgogotran.chapter

/** Phase 1 proof only: one verified plain dialogue field, never a raw-script replacement. */
data class NativeTextPatch(
    val fieldId: NativeElementId,
    val expectedTextSha256: Sha256Digest,
    val replacement: String
) {
    init {
        require(replacement.isNotBlank() && replacement.length <= 4096)
        require(replacement.none { it.isISOControl() || it in "[]＠＄？" }) {
            "A proof replacement must not introduce native commands or line markers"
        }
        require(replacement.indices.none { index ->
            Character.isHighSurrogate(replacement[index]) &&
                (index + 1 == replacement.length || !Character.isLowSurrogate(replacement[index + 1])) ||
                Character.isLowSurrogate(replacement[index]) &&
                (index == 0 || !Character.isHighSurrogate(replacement[index - 1]))
        }) { "Replacement contains an unpaired surrogate" }
    }
}

/** Internal compiler output: one exact original field, never a caller-supplied byte range. */
internal data class NativeFieldReplacement(
    val fieldId: NativeElementId,
    val kind: NativeTextKind,
    val expectedTextSha256: Sha256Digest,
    val replacement: String
)
