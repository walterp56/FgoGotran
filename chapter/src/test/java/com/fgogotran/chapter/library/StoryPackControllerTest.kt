package com.fgogotran.chapter.library

import com.fgogotran.chapter.packs.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private suspend fun idle(controller: StoryPackController) = withTimeout(5000) { controller.state.first { !it.busy } }
    private fun report(fixture: StoryLibraryFixture) = StoryPackPreview(fixture.native.version, fixture.index.fingerprint.sha256.hex, 1, 1, 1, 1, 123)

    @Test fun `selection is filtered by language latest revision and session changes invalidate preview`(): Unit = runBlocking {
        val fixture = StoryLibraryFixture(temporary.newFolder()); val library = fixture.library()
        val old = fixture.import(library, fixture.pack())
        val latest = fixture.import(library, fixture.pack(revision = 2))
        val english = fixture.import(library, fixture.pack(language = StoryLanguage.ENGLISH))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = StoryPackController(library, StoryPackPreviewPort { report(fixture) }, scope)
            controller.foreground()
            assertEquals(listOf(latest), idle(controller).available)
            assertFailsWith<IllegalArgumentException> { controller.select(old.sha256, true) }
            controller.select(latest.sha256, true); controller.preview()
            assertNotNull(idle(controller).preview)
            controller.language(StoryLanguage.ENGLISH)
            assertTrue(controller.state.value.selected.isEmpty()); assertNull(controller.state.value.preview)
            assertEquals(listOf(english), controller.state.value.available)
            controller.select(english.sha256, true)
            controller.background(); controller.foreground()
            assertEquals(listOf(english), idle(controller).selection)
            val reopened = StoryPackController(fixture.library(), StoryPackPreviewPort { report(fixture) }, scope)
            reopened.foreground()
            assertEquals(3, idle(reopened).entries.size)
            assertTrue(reopened.state.value.selected.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `import refreshes available chapters while a failed import keeps the valid library`(): Unit = runBlocking {
        val fixture = StoryLibraryFixture(temporary.newFolder()); val library = fixture.library()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = StoryPackController(library, StoryPackPreviewPort { report(fixture) }, scope)
            controller.foreground(); assertTrue(idle(controller).entries.isEmpty())
            val pack = fixture.pack()
            controller.import { fixture.archive(pack).inputStream() }
            val imported = idle(controller)
            assertEquals(pack.archiveSha256, imported.imported!!.sha256)
            controller.select(imported.available.single().sha256, true)
            controller.import { "not zip".byteInputStream() }
            val failed = idle(controller)
            assertNotNull(failed.error); assertEquals(1, failed.entries.size); assertNull(failed.preview)
            assertEquals(1, library.list().size)
        } finally { scope.cancel() }
    }

    @Test fun `late cancelled preview cannot republish success after a language change or background`(): Unit = runBlocking {
        val fixture = StoryLibraryFixture(temporary.newFolder()); val library = fixture.library()
        val entry = fixture.import(library)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = StoryPackController(library, StoryPackPreviewPort {
                withContext(NonCancellable) { started.complete(Unit); finish.await(); returned.complete(Unit); report(fixture) }
            }, scope)
            controller.foreground(); idle(controller); controller.select(entry.sha256, true); controller.preview()
            withTimeout(5000) { started.await() }
            controller.language(StoryLanguage.ENGLISH); controller.background()
            finish.complete(Unit); withTimeout(5000) { returned.await() }
            assertNull(controller.state.value.preview); assertFalse(controller.state.value.busy)
            assertEquals(StoryLanguage.ENGLISH, controller.state.value.language)
            assertTrue(controller.state.value.selected.isEmpty())
        } finally { finish.complete(Unit); scope.cancel() }
    }
}
