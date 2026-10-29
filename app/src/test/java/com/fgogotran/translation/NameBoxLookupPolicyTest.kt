package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.TermEntity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameBoxLookupPolicyTest {
    private val elisabeth = character("エリザベート", "伊丽莎白", "Elisabeth")

    @Test
    fun `complete stored names select only the requested target`() {
        assertEquals("伊丽莎白", find("エリザベート", listOf(elisabeth)))
        assertEquals("Elisabeth", find("エリザベート", listOf(elisabeth), english = true))
        assertNull(find("エリザベート", listOf(elisabeth.copy(enName = "")), english = true))
        assertNull(find("エリザベート", listOf(elisabeth.copy(cnName = " "))))
    }

    @Test
    fun `suffixes prefixes punctuation ruby and OCR near matches never return the base name`() {
        for (label in listOf(
            "エリザベートたち", "エリザベートちゃん", "たちエリザベート", "エリザベートA",
            "エリザベート1", "エリザベート？", "エリザベート（偽）", "エリザベート《ナレーター》",
            "エリザベート〈エリー〉", "エリサベート", "ェリザベート"
        )) {
            for (english in listOf(false, true)) {
                assertNull(find(label, listOf(elisabeth), english), label)
            }
        }
    }

    @Test
    fun `parts are not invented from a longer stored name`() {
        val full = character("エリザベート・バートリー", "伊丽莎白·巴托里", "Elisabeth Báthory")
        for (english in listOf(false, true)) {
            assertNull(find("エリザベート", listOf(full), english))
            assertNull(find("バートリー", listOf(full), english))
            assertNull(find("エリザベートバートリー", listOf(full), english))
        }
        // Component rows already present in the DB are still complete stored entries.
        assertEquals("伊丽莎白", find("エリザベート", listOf(full, elisabeth)))
    }

    @Test
    fun `explicit alias survives even when broad normalization would merge it with canonical name`() {
        for (aliases in listOf("エリザベートバートリー", "[\"エリザベートバートリー\"]")) {
            val full = character(
                "エリザベート・バートリー", "伊丽莎白·巴托里", "Elisabeth Báthory", aliases
            )
            assertEquals("伊丽莎白·巴托里", find("エリザベートバートリー", listOf(full)))
            assertEquals("Elisabeth Báthory", find("エリザベートバートリー", listOf(full), true))
            assertNull(find("エリザベートバートリーたち", listOf(full)))
        }
    }

    @Test
    fun `states groups and combined labels require their own complete entries`() {
        val mash = character("マシュ", "玛修", "Mash")
        val daVinci = character("ダ・ヴィンチ", "达·芬奇", "Da Vinci")
        val seton = character("シートン", "西顿", "Seton")
        val fullLabels = listOf(
            character("エリザベートたち", "伊丽莎白们", "The Elisabeths"),
            character("シートン（？）", "西顿（？）", "Seton (?)"),
            character("マシュ《ナレーター》", "玛修《旁白》", "Mash (Narrator)"),
            character("マシュ＆ダ・ヴィンチ", "玛修＆达·芬奇", "Mash & Da Vinci")
        )
        val bases = listOf(elisabeth, mash, daVinci, seton)
        fullLabels.forEach { label ->
            assertNull(find(label.jpName, bases), label.jpName)
            assertNull(find(label.jpName, bases, true), label.jpName)
            assertEquals(label.cnName, find(label.jpName, bases + label))
            assertEquals(label.enName, find(label.jpName, bases + label, true))
        }
    }

    @Test
    fun `formatting normalization preserves meaningful separators spaces and markers`() {
        val soldier = character("兵士Ａ１", "士兵A1", "Soldier A1")
        assertEquals("士兵A1", find("  兵士A1　", listOf(soldier)))
        val spaced = character("Alpha Beta", "甲乙", "Alpha Beta")
        assertEquals("甲乙", find(" Alpha   Beta ", listOf(spaced)))
        assertNull(find("AlphaBeta", listOf(spaced)))
        assertNull(find("兵士A", listOf(soldier)))
        assertNull(find("兵士A2", listOf(soldier)))
        assertNull(find("BBGO", listOf(character("ＢＢ／ＧＯ", "BB/GO", "BB/GO"))))
    }

    @Test
    fun `JSON aliases keep commas and quotes inside the complete alias`() {
        val row = character("原名", "译名", "Target", "[\"別名,その二\",\"別名\\\"その三\"]")
        assertEquals("译名", find("別名,その二", listOf(row)))
        assertEquals("译名", find("別名\"その三", listOf(row)))
        assertNull(find("別名", listOf(row)))
        assertNull(find("その二", listOf(row)))
        assertNull(find("別名", listOf(row.copy(aliases = "[\"別名\""))))
    }

    @Test
    fun `blank unknown labels and absent aliases are misses`() {
        for (source in listOf("", "　 ", "少女", "不明な人物")) {
            assertNull(find(source, listOf(elisabeth)))
        }
        assertNull(find("エリザベート", emptyList()))
        assertEquals("伊丽莎白", find("エリザベート", listOf(elisabeth.copy(aliases = null))))
    }

    @Test
    fun `local row priority and replaced snapshots remain effective`() {
        val local = elisabeth.copy(cnName = "自订译名")
        assertEquals("自订译名", find("エリザベート", listOf(local, elisabeth)))
        assertEquals("伊丽莎白", find("エリザベート", listOf(elisabeth)))
        assertNull(find("エリザベート", listOf(character("マシュ", "玛修", "Mash"))))
    }

    @Test
    fun `partial name RAG remains guidance and the complete label remains API input`() {
        val builder = PromptBuilder()
        val base = TermEntity(jpTerm = "エリザベート", cnTerm = "伊丽莎白", enTerm = "Elisabeth", category = "character")
        val source = "エリザベートたち"
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            assertNull(find(source, listOf(elisabeth), target == "en"))
            val matches = builder.extractTermMatches(source, listOf(base), target)
            assertEquals(listOf(base), matches)
            val entry = TranslationGlossaryEntry(base.jpTerm, if (target == "en") base.enTerm else base.cnTerm)
            val prompt = buildGeneralSingleUserPrompt(
                builder, source, emptyList(), translateAsName = true, glossaryEntries = listOf(entry)
            )
            assertTrue(prompt.contains("${entry.source}->${entry.target}"))
            assertTrue(prompt.endsWith("Current Japanese:\n$source"))
        }
    }

    @Test
    fun `scene name admission uses only strict full-label lookup before existing API and cache paths`() {
        val scene = function(translatorSource(), "translateScene")
        val names = scene.substringAfter("normalizedName?.let { normalized ->")
            .substringBefore("normalizedDialogue?.let { normalized ->")
        assertTrue(names.contains("maskedSourceFallback(normalized)"))
        assertTrue(names.contains("findExactVisibleCharacterNameTranslation("))
        assertTrue(names.contains("!TextNormalizer.hasRubyAnnotations(normalized)"))
        assertTrue(names.contains("sanitizeVisibleCharacterNameResult(it)"))
        for (disallowed in listOf(
            "findCharacterNameTranslation(", "resolveCharacterNameWithState(",
            "resolveCombinedCharacterNames(", "allowOcrWrappedMatch"
        )) assertFalse(names.contains(disallowed), disallowed)
        assertTrue(scene.contains("val uncachedName = if (needsName) nameForLlm else null"))
        assertTrue(scene.contains("val combinedText = listOfNotNull(uncachedName, sceneDialogueForApi)"))
        assertTrue(scene.contains("promptBuilder.extractTermMatches(combinedText, allTerms, config.targetLanguage)"))
        assertTrue(scene.contains("val protectedName = uncachedName?.let(::preparePromptText)"))
        assertTrue(scene.contains("translateName = needsName"))
        assertTrue(function(translatorSource(), "buildSceneUserPrompt").contains("appendLine(it)"))
    }

    @Test
    fun `name-only fallback cannot reenter general memory term or fuzzy shortcuts`() {
        val single = function(translatorSource(), "translate")
        val admission = single.substringAfter("if (translateAsName) {").substringBefore("val useSakuraPrompt")
        val nonNameBranch = "} else if (alignedSubtitleRows == 0) {"
        assertTrue(admission.contains(nonNameBranch))
        val names = admission.substringBefore(nonNameBranch)
        assertTrue(names.contains("findExactVisibleCharacterNameTranslation(normalizedText, config.targetLanguage,"))
        assertTrue(names.contains("useTraditionalOverrides = usesTraditionalOverrides(config)"))
        assertTrue(names.contains("!forceFreshTranslation && !TextNormalizer.hasRubyAnnotations(normalizedText)"))
        for (lookup in listOf("translationMemory.lookupNormalized", "findCharacterNameTranslation(", "findTermTranslation(")) {
            assertFalse(names.contains(lookup), lookup)
            assertTrue(admission.substringAfter(nonNameBranch).contains(lookup), lookup)
        }
        val exact = function(translatorSource(), "findExactVisibleCharacterNameTranslation")
        assertTrue(exact.contains("NameBoxLookupPolicy.findTranslation("))
        assertTrue(exact.contains("characters = getCachedCharacterNames()"))
        assertFalse(exact.contains("characterNameVariants("))
    }

    @Test
    fun `new name namespaces bypass old entries without changing other cache policies`() {
        assertNotEquals("", NameBoxLookupPolicy.SCENE_CACHE_POLICY)
        assertNotEquals("name-only-v1", NameBoxLookupPolicy.SINGLE_CACHE_POLICY)
        assertNotEquals("scene-name-whole-label-v1", NameBoxLookupPolicy.SCENE_CACHE_POLICY)
        assertNotEquals("name-only-whole-label-v2", NameBoxLookupPolicy.SINGLE_CACHE_POLICY)
        assertNotEquals("scene-name-label-v2", NameBoxLookupPolicy.SCENE_CACHE_POLICY)
        assertNotEquals("name-only-label-v3", NameBoxLookupPolicy.SINGLE_CACHE_POLICY)
        assertNotEquals(NameBoxLookupPolicy.SCENE_CACHE_POLICY, NameBoxLookupPolicy.SINGLE_CACHE_POLICY)
        val translator = translatorSource()
        val scene = function(translator, "translateScene")
        val nameKey = scene.substringAfter("val nameKey = nameForLlm?.let {").substringBefore("val nameHash")
        assertTrue(nameKey.contains("normalizedText = it"))
        assertTrue(nameKey.contains("promptPolicyKey = NameBoxLookupPolicy.SCENE_CACHE_POLICY"))
        assertTrue(scene.contains("lookupCachedTranslation(nameHash, nameForLlm"))
        assertTrue(Regex("""cacheTranslatedText\(\s+nameHash!!,""").containsMatchIn(scene))
        val single = function(translator, "translate")
        assertTrue(single.contains("translateAsName -> NameBoxLookupPolicy.SINGLE_CACHE_POLICY"))
        assertTrue(single.contains("cropMode -> if (useSakuraPrompt) \"crop-screen-v2\" else \"crop-screen-v3\""))
        assertTrue(single.contains("preserveRubyMeaning -> \"ruby-angle-v4\""))
        assertEquals(2, Regex("NameBoxLookupPolicy\\.(?:SCENE|SINGLE)_CACHE_POLICY").findAll(translator).count())
    }

    private fun find(source: String, rows: List<CharacterNameEntity>, english: Boolean = false): String? =
        NameBoxLookupPolicy.findTranslation(source, rows, english)

    private fun character(jp: String, cn: String, en: String, aliases: String = "[]") =
        CharacterNameEntity(jpName = jp, cnName = cn, enName = en, aliases = aliases)

    private fun translatorSource(): String = listOf(
        File("src/main/java/com/fgogotran/translation/Translator.kt"),
        File("app/src/main/java/com/fgogotran/translation/Translator.kt")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(source: String, name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(source).drop(1)
            .first { it.startsWith("$name(") }
}
