package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps character rows and their gender index in one invalidatable snapshot.
 * Name matching and gender normalization remain supplied by Translator.
 */
internal class CharacterGenderLookup(
    private val loadCharacters: suspend () -> List<CharacterNameEntity>,
    private val lookupKeys: (CharacterNameEntity) -> List<String>,
    private val canonicalKey: (String) -> String,
    private val normalizeGender: (String) -> String
) {
    private val snapshotLock = Any()
    private val loadingMutex = Mutex()
    private var generation = Any()
    private var cachedSnapshot: Snapshot? = null

    private inner class Snapshot(val characters: List<CharacterNameEntity>) {
        // Build only when warm-up or a speaker lookup needs genders, not for
        // callers that only need the character-name rows.
        val genders: Map<String, String> by lazy { buildIndex(characters) }
    }

    suspend fun characters(): List<CharacterNameEntity> = snapshot().characters

    suspend fun warmUp() {
        indexedSnapshot()
    }

    suspend fun find(lookupKey: String): String =
        indexedSnapshot().genders[lookupKey].orEmpty()

    fun clear() {
        synchronized(snapshotLock) {
            generation = Any()
            cachedSnapshot = null
        }
    }

    private suspend fun snapshot(): Snapshot {
        while (true) {
            synchronized(snapshotLock) {
                cachedSnapshot?.let { return it }
            }
            val loaded = loadingMutex.withLock {
                val loadingGeneration = synchronized(snapshotLock) {
                    cachedSnapshot?.let { return@withLock it }
                    generation
                }
                val candidate = Snapshot(loadCharacters())
                synchronized(snapshotLock) {
                    // A glossary/profile change may happen during the DAO read.
                    if (generation === loadingGeneration) {
                        cachedSnapshot = candidate
                        candidate
                    } else {
                        null
                    }
                }
            }
            if (loaded != null) return loaded
        }
    }

    private suspend fun indexedSnapshot(): Snapshot {
        while (true) {
            val snapshot = snapshot()
            snapshot.genders
            synchronized(snapshotLock) {
                // Never reuse an index built from an invalidated snapshot.
                if (cachedSnapshot === snapshot) return snapshot
            }
        }
    }

    private fun buildIndex(characters: List<CharacterNameEntity>): Map<String, String> {
        val ownersByKey = mutableMapOf<String, MutableMap<String, String>>()
        characters.forEach { character ->
            val gender = normalizeGender(character.gender)
            if (gender.isBlank()) return@forEach
            val owner = canonicalKey(character.jpName)
            lookupKeys(character).forEach { key ->
                // Deduplicate owners per key, preserving the first matching row
                // just like the previous filter(...).distinctBy(...) scan.
                ownersByKey.getOrPut(key) { linkedMapOf() }.putIfAbsent(owner, gender)
            }
        }
        return buildMap {
            ownersByKey.forEach { (key, owners) ->
                // Two canonical characters are ambiguous even with equal gender.
                owners.values.singleOrNull()?.let { put(key, it) }
            }
        }
    }
}
