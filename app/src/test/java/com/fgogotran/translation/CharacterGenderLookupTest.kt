package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.TermEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CharacterGenderLookupTest {
    @Test
    fun `character reads stay lazy and warm up builds the index only once`() = runBlocking {
        val rows = listOf(character("Mash", "女性", "Shield"))
        var loads = 0
        var keyBuilds = 0
        val lookup = lookup(
            load = { loads++; rows },
            keys = { keyBuilds++; keys(it) }
        )

        assertSame(rows, lookup.characters())
        assertSame(rows, lookup.characters())
        assertEquals(0, keyBuilds)
        lookup.warmUp()
        repeat(20) {
            assertEquals("女性", lookup.find("mash"))
            assertEquals("女性", lookup.find("shield"))
            assertEquals("", lookup.find("missing"))
        }
        lookup.warmUp()
        assertEquals(1, loads)
        assertEquals(1, keyBuilds)
    }

    @Test
    fun `lookup works without warm up`() = runBlocking {
        val lookup = lookup { listOf(character("Kadoc", "男性")) }
        assertEquals("男性", lookup.find("kadoc"))
    }

    @Test
    fun `different canonical owners stay ambiguous even with equal genders`() = runBlocking {
        val lookup = lookup {
            listOf(
                character("Jeanne", "女性", "Alter"),
                character("Artoria", "女性", "Alter")
            )
        }
        assertEquals("", lookup.find("alter"))
        assertEquals("女性", lookup.find("jeanne"))
        assertEquals("女性", lookup.find("artoria"))
    }

    @Test
    fun `exact short name keeps its gender when its full name generates the same key`() = runBlocking {
        val rows = listOf(character("Sherlock Holmes", "男性", "Holmes"), character("Holmes", "男性"))
        for (ordered in listOf(rows, rows.reversed())) {
            val lookup = lookup { ordered }
            assertEquals("男性", lookup.find("holmes"))
            assertEquals("男性", lookup.find("sherlock holmes"))
        }
    }

    @Test
    fun `blank shared short row does not override ambiguous parent ownership`() = runBlocking {
        val lookup = lookup {
            listOf(character("Alpha", "女性", "Shared"), character("Beta", "女性", "Shared"),
                character("Shared", ""))
        }
        assertEquals("", lookup.find("shared"))
        assertEquals("女性", lookup.find("alpha"))
    }

    @Test
    fun `RAG recovers missing character gender without changing target text or explicit metadata`() = runBlocking {
        val lookup = lookup {
            listOf(character("Sherlock Holmes", "男性", "Holmes"), character("Holmes", "男性"),
                character("Alpha", "女性", "Shared"), character("Beta", "男性", "Shared"))
        }
        val terms = listOf(
            TermEntity(jpTerm = "Holmes", cnTerm = "福尔摩斯", enTerm = "Holmes", category = "character_part"),
            TermEntity(jpTerm = "Shared", cnTerm = "共通", category = "character_part"),
            TermEntity(jpTerm = "Sherlock Holmes", cnTerm = "夏洛克·福尔摩斯", category = "character"),
            TermEntity(jpTerm = "Holmes", cnTerm = "地名", category = "place"),
            TermEntity(jpTerm = "Holmes", cnTerm = "明确记录", category = "character", gender = "性別不明")
        )
        val enriched = enrichCharacterTermGenders(terms, lookup, ::canonical)
        assertEquals(terms[0].copy(gender = "男性"), enriched[0])
        assertSame(terms[1], enriched[1])
        assertEquals(terms[2].copy(gender = "男性"), enriched[2])
        assertSame(terms[3], enriched[3])
        assertSame(terms[4], enriched[4])
        assertEquals("male", TranslationGlossaryBuilder.genderNoteFor(enriched[0].gender, "en"))
        assertEquals("男性", TranslationGlossaryBuilder.genderNoteFor(enriched[0].gender, "zh-Hant"))
    }

    @Test
    fun `duplicate aliases and canonical rows do not create ambiguity`() = runBlocking {
        val lookup = lookup {
            listOf(
                character("Mash", "女性", "Shield|Shield"),
                character(" Mash ", "女性", "Shield|Kouhai")
            )
        }
        assertEquals("女性", lookup.find("shield"))
        assertEquals("女性", lookup.find("kouhai"))
    }

    @Test
    fun `first matching canonical row wins separately for each key`() = runBlocking {
        val lookup = lookup {
            listOf(
                character("Alpha", "女性", "Shared|First"),
                character(" Alpha ", "男性", "Shared|Second")
            )
        }
        assertEquals("女性", lookup.find("shared"))
        assertEquals("女性", lookup.find("first"))
        assertEquals("男性", lookup.find("second"))
    }

    @Test
    fun `unrecognized gender rows are excluded before ambiguity counting`() = runBlocking {
        val lookup = lookup {
            listOf(
                character("Unknown", "", "Shared"),
                character("Invalid", "other", "Shared"),
                character("Known", " 女性 ", "Shared"),
                character("Unspecified", "性別不明")
            )
        }
        assertEquals("女性", lookup.find("shared"))
        assertEquals("", lookup.find("unknown"))
        assertEquals("性别不明", lookup.find("unspecified"))
    }

    @Test
    fun `clear reloads both rows and index including previously missing keys`() = runBlocking {
        var rows = listOf(character("Mash", "女性", "Shared"))
        var loads = 0
        val lookup = lookup { loads++; rows }
        assertEquals("女性", lookup.find("shared"))
        assertEquals("", lookup.find("kadoc"))

        rows = listOf(character("Kadoc", "男性", "Shared"))
        lookup.clear()
        assertSame(rows, lookup.characters())
        assertEquals("男性", lookup.find("shared"))
        assertEquals("男性", lookup.find("kadoc"))
        assertEquals("", lookup.find("mash"))
        assertEquals(2, loads)
    }

    @Test
    fun `empty data is cached until invalidated`() = runBlocking {
        var loads = 0
        val lookup = lookup { loads++; emptyList() }
        repeat(10) { assertEquals("", lookup.find("missing")) }
        assertEquals(1, loads)
        lookup.clear()
        lookup.warmUp()
        assertEquals(2, loads)
    }

    @Test
    fun `concurrent first requests share one load and index build`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val finishLoad = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val keyBuilds = AtomicInteger()
        val lookup = lookup(
            load = {
                loads.incrementAndGet()
                loadStarted.complete(Unit)
                finishLoad.await()
                listOf(character("Mash", "女性"))
            },
            keys = { keyBuilds.incrementAndGet(); keys(it) }
        )
        withTimeout(10_000) {
            val requests = List(16) { async(Dispatchers.Default) { lookup.find("mash") } }
            loadStarted.await()
            finishLoad.complete(Unit)
            assertEquals(List(16) { "女性" }, requests.awaitAll())
        }
        assertEquals(1, loads.get())
        assertEquals(1, keyBuilds.get())
    }

    @Test
    fun `clear during a suspended load cannot publish old rows`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val finishOldLoad = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val lookup = lookup {
            if (loads.incrementAndGet() == 1) {
                loadStarted.complete(Unit)
                finishOldLoad.await()
                listOf(character("Old", "女性", "Shared"))
            } else {
                listOf(character("New", "男性", "Shared"))
            }
        }
        withTimeout(10_000) {
            val result = async(Dispatchers.Default) { lookup.find("shared") }
            loadStarted.await()
            lookup.clear()
            finishOldLoad.complete(Unit)
            assertEquals("男性", result.await())
        }
        assertEquals("new", lookup.characters().single().jpName.lowercase())
        assertEquals("", lookup.find("old"))
        assertEquals(2, loads.get())
    }

    @Test
    fun `clear during index construction cannot reuse the old index`() = runBlocking {
        val buildStarted = CountDownLatch(1)
        val finishOldBuild = CountDownLatch(1)
        val loads = AtomicInteger()
        val keyBuilds = AtomicInteger()
        val lookup = lookup(
            load = {
                if (loads.incrementAndGet() == 1) listOf(character("Old", "女性", "Shared"))
                else listOf(character("New", "男性", "Shared"))
            },
            keys = {
                keyBuilds.incrementAndGet()
                if (it.jpName == "Old") {
                    buildStarted.countDown()
                    check(finishOldBuild.await(10, TimeUnit.SECONDS))
                }
                keys(it)
            }
        )
        val oldRequest = async(Dispatchers.Default) { lookup.find("shared") }
        try {
            assertTrue(buildStarted.await(10, TimeUnit.SECONDS))
            lookup.clear()
            // A new snapshot can be loaded while the obsolete build finishes.
            assertEquals("男性", lookup.find("shared"))
        } finally {
            finishOldBuild.countDown()
        }
        withTimeout(10_000) { assertEquals("男性", oldRequest.await()) }
        assertEquals("", lookup.find("old"))
        assertEquals(2, loads.get())
        assertEquals(2, keyBuilds.get())
    }

    @Test
    fun `failed load can be retried without clearing`() = runBlocking {
        var loads = 0
        val lookup = lookup {
            check(++loads > 1) { "Unavailable database" }
            listOf(character("Mash", "女性"))
        }
        assertFailsWith<IllegalStateException> { lookup.find("mash") }
        assertEquals("女性", lookup.find("mash"))
        assertEquals(2, loads)
    }

    @Test
    fun `cancelled warm up does not block the next lookup`() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        var loads = 0
        val lookup = lookup {
            if (++loads == 1) {
                loadStarted.complete(Unit)
                awaitCancellation()
            }
            listOf(character("Mash", "女性"))
        }
        withTimeout(10_000) {
            val warmUp = launch { lookup.warmUp() }
            loadStarted.await()
            warmUp.cancelAndJoin()
            assertEquals("女性", lookup.find("mash"))
        }
        assertEquals(2, loads)
    }

    @Test
    fun `failed index construction is not retained as a partial index`() = runBlocking {
        var loads = 0
        var fail = true
        val lookup = lookup(
            load = {
                loads++
                listOf(character("Mash", "女性", "Shared"), character("Kadoc", "男性", "Shared"))
            },
            keys = {
                check(!fail || it.jpName != "Kadoc") { "Interrupted index construction" }
                keys(it)
            }
        )
        assertFailsWith<IllegalStateException> { lookup.warmUp() }
        fail = false
        assertEquals("", lookup.find("shared"))
        assertEquals("男性", lookup.find("kadoc"))
        assertEquals(1, loads)
    }

    @Test
    fun `index answers match the old per key scan`() = runBlocking {
        val rows = listOf(
            character("Alpha", "女性", "Shared|First|First"),
            character(" Alpha ", "男性", "Shared|Second"),
            character("Beta", "女性", "Shared|Other"),
            character("Invalid", "", "Other"),
            character("Unspecified", "性別不明")
        )
        val lookup = lookup { rows }
        val queries = rows.flatMap(::keys).distinct() + listOf("", "missing")
        queries.forEach { query ->
            val expected = rows.asSequence()
                .filter { gender(it.gender).isNotBlank() }
                .filter { query in keys(it) }
                .distinctBy { canonical(it.jpName) }
                .singleOrNull()?.gender?.let(::gender).orEmpty()
            assertEquals(expected, lookup.find(query), query)
        }
    }

    private fun lookup(
        keys: (CharacterNameEntity) -> List<String> = ::keys,
        load: suspend () -> List<CharacterNameEntity>
    ) = CharacterGenderLookup(load, keys, ::canonical, ::gender)

    // Fixture selectors isolate index ownership/caching from Translator's
    // unchanged Japanese name/alias normalization rules.
    private fun keys(character: CharacterNameEntity): List<String> =
        (listOf(character.jpName) + character.aliases.orEmpty().split('|'))
            .map(::canonical).filter { it.isNotBlank() }

    private fun canonical(name: String): String = name.trim().lowercase()

    private fun gender(value: String): String = when (value.trim()) {
        "女性" -> "女性"
        "男性" -> "男性"
        "性別不明", "性别不明" -> "性别不明"
        else -> ""
    }

    private fun character(name: String, gender: String, aliases: String = "") =
        CharacterNameEntity(jpName = name, cnName = name, gender = gender, aliases = aliases)
}
