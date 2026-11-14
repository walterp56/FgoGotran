package com.fgogotran.translation

import android.icu.text.Transliterator
import androidx.room.Room
import com.fgogotran.data.ApiSamplingSettings
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.scriptmemory.ScriptMemory
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.terminology.TermDao
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import java.io.File
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChineseHonorificNormalizationTest {
    private lateinit var translator: Translator
    private lateinit var db: TranslationCacheDb

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        val local = unusedDao(LocalGlossaryDao::class.java)
        val settings = SettingsRepository(app, local)
        db = Room.inMemoryDatabaseBuilder(app, TranslationCacheDb::class.java).build()
        translator = Translator(settings, UserProfile(settings), unusedDao(TermDao::class.java),
            local, PromptBuilder(), CharacterContextRepository(app), db,
            TranslationMemory(app), DiagnosticEventStore(app), ScriptMemory(app), app)
    }

    @After fun tearDown() {
        if (::translator.isInitialized) {
            val field = Translator::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
            (field.get(translator) as HttpClient).close()
        }
        if (::db.isInitialized) db.close()
    }

    @Test fun hantChanChoiceKeepsOneSuffixAcrossModelAndRepeatedCacheRoundTrips() {
        val source = "……ダ・ヴィンチちゃん"
        val expected = "……達·文西醬"
        var visible = sanitize("sanitizeTraditionalModelTranslation", source, expected)
        assertEquals(expected, visible)
        repeat(3) {
            val stored = call("sanitizeCacheTranslation", source, visible, "zh-Hant") as String
            assertEquals("……达·文西酱", stored)
            visible = call("toTargetChinese", stored, "zh-Hant") as String
            assertEquals(expected, visible)
        }
    }

    @Test fun existingHonorificsAreRecognisedInEitherChineseScript() {
        val cases = listOf(
            Triple("ちゃん", "酱", "醬"), Triple("てゃ", "宝", "寶"),
            Triple("殿", "阁下", "閣下"), Triple("さん", "桑", "桑"),
            Triple("くん", "君", "君"), Triple("たん", "炭", "炭"),
            Triple("様", "大人", "大人"), Triple("氏", "氏", "氏")
        )
        for ((jp, hans, hant) in cases) {
            for (input in listOf("達·文西$hant", "达·文西$hans")) {
                assertEquals("達·文西$hant", sanitize("sanitizeTraditionalModelTranslation", "ダ・ヴィンチ$jp", input), "$jp: $input")
                assertEquals("达·文西$hans", sanitize("sanitizeTranslation", "ダ・ヴィンチ$jp", input), "$jp: $input")
            }
        }
    }

    @Test fun missingOrWrongChanStillGetsOneSuffixAndKeepsPunctuation() {
        for ((source, input, expected) in listOf(
            Triple("ダ・ヴィンチちゃん", "達·文西", "達·文西醬"),
            Triple("ダ・ヴィンチちゃん", "達·文西小姐", "達·文西醬"),
            Triple("……ダ・ヴィンチちゃん！？", "……達·文西醬！？", "……達·文西醬！？"),
            Triple("ダ・ヴィンチちゃん", "達·文西親", "達·文西醬")
        )) {
            val once = sanitize("sanitizeTraditionalModelTranslation", source, input)
            assertEquals(expected, once)
            assertEquals(once, sanitize("sanitizeTraditionalModelTranslation", source, once))
        }
    }

    @Test fun fullDialogueAndRegionalNamesKeepTheirExistingSpelling() {
        val cases = listOf(
            Triple("あ、キリエちゃんもおかえりなさい。", "啊，基利艾醬也歡迎回來。", "啊，基利艾醬也歡迎回來。"),
            Triple("ダ・ヴィンチちゃん", "達·文西醬", "達·文西醬"),
            Triple("オベロンちゃん", "奧伯隆醬", "奧伯隆醬"),
            Triple("シオンちゃん", "紫苑醬", "紫苑醬"),
            Triple("シェヘラザードちゃん", "雪赫拉莎德醬", "雪赫拉莎德醬")
        )
        for ((source, input, expected) in cases) {
            assertEquals(expected, sanitize("sanitizeTraditionalModelTranslation", source, input))
        }
    }

    @Test fun literalRepetitionsAreNotGloballyCollapsed() {
        assertEquals("醬醬，這是你的名字。", sanitize("sanitizeTraditionalModelTranslation", "醬醬、それが君の名だ。", "醬醬，這是你的名字。"))
        assertEquals("酱酱，这是你的名字。", sanitize("sanitizeTranslation", "醬醬、それが君の名だ。", "酱酱，这是你的名字。"))
    }

    @Test fun officialTextMatchingUsesScriptEquivalenceWithoutRegionalNameSubstitution() {
        for ((hant, hans) in listOf("醬" to "酱", "寶" to "宝", "閣下" to "阁下", "萊納維貴蓋溫呂茲" to "莱纳维贵盖温吕兹")) {
            assertTrue(call("translatedContainsOfficialText", "達·文西$hant", hans) as Boolean, "$hant/$hans")
            assertTrue(call("translatedContainsOfficialText", "达·文西$hans", hant) as Boolean, "$hans/$hant")
        }
        assertFalse(call("translatedContainsOfficialText", "達·文西醬", "达·芬奇酱") as Boolean)
    }

    @Test fun conversionTableCoversCurrentChineseHonorificsAndReviewedGlossaryCharacters() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/assets/translation/traditional_glossary_overrides.tsv").isFile }
        val glossary = File(root, "app/src/main/assets/translation/traditional_glossary_overrides.tsv")
            .readLines(Charsets.UTF_8).drop(1).map { it.split('\t') }.filter { it.size >= 3 }.joinToString("") { it[2] }
        val source = File(root, "app/src/main/java/com/fgogotran/translation/Translator.kt").readText()
        val policies = source.substringAfter("private val wrongSanHonorificSuffixes")
            .substringBefore("private val malformedProtectedTokenPattern")
        val converter = Transliterator.getInstance("Hant-Hans")
        for (char in (glossary + policies).toSet().filter { Character.isIdeographic(it.code) }) {
            val expected = converter.transliterate(char.toString())
            assertEquals(expected, call("toSimplifiedChinese", char.toString()), "Missing script equivalence for $char")
        }
    }

    @Test fun existingTableMappingsHaveNoConflictsAndMatchIcuExceptRetainedYueVariant() {
        val field = Translator::class.java.getDeclaredField("traditionalToSimplified").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val table = field.get(translator) as Map<Char, Char>
        val converter = Transliterator.getInstance("Hant-Hans")
        for ((hant, hans) in table) {
            // Existing regional normalization is intentional; ICU leaves this glyph unchanged.
            if (hant == '嶽') assertEquals('岳', hans)
            else assertEquals(converter.transliterate(hant.toString()), hans.toString(), "$hant -> $hans")
        }
    }

    @Test fun englishCleanupDoesNotAddChineseHonorifics() {
        val english = "……Da Vinci-chan!?"
        assertEquals(english, sanitize("sanitizeEnglishModelTranslation", "……ダ・ヴィンチちゃん！？", english))
    }

    @Test fun bothChineseTargetsAlwaysUseOnePostprocessingVersion() {
        val material = listOf("prompt", "model", "source", "glossary")
        for (target in listOf("zh-Hans", "zh-CN", "zh-Hant", "zh-Hant-TW", "zh-Hant-HK")) {
            val updated = extendChinesePostprocessingCacheMaterial(material, target)
            assertEquals(material + CHINESE_POSTPROCESSING_CACHE_VERSION, updated)
            assertNotEquals(material, updated)
        }
        for (target in listOf("en", "en-US")) {
            assertSame(material, extendChinesePostprocessingCacheMaterial(material, target))
        }
    }

    @Test fun productionKeysRetireOldChineseEntriesRegardlessOfSourceHonorificOrModel() {
        for (fixture in preChangeKeys.filter { it.target != "en" }) {
            val updated = key(fixture.source, fixture.target, fixture.model)
            assertNotEquals(fixture.hash, updated.first, fixture.toString())
            assertTrue("postprocess=$CHINESE_POSTPROCESSING_CACHE_VERSION" in updated.second)
            assertFalse("honorific=" in updated.second)
        }
        for (source in listOf("マシュてゃ", "ダ・ヴィンチ殿", "ちゃんと聞いて。")) {
            for (target in listOf("zh-Hans", "zh-Hant")) {
                for (batch in listOf(false, true)) {
                    assertTrue("postprocess=$CHINESE_POSTPROCESSING_CACHE_VERSION" in
                        key(source, target, "deepseek-v4-flash", batch).second)
                }
            }
        }
    }

    @Test fun englishProductionHashesRetirePrePunctuationPolicyValues() {
        for (fixture in preChangeKeys.filter { it.target == "en" }) {
            for (target in listOf("en", "en-US")) {
                val updated = key(fixture.source, target, fixture.model)
                assertNotEquals(fixture.hash, updated.first, fixture.toString())
                assertFalse("postprocess=" in updated.second)
                assertFalse("honorific=" in updated.second)
            }
        }
    }

    @Test fun sharedVersionPreservesExistingGeneralTargetAndModelSeparation() {
        val source = "マシュさん"
        val hans = key(source, "zh-Hans", "deepseek-v4-flash").first
        val hant = key(source, "zh-Hant", "deepseek-v4-flash").first
        assertNotEquals(hans, hant)
        assertEquals(hans, key(source, "zh-CN", "deepseek-v4-flash").first)
        assertEquals(hant, key(source, "zh-Hant-TW", "deepseek-v4-flash").first)
        assertEquals(hant, key(source, "zh-Hant-HK", "deepseek-v4-flash").first)
        assertNotEquals(hans, key(source, "zh-Hans", "sakura-14b-qwen2.5-v1.0").first)
    }

    @Test fun newPolicyIgnoresBadOldKeysAndCorrectResultsHitRoomAndMemory() = runBlocking {
        val fixtures = preChangeKeys.filter { it.target != "en" && it.source == "……ダ・ヴィンチちゃん" }
        for (fixture in fixtures) {
            val source = fixture.source
            val target = fixture.target
            val updated = key(source, target, fixture.model)
            val bad = "……达·文西酱酱"
            invoke("removeMemoryCachedTranslation", updated.first)
            db.cacheDao().deleteByHash(updated.first)
            db.cacheDao().insert(CachedTranslation(jpTextHash = fixture.hash,
                jpText = source, normalizedJpText = source, cnText = bad))
            invoke("putMemoryCachedTranslation", fixture.hash, bad)
            val general = fixture.model == "deepseek-v4-flash"
            suspend fun lookup(): Any? = invokeSuspending("lookupCachedTranslation",
                updated.first, source, "", "offline-test", updated.second, target, general, 0)
            assertNull(lookup(), "The new key must not fall back to a retired key")
            assertEquals(bad, db.cacheDao().getCached(fixture.hash), "Do not clear old rows")

            invokeSuspending("cacheTranslatedText", updated.first, source, source,
                "……達·文西醬", "offline-test", "", "offline-test", target, general, 0)
            val expected = "……达·文西酱"
            assertEquals(expected, db.cacheDao().getCached(updated.first))
            invoke("removeMemoryCachedTranslation", updated.first)
            assertEquals(expected, lookup(), "Room cache hit")
            db.cacheDao().deleteByHash(updated.first)
            assertEquals(expected, lookup(), "Memory cache hit after the Room row is removed")
            assertEquals(if (target == "zh-Hant") "……達·文西醬" else expected,
                call("toTargetChinese", expected, target))
            assertEquals(bad, db.cacheDao().getCached(fixture.hash))
            invoke("removeMemoryCachedTranslation", updated.first)
        }
    }

    private data class PreChangeKey(val model: String, val target: String, val source: String, val hash: String)

    // Captured from production cacheKey() before this postprocessing-policy change; no API calls.
    private val preChangeKeys = """
        deepseek-v4-flash|zh-Hans|……ダ・ヴィンチちゃん|60481b77f79304a61fc35b62fce33cc748a2faf1dfd0fb8e8cb9d917e7005bd0
        deepseek-v4-flash|zh-Hans|マシュさん|9554f70fd1a2d0d758e43fb07f7325d08efeb9c2b49897191210f868d34d78c3
        deepseek-v4-flash|zh-Hans|お帰りなさい。|6c947a27d358bf34a77e6b6ca0fab20ee0f943c7c9957f476dc4c7902fd3aff6
        deepseek-v4-flash|zh-Hant|……ダ・ヴィンチちゃん|0674dee1ca3c1fe7bb6caa1e403b5ace17c586a775ff1081cae446ffaaa3a605
        deepseek-v4-flash|zh-Hant|マシュさん|d188ed36acc0de4aa7cd0a6fbdc5e30eafa792d2124eeac1a7a02592612aca97
        deepseek-v4-flash|zh-Hant|お帰りなさい。|06b9e4858de54b971619d31010dba104968a9e9e570a25f022230396cd6097ff
        deepseek-v4-flash|en|……ダ・ヴィンチちゃん|f9477da7b447e70131f15e4540a0ba8a2ffdbea2cf3cb3a07ac681f8f93f443e
        deepseek-v4-flash|en|マシュさん|785fa4b9aa1c0441ab21fff83d88f2feb76bf270a98539983d1564f211dd9a91
        deepseek-v4-flash|en|お帰りなさい。|2ac4594f97b94b7559ee00c06578773f20bd809545b3eb3b595397c5b76716b3
        sakura-14b-qwen2.5-v1.0|zh-Hans|……ダ・ヴィンチちゃん|d98a03c7e63bd58c1cb0f5166263b10b1d8cce8e31f16cb489fb6292f19cb413
        sakura-14b-qwen2.5-v1.0|zh-Hans|マシュさん|c0bbfc19260a1d3d80598bf706f14a07e43c48ad24e77f061112f881abab77cc
        sakura-14b-qwen2.5-v1.0|zh-Hans|お帰りなさい。|75cc39b8f43f9cc26f819a2329760cf7c6f379d34233933e752b68837b4dff15
        sakura-14b-qwen2.5-v1.0|zh-Hant|……ダ・ヴィンチちゃん|d98a03c7e63bd58c1cb0f5166263b10b1d8cce8e31f16cb489fb6292f19cb413
        sakura-14b-qwen2.5-v1.0|zh-Hant|マシュさん|c0bbfc19260a1d3d80598bf706f14a07e43c48ad24e77f061112f881abab77cc
        sakura-14b-qwen2.5-v1.0|zh-Hant|お帰りなさい。|75cc39b8f43f9cc26f819a2329760cf7c6f379d34233933e752b68837b4dff15
        sakura-14b-qwen2.5-v1.0|en|……ダ・ヴィンチちゃん|da1ae34034b729f850c1c284c0a64f4feca7e91ba43e5b181c63b5f63f879d63
        sakura-14b-qwen2.5-v1.0|en|マシュさん|a2b258f68cbe405b0704f55bcf40cf2fd776767a7b5e9fd22ef6989e22c7307d
        sakura-14b-qwen2.5-v1.0|en|お帰りなさい。|7e3af48115b6a5c40da822afb586d331d3be10a3fe52fb4c0f8a27f6d52970a5
    """.trimIndent().lines().map { line ->
        val columns = line.split('|')
        PreChangeKey(columns[0], columns[1], columns[2], columns[3])
    }

    @Test fun rawPauseLengthsRemainDistinctEvenWhenNormalizedTextMatches() {
        for (target in listOf("zh-Hans", "zh-Hant", "en")) {
            assertNotEquals(key("───待て", target, "test-model", rawSource = "---待て").first,
                key("───待て", target, "test-model", rawSource = "------待て").first)
        }
    }

    private fun key(source: String, target: String, model: String, choiceBatch: Boolean = false, rawSource: String = source): Pair<String, String> {
        val configType = Translator::class.java.declaredClasses.single { it.simpleName == "RuntimeConfig" }
        val constructor = configType.declaredConstructors.single().apply { isAccessible = true }
        val config = constructor.newInstance("deepseek", "", "https://offline.invalid/v1", model,
            "", "", true, target, "", ApiSamplingSettings(SettingsRepository.API_SAMPLING_MODE_AUTO, false, 1.0, false, 1.0),
            com.fgogotran.data.TranslationRequestOptions())
        val value = requireNotNull(invoke("cacheKey", source, emptyList<String>(), config,
            "", "", "", "", choiceBatch, "", "", "", "", rawSource))
        fun field(name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as String
        return field("hash") to field("parts")
    }

    private fun invoke(name: String, vararg args: Any?): Any? = Translator::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(translator, *args)

    private suspend fun invokeSuspending(name: String, vararg args: Any?): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            Translator::class.java.declaredMethods
                .single { it.name == name && it.parameterCount == args.size + 1 }
                .apply { isAccessible = true }.invoke(translator, *args, continuation)
        }

    private fun sanitize(method: String, source: String, translated: String) = call(method, source, translated) as String

    private fun call(name: String, vararg args: String): Any? = Translator::class.java
        .getDeclaredMethod(name, *Array(args.size) { String::class.java })
        .apply { isAccessible = true }.invoke(translator, *args)

    private fun <T : Any> unusedDao(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)
    ) { _, method, _ -> error("Unexpected DAO access: ${method.name}") }))
}
