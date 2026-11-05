package com.fgogotran.translation

import android.icu.text.Transliterator
import androidx.room.Room
import com.fgogotran.data.SettingsRepository
import com.fgogotran.data.UserProfile
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.terminology.TermDao
import io.ktor.client.HttpClient
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
            TranslationMemory(app), DiagnosticEventStore(app), app)
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

    @Test fun onlyChineseRequestsContainingChanBypassTheOldCache() {
        val old = listOf("prompt", "model", "source", "glossary")
        for (target in listOf("zh-Hans", "zh-Hant", "zh-Hant-TW")) {
            for (sources in listOf(listOf("……ダ・ヴィンチちゃん"), listOf("本文", "ダ・ヴィンチちゃん"))) {
                val updated = extendChineseHonorificCacheMaterial(old, sources, target)
                assertNotEquals(old, updated)
                assertEquals(old, updated.dropLast(1))
                assertEquals("chinese-chan-script-v1", updated.last())
            }
            assertSame(old, extendChineseHonorificCacheMaterial(old, listOf("マシュ", "ダ・ヴィンチ"), target))
        }
        assertSame(old, extendChineseHonorificCacheMaterial(old, listOf("ダ・ヴィンチちゃん"), "en"))
        assertSame(old, extendChineseHonorificCacheMaterial(old, listOf("ダ・ヴィンチちゃん"), "en-US"))
    }

    private fun sanitize(method: String, source: String, translated: String) = call(method, source, translated) as String

    private fun call(name: String, vararg args: String): Any? = Translator::class.java
        .getDeclaredMethod(name, *Array(args.size) { String::class.java })
        .apply { isAccessible = true }.invoke(translator, *args)

    private fun <T : Any> unusedDao(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)
    ) { _, method, _ -> error("Unexpected DAO access: ${method.name}") }))
}
