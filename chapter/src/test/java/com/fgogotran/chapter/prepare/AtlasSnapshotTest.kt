package com.fgogotran.chapter.prepare

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.*

class AtlasSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ids = listOf("0100000010", "0100000011")

    @Test fun `regional inventory and raw provenance are immutable and revalidated from disk`() {
        val repository = temporary.newFolder()
        val snapshot = preparationSnapshot(repository, ids)
        assertEquals(8, snapshot.references.size)
        assertEquals(listOf(AtlasLink(100, 1000000, 1, 1)), snapshot.references("JP").single { it.id == ids[1] }.links)
        assertEquals(snapshot.contentSha256, AtlasSnapshot.load(snapshot.directory).contentSha256)
        assertContentEquals(preparationJp.toByteArray(), snapshot.bytes(snapshot.references("JP").first()))
        val target = snapshot.references("TW").first()
        assertContentEquals(localized(preparationJp).toByteArray(), snapshot.bytes(target))
        val oldIndex = File(snapshot.directory, "source-index.json").readBytes()
        assertFails { AtlasSnapshot.download(repository, setOf(100), fetch = AtlasFetch { _, _ -> throw IOException("temporary outage") }) }
        assertContentEquals(oldIndex, File(snapshot.directory, "source-index.json").readBytes())
        AtlasSnapshot.load(snapshot.directory)
        File(snapshot.directory, target.path).appendText("changed")
        assertFails { AtlasSnapshot.load(snapshot.directory) }
    }

    @Test fun `known 404 is absence but transient failure never becomes a verified missing report`() {
        val repository = temporary.newFolder()
        val ordinary = preparationFetch(ids)
        val missing = AtlasSnapshot.download(repository, setOf(100), fetch = AtlasFetch { url, limit ->
            if (url.endsWith("/TW/war/100") || url.endsWith("/CN/Script/01/${ids[0]}.txt")) null else ordinary.get(url, limit)
        })
        assertTrue(missing.references("TW").isEmpty())
        assertNull(missing.bytes(missing.references("CN").single { it.id == ids[0] }))
        assertEquals(2, missing.index.getValue("missing").jsonArray.size)
        for (failure in listOf("429", "500", "timeout")) {
            assertFails { AtlasSnapshot.download(repository, setOf(100), fetch = AtlasFetch { url, limit ->
                if (url.contains("/CN/Script/")) throw IOException(failure) else ordinary.get(url, limit)
            }) }
        }
        assertEquals(1, File(repository, "story_assets/atlas-r2").listFiles()!!.count { !it.name.startsWith('.') })
    }

    @Test fun `source version change or unexpected script URL cannot publish a snapshot`() {
        val repository = temporary.newFolder()
        var info = 0
        val ordinary = preparationFetch(ids)
        assertFails { AtlasSnapshot.download(repository, setOf(100), fetch = AtlasFetch { url, limit ->
            if (url.endsWith("/info")) preparationInfo(if (++info == 1) "abcdef" else "fedcba") else ordinary.get(url, limit)
        }) }
        assertTrue(File(repository, "story_assets/atlas-r2").listFiles()!!.all { it.name.startsWith('.') })
        listOf("http://api.atlasacademy.io/info", "https://api.atlasacademy.io/info?token=x", "https://evil.example/JP/Script/01/x.txt",
            "https://static.atlasacademy.io/JP/Script/../x.txt", "https://static.atlasacademy.io/JP/Script/01/%2e%2e.txt").forEach {
            assertFails { AtlasEndpoints.validate(it) }
        }
        assertFails { AtlasEndpoints.reference("JP", ids[0], "https://static.atlasacademy.io/CN/Script/01/${ids[0]}.txt", listOf(AtlasLink(100, 1, 1, 0))) }
    }

    @Test fun `bounded scopes responses and path confinement fail closed`() {
        val repository = temporary.newFolder()
        assertFails { AtlasSnapshot.download(repository, (1..9).toSet(), fetch = preparationFetch(ids)) }
        assertFails { AtlasSnapshot.download(repository, setOf(100), setOf(1000000, 9999999), preparationFetch(ids)) }
        assertFails { AtlasSnapshot.download(repository, setOf(100), fetch = AtlasFetch { _, limit -> ByteArray(limit + 1) }) }
        assertFails { PreparationFiles.confined(repository, File(repository, "../escape")) }
        assertFails { PreparationFiles.bounded(byteArrayOf(1, 2, 3).inputStream(), 2) }
        assertFails { PreparationFiles.bounded(byteArrayOf(1).inputStream(), 2, System.nanoTime() - 1) }
        assertFails { PreparationFiles.json("{\"x\":1,\"x\":2}".toByteArray()) }
    }
}
