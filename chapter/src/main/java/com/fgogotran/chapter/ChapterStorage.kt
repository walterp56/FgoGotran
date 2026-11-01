package com.fgogotran.chapter

enum class StorageUnavailableReason { NOT_CONFIGURED, PERMISSION_DENIED, UNSUPPORTED, ROOT_NOT_VERIFIED }

sealed interface ChapterStorageCapability {
    /** A provider's verified root identity, not a filesystem path supplied by the caller. */
    data class ReadOnly(val rootIdentity: String) : ChapterStorageCapability {
        init { requireIdentifier(rootIdentity) }
    }

    data class Unavailable(val reason: StorageUnavailableReason) : ChapterStorageCapability
}

sealed interface OriginalAssetReadResult {
    data class Read(val original: OriginalAsset) : OriginalAssetReadResult
    data class Unavailable(val reason: StorageUnavailableReason) : OriginalAssetReadResult
    data object NotFound : OriginalAssetReadResult
}

/** No write, install, delete, permission-request or automatic discovery path is exposed. */
interface ChapterOriginalStorage {
    suspend fun capability(): ChapterStorageCapability
    suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult
}

/** The only initial adapter fails closed without opening any game data. */
class UnavailableChapterStorage(
    private val reason: StorageUnavailableReason = StorageUnavailableReason.NOT_CONFIGURED
) : ChapterOriginalStorage {
    override suspend fun capability(): ChapterStorageCapability = ChapterStorageCapability.Unavailable(reason)
    override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult = OriginalAssetReadResult.Unavailable(reason)
}
