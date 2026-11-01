package com.fgogotran.chapter.packs

import com.fgogotran.chapter.packs.tools.LocalStoryPackPublisher
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class StoryPackPublisherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `publisher is reproducible immutable and catalogue-backed`() {
        val repository = temporary.newFolder()
        val draft = StoryPackTestData.draft(repository)
        val first = LocalStoryPackPublisher.publish(repository, draft)
        val archive = first.archive.readBytes(); val catalogue = first.catalogue.readBytes()
        assertFalse(first.reused)
        val next = LocalStoryPackPublisher.publish(repository, draft)
        assertTrue(next.reused)
        assertContentEquals(archive, next.archive.readBytes()); assertContentEquals(catalogue, next.catalogue.readBytes())
        val index = StoryPackJson.decodeCatalogue(catalogue)
        StoryPackArchive.read(first.archive, index.entries.single())
        assertFalse(File(repository, "story_assets/jp-originals").exists())
    }

    @Test fun `new revisions append without deleting prior packages`() {
        val repository = temporary.newFolder()
        val first = LocalStoryPackPublisher.publish(repository, StoryPackTestData.draft(repository))
        val oldArchive = first.archive.readBytes()
        val second = LocalStoryPackPublisher.publish(repository,
            StoryPackTestData.draft(repository, "revision-two", StoryPackTestData.manifest(revision = 2)))
        val catalogue = StoryPackJson.decodeCatalogue(second.catalogue.readBytes())
        assertEquals(2, catalogue.entries.size); assertEquals(2, catalogue.latest().single().revision)
        assertContentEquals(oldArchive, first.archive.readBytes())
    }

    @Test fun `changed same revision and corrupt published history never overwrite the catalogue`() {
        val repository = temporary.newFolder()
        val first = LocalStoryPackPublisher.publish(repository, StoryPackTestData.draft(repository))
        val oldArchive = first.archive.readBytes(); val oldCatalogue = first.catalogue.readBytes()
        val changed = StoryPackTestData.patch("Different synthetic text")
        val changedDraft = StoryPackTestData.draft(repository, "conflicting", StoryPackTestData.manifest(changed), changed)
        assertFails { LocalStoryPackPublisher.publish(repository, changedDraft) }
        assertContentEquals(oldArchive, first.archive.readBytes()); assertContentEquals(oldCatalogue, first.catalogue.readBytes())
        first.archive.appendText("unexpected modification")
        val corrupt = first.archive.readBytes()
        assertFails { LocalStoryPackPublisher.publish(repository, StoryPackTestData.draft(repository, "revision-two", StoryPackTestData.manifest(revision = 2))) }
        assertContentEquals(corrupt, first.archive.readBytes()); assertContentEquals(oldCatalogue, first.catalogue.readBytes())
    }

    @Test fun `bad drafts outside paths and undeclared files never publish a catalogue`() {
        val repository = temporary.newFolder()
        val draft = StoryPackTestData.draft(repository)
        File(draft, "private-backup.bin").writeText("synthetic private data")
        assertFails { LocalStoryPackPublisher.publish(repository, draft) }
        assertFalse(File(repository, "story_assets/published/catalog.json").exists())
        assertFails { LocalStoryPackPublisher.publish(repository, temporary.newFolder()) }
        val bad = StoryPackTestData.draft(repository, "bad-digest")
        File(bad, "patches/0100000011.json").appendText(" ")
        assertFails { LocalStoryPackPublisher.publish(repository, bad) }
        assertFalse(File(repository, "story_assets/published/catalog.json").exists())
    }
}
