package com.fgogotran.chapter.packinstall

import com.fgogotran.chapter.library.*
import com.fgogotran.chapter.packs.StoryLanguage
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackPersistentSelectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private suspend fun idle(controller: StoryPackController) = withTimeout(5000) { controller.state.first { !it.busy } }
    private fun controller(f: PackInstallFixture, scope: CoroutineScope) = StoryPackController(f.library, StoryPackPreviewPort { error("No preview expected") }, scope, f.store)

    @Test fun `selected older revision survives reopen and import and choosing latest explicitly replaces it`(): Unit = runBlocking {
        val f = PackInstallFixture(temporary.newFolder()); val one = f.import(); f.desired(one)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = controller(f, scope); c.foreground()
            assertEquals(setOf(one.sha256), idle(c).selected)
            val two = f.import(revision = 2, translated = "Revision two")
            c.reload(); val loaded = idle(c)
            assertEquals(setOf(one.sha256), loaded.selected)
            assertEquals(setOf(one, two), loaded.available.toSet())
            c.select(two.sha256, true); assertEquals(setOf(two.sha256), idle(c).selected)
            assertEquals(setOf(two.sha256), f.store.loadSelection().archives)
            val reopened = controller(f, scope); reopened.foreground()
            assertEquals(setOf(two.sha256), idle(reopened).selected)
            assertTrue(f.files.writes.isEmpty()); assertNull(f.store.load())
        } finally { scope.cancel() }
    }

    @Test fun `missing saved archives are visible and never silently dropped or substituted`(): Unit = runBlocking {
        val f = PackInstallFixture(temporary.newFolder()); val entry = f.import(); f.desired(entry)
        check(File(f.folder, "library/archives/${entry.sha256}.fgostory.zip").delete()) // This test's generated private copy only.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = controller(f, scope); c.foreground(); val state = idle(c)
            assertTrue(state.selectionMissing); assertEquals(setOf(entry.sha256), state.selected)
            c.preview(); assertNull(c.state.value.preview)
            c.clearSelection(); assertTrue(idle(c).selected.isEmpty()); assertTrue(f.store.loadSelection().archives.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `language changes are durable intent and stale UI cannot overwrite another saved choice`(): Unit = runBlocking {
        val f = PackInstallFixture(temporary.newFolder()); val entry = f.import(); f.desired(entry)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = controller(f, scope); c.foreground(); idle(c)
            f.store.saveSelection(f.store.loadSelection(), StoryLanguage.ENGLISH, emptySet())
            c.clearSelection(); assertNotNull(idle(c).error)
            assertEquals(StoryLanguage.ENGLISH, f.store.loadSelection().language)
            c.reload(); idle(c); c.language(StoryLanguage.SIMPLIFIED_CHINESE)
            assertEquals(StoryLanguage.SIMPLIFIED_CHINESE, idle(c).language)
            assertEquals(StoryLanguage.SIMPLIFIED_CHINESE, f.store.loadSelection().language)
            assertNull(f.store.load()); assertTrue(f.files.writes.isEmpty())
        } finally { scope.cancel() }
    }
}
