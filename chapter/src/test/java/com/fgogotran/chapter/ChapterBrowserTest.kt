package com.fgogotran.chapter

import com.fgogotran.chapter.codec.FgoJpFormatProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ChapterBrowserTest {
    @Test fun `construction performs no reads and scan requests only the fixed manifest`() = runBlocking {
        val paths = mutableListOf<GameAssetPath>()
        val browser = ChapterBrowser(object : ChapterOriginalStorage {
            override suspend fun capability() = ChapterStorageCapability.ReadOnly("test")
            override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult {
                paths += path
                return OriginalAssetReadResult.NotFound
            }
        })
        assertTrue(paths.isEmpty())
        assertFailsWith<IllegalStateException> { browser.scan() }
        assertEquals(listOf(FgoJpFormatProfile.MANIFEST_NAME), paths.map { it.value })
    }

    @Test fun `unavailable storage is not reported as an empty successful catalogue`() = runBlocking {
        assertFailsWith<IllegalStateException> { ChapterBrowser(UnavailableChapterStorage()).scan() }
        Unit
    }

    @Test fun `cancellation is propagated not converted into a successful scan`() = runBlocking {
        val browser = ChapterBrowser(object : ChapterOriginalStorage {
            override suspend fun capability() = ChapterStorageCapability.ReadOnly("test")
            override suspend fun readOriginal(path: GameAssetPath): OriginalAssetReadResult = throw CancellationException("test")
        })
        assertFailsWith<CancellationException> { browser.scan() }
        Unit
    }
}
