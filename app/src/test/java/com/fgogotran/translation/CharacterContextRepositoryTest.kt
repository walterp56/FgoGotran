package com.fgogotran.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CharacterContextRepositoryTest {
    @Test
    fun `bundled profile keeps both Chinese prompts and adds English guidance`() {
        val profile = loadBeniEnmaCharacterContextForTest()

        assertEquals("紅閻魔", profile.speakerId)
        assertEquals(listOf("紅閻魔"), profile.aliases)
        assertEquals(
            "childlike, polite, earnest, and slightly stern. When the JP visibly uses her " +
                "でち-style lisp (e.g. でち/まちゅ/まちぇん), preserve it naturally, usually with " +
                "clause-final 啾; never convert ordinary ち or add 啾 when the tic is absent.",
            profile.generalPrompt
        )
        assertEquals(
            "幼女般礼貌、认真、略显严厉；仅当原文明确出现でち、まちゅ或まちぇん等口癖时，" +
                "自然用句末“啾”体现；普通ち或原文无口癖时不得添加“啾”。",
            profile.sakuraPrompt
        )
        assertTrue(profile.englishPrompt.contains("occasional \"chirp,"))
        assertTrue(profile.englishPrompt.contains("Do not force one \"chirp\" per occurrence or sentence."))
        assertTrue(profile.englishPrompt.contains("Never treat ordinary ち as the tic or add it when absent."))
        assertTrue(profile.englishPrompt.contains("Preserve the original meaning."))
        assertFalse(profile.englishPrompt.contains("啾"))
    }

    @Test
    fun `English target aliases select the English prompt`() {
        val profile = loadBeniEnmaCharacterContextForTest()

        for (target in listOf("en", "en-US", "EN-GB")) {
            for (sakura in listOf(false, true)) {
                assertEquals(profile.englishPrompt, profile.promptFor(target, sakura))
            }
        }
    }

    @Test
    fun `Chinese prompt selection and cache identities remain unchanged`() {
        val profile = loadBeniEnmaCharacterContextForTest()

        for (target in listOf("zh-Hans", "zh-Hant", "zh-Hant-HK")) {
            assertEquals(profile.generalPrompt, profile.promptFor(target, isSakuraModel = false))
            assertEquals(profile.sakuraPrompt, profile.promptFor(target, isSakuraModel = true))
            assertEquals(
                listOf(profile.speakerId, "general", profile.generalPrompt).joinToString("\u001D"),
                profile.cacheIdentityFor(target, isSakuraModel = false)
            )
            assertEquals(
                listOf(profile.speakerId, "sakura", profile.sakuraPrompt).joinToString("\u001D"),
                profile.cacheIdentityFor(target, isSakuraModel = true)
            )
        }
    }

    @Test
    fun `English identity excludes the old Chinese policy and normalizes locale aliases`() {
        val profile = loadBeniEnmaCharacterContextForTest()
        val identity = profile.cacheIdentityFor("en", isSakuraModel = false)

        assertEquals(
            listOf(profile.speakerId, "english", profile.englishPrompt).joinToString("\u001D"),
            identity
        )
        assertEquals(identity, profile.cacheIdentityFor("en-US", isSakuraModel = false))
        assertNotEquals(identity, profile.cacheIdentityFor("zh-Hans", isSakuraModel = false))
        assertNotEquals(identity, profile.cacheIdentityFor("zh-Hans", isSakuraModel = true))
    }

    @Test
    fun `only a changed English prompt invalidates its identity`() {
        val profile = loadBeniEnmaCharacterContextForTest()
        val changed = profile.copy(englishPrompt = profile.englishPrompt + " Updated guidance.")

        assertNotEquals(
            profile.cacheIdentityFor("en", false),
            changed.cacheIdentityFor("en", false)
        )
        for (sakura in listOf(false, true)) {
            assertEquals(
                profile.cacheIdentityFor("zh-Hans", sakura),
                changed.cacheIdentityFor("zh-Hans", sakura)
            )
        }
    }

    @Test
    fun `parser normalizes English whitespace and supports BOM and comments`() {
        val profiles = parseCharacterContextProfiles(
            sequenceOf("# comment", "", "\uFEFF$HEADER", row("  Use   chirp.  "))
        )

        assertEquals("Use chirp.", profiles.single().englishPrompt)
    }

    @Test
    fun `parser rejects the old separate-profile header`() {
        assertFailsWith<IllegalArgumentException> {
            parseCharacterContextProfiles(sequenceOf("speaker_id\taliases\tprompt_general\tprompt_sakura\tprompt_english"))
        }
    }

    @Test
    fun `parser requires exactly eight columns`() {
        for (invalidRow in listOf(row().substringBeforeLast('\t'), row() + "\textra")) {
            assertFailsWith<IllegalArgumentException> {
                parseCharacterContextProfiles(sequenceOf(HEADER, invalidRow))
            }
        }
    }

    @Test
    fun `base-only profiles need no conditional rule and keep their original prompt identity`() {
        val profile = parseCharacterContextProfiles(sequenceOf(HEADER, row())).single()
        assertNull(profile.generalRule)
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            val prepared = prepareGeneralCharacterContext(profile, "待つのでち。", target)
            assertEquals(profile.promptFor(target, false), prepared.prompt)
            assertEquals(profile.cacheIdentityFor(target, false), prepared.cacheIdentity)
        }
    }

    @Test
    fun `conditional fields require a complete group with a bounded valid pattern`() {
        val valid = row().split('\t').toMutableList().apply {
            this[4] = "でち"
            this[5] = "Use 啾 only when present."
            this[6] = "Use chirp only when present."
        }
        for (column in 4..6) {
            val incomplete = valid.toMutableList().apply { this[column] = "" }
            assertFailsWith<IllegalArgumentException> {
                parseCharacterContextProfiles(sequenceOf(HEADER, incomplete.joinToString("\t")))
            }
        }
        for (pattern in listOf("[", "x".repeat(161))) {
            assertFailsWith<IllegalArgumentException> {
                parseCharacterContextProfiles(sequenceOf(HEADER, valid.toMutableList().apply {
                    this[4] = pattern
                }.joinToString("\t")))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            parseCharacterContextProfiles(sequenceOf(HEADER, valid.toMutableList().apply {
                this[6] = "x".repeat(800)
            }.joinToString("\t")))
        }
    }

    @Test
    fun `parser rejects blank or oversized English guidance`() {
        for (invalidPrompt in listOf("", "  ", "x".repeat(801))) {
            assertFailsWith<IllegalArgumentException> {
                parseCharacterContextProfiles(sequenceOf(HEADER, row(invalidPrompt)))
            }
        }
        assertEquals(
            800,
            parseCharacterContextProfiles(sequenceOf(HEADER, row("x".repeat(800))))
                .single().englishPrompt.length
        )
    }

    @Test
    fun `profile matching remains exact and excludes ambiguous aliases`() {
        val profile = loadBeniEnmaCharacterContextForTest()
        val index = buildCharacterContextIndex(listOf(profile))

        assertEquals(profile, index.profilesByAlias[normalizeCharacterContextSpeakerName("紅 閻魔")])
        assertFalse(index.profilesByAlias.containsKey(normalizeCharacterContextSpeakerName("???")))
        assertFalse(index.profilesByAlias.containsKey(normalizeCharacterContextSpeakerName("紅閻魔（別名）")))

        val ambiguous = buildCharacterContextIndex(
            listOf(profile, profile.copy(speakerId = "別人", aliases = listOf("紅閻魔")))
        )
        assertEquals(1, ambiguous.ambiguousAliasCount)
        assertFalse(ambiguous.profilesByAlias.containsKey("紅閻魔"))
    }

    private fun row(englishPrompt: String = "Use chirp."): String =
        listOf("紅閻魔", "紅閻魔", "General 啾.", englishPrompt, "", "", "", "Sakura 啾.").joinToString("\t")

    private companion object {
        const val HEADER = "speaker_id\taliases\tbase_general\tbase_english\tsource_pattern\tconditional_general\tconditional_english\tprompt_sakura"
    }
}

internal fun loadBeniEnmaCharacterContextForTest(): CharacterContextProfile {
    val relativePath = "src/main/assets/translation/character_context_prompts.tsv"
    val asset = File(relativePath).takeIf(File::isFile) ?: File("app/$relativePath")
    return asset.useLines(Charsets.UTF_8) { lines ->
        parseCharacterContextProfiles(lines).single { it.speakerId == "紅閻魔" }
    }
}
