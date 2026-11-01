package com.fgogotran.chapter

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ChapterStorageTest {
    @Test
    fun `default adapter reports unavailable and never reads original data`() = runBlocking {
        val storage: ChapterOriginalStorage = UnavailableChapterStorage()
        assertEquals(
            ChapterStorageCapability.Unavailable(StorageUnavailableReason.NOT_CONFIGURED),
            storage.capability()
        )
        assertEquals(
            OriginalAssetReadResult.Unavailable(StorageUnavailableReason.NOT_CONFIGURED),
            storage.readOriginal(GameAssetPath.parse("files/data/source.bin"))
        )
    }

    @Test
    fun `every unavailable reason fails closed for capability and reads`() = runBlocking {
        for (reason in StorageUnavailableReason.entries) {
            val storage = UnavailableChapterStorage(reason)
            assertEquals(reason, assertIs<ChapterStorageCapability.Unavailable>(storage.capability()).reason)
            assertEquals(reason, assertIs<OriginalAssetReadResult.Unavailable>(storage.readOriginal(GameAssetPath.parse("a.bin"))).reason)
        }
    }

    @Test
    fun `read only capability requires an explicit unpadded root identity`() {
        assertEquals("verified-root", ChapterStorageCapability.ReadOnly("verified-root").rootIdentity)
        for (root in listOf("", " ", " root", "root\nname")) {
            assertFailsWith<IllegalArgumentException> { ChapterStorageCapability.ReadOnly(root) }
        }
    }
}
