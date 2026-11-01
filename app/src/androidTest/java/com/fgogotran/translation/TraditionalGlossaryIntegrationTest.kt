package com.fgogotran.translation

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.fgogotran.MainActivity
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.LocalCharacterNameEntity
import com.fgogotran.terminology.LocalGlossaryDao
import com.fgogotran.terminology.TermDao
import com.fgogotran.terminology.TermEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Inserts fixtures only on an explicitly opted-in disposable emulator. Never runs on a phone. */
class TraditionalGlossaryIntegrationTest {
    @Test fun actualLookupRagIcuAndLanguageSwitchingKeepTheirBoundaries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("traditionalGlossary") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val repo = activity.settingsRepository
        val translator = activity.translator
        fun <T> field(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return Translator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(translator) as T
        }
        val dao = field<TermDao>("termDao")
        val localDao = field<LocalGlossaryDao>("localGlossaryDao")
        runBlocking {
            val oldTarget = repo.targetLanguage.first()
            val oldProfile = repo.playerProfile.first()
            val oldBackend = repo.translationBackend.first()
            val oldKey = repo.getApiKeyForBackend(oldBackend)
            val oldUrl = repo.getApiBaseUrlForBackend(oldBackend)
            val oldModel = repo.getApiModelForBackend(oldBackend)
            val backend = SettingsRepository.BACKEND_DEEPSEEK
            suspend fun model(value: String) = repo.saveApiSettings(backend, "",
                SettingsRepository.defaultApiBaseUrl(backend), value)
            val terms = listOf(TermEntity(jpTerm = "シオン", cnTerm = "希翁", enTerm = "Sion", category = "character",
                aliases = "[\"明示された別名\"]"),
                TermEntity(jpTerm = "オベロン", cnTerm = "奥伯龙", enTerm = "Oberon", category = "character"),
                TermEntity(jpTerm = "魔術", cnTerm = "魔术", enTerm = "Magecraft", category = "game_term"),
                TermEntity(jpTerm = "オリュンポス", cnTerm = "奥林波斯", enTerm = "Olympus", category = "place"),
                TermEntity(jpTerm = "無限の剣製", cnTerm = "无限剑制", enTerm = "Unlimited Blade Works", category = "noble_phantasm"),
                TermEntity(jpTerm = "ルールブレイカー", cnTerm = "万符必应破戒", enTerm = "Rule Breaker", category = "noble_phantasm",
                    aliases = "[\"破戒すべき全ての符\"]"))
            try {
                repo.setPlayerProfile("", SettingsRepository.PLAYER_GENDER_UNSPECIFIED)
                model("deepseek-chat")
                dao.upsertTerms(terms)
                dao.upsertCharacterNames(listOf(
                    CharacterNameEntity(jpName = "シオン", cnName = "希翁", enName = "Sion"),
                    CharacterNameEntity(jpName = "オベロン", cnName = "奥伯龙", enName = "Oberon"),
                    CharacterNameEntity(jpName = "魔神グシオン", cnName = "魔神古辛", enName = "Gusion"),
                    CharacterNameEntity(jpName = "シオン・エルトナム・ソカリス", cnName = "希翁·艾尔特纳姆·索卡里斯",
                        enName = "Sion Eltnam Sokaris", aliases = "[\"シオンエルトナムソカリス\"]")))
                translator.clearGlossaryCache()
                repo.setTargetLanguage("zh-Hant")
                assertEquals("紫苑", translator.translate("シオン", translateAsName = true).translatedText)
                val full = "紫苑·艾爾特納姆·索卡里斯"
                assertEquals(full, translator.translate("シオンエルトナムソカリス", translateAsName = true).translatedText)
                assertEquals(full, translator.translate("シオンエルトナムソカリス").translatedText)
                assertEquals("紫苑", translator.translate("明示された別名").translatedText)
                assertEquals("魔神古辛", translator.translate("魔神グシオン", translateAsName = true).translatedText)
                assertEquals("奧林帕斯", translator.translate("オリュンポス").translatedText)
                assertEquals("無限劍製", translator.translate("無限の剣製").translatedText)
                assertEquals("萬符必行破戒", translator.translate("破戒すべき全ての符").translatedText)
                assertEquals(listOf("紫苑", "奧伯隆", "魔術"),
                    translator.translateBatch(listOf("シオン", "オベロン", "魔術")).map { it.translatedText })
                val scene = translator.translateScene(SceneTranslateInput("シオン", "魔術", listOf("オベロン")))
                assertEquals("紫苑", scene.name?.translatedText)
                assertEquals("魔術", scene.dialogue?.translatedText)
                assertEquals("奧伯隆", scene.choices.single().translatedText)
                val termScene = translator.translateScene(SceneTranslateInput("シオン", "無限の剣製", listOf("オリュンポス")))
                assertEquals("無限劍製", termScene.dialogue?.translatedText)
                assertEquals("奧林帕斯", termScene.choices.single().translatedText)
                assertEquals(listOf("奧林帕斯", "無限劍製", "萬符必行破戒"), translator.translateBatch(
                    listOf("オリュンポス", "無限の剣製", "破戒すべき全ての符")).map { it.translatedText })

                // Exercise the actual prepared RAG targets without issuing a network request.
                val rag = Translator::class.java.getDeclaredMethod("buildMatchedGlossaryEntries", String::class.java,
                    List::class.java, String::class.java, String::class.java, String::class.java, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val entries = rag.invoke(translator, "シオンとオベロン。", terms.take(2), "", "unknown", "zh-Hant", true)
                    as List<TranslationGlossaryEntry>
                assertEquals(mapOf("シオン" to "紫苑", "オベロン" to "奧伯隆"), entries.associate { it.source to it.target })
                val fullTerm = TermEntity(jpTerm = "シオン・エルトナム・ソカリス",
                    cnTerm = "希翁·艾尔特纳姆·索卡里斯", category = "character")
                @Suppress("UNCHECKED_CAST")
                val fullEntries = rag.invoke(translator, fullTerm.jpTerm, listOf(fullTerm), "", "unknown", "zh-Hant", true)
                    as List<TranslationGlossaryEntry>
                assertEquals(full, fullEntries.single().target)
                @Suppress("UNCHECKED_CAST")
                val termEntries = rag.invoke(translator, "オリュンポス、無限の剣製、破戒すべき全ての符。",
                    terms.drop(3), "", "unknown", "zh-Hant", true) as List<TranslationGlossaryEntry>
                assertEquals(mapOf("オリュンポス" to "奧林帕斯", "無限の剣製" to "無限劍製",
                    "破戒すべき全ての符" to "萬符必行破戒"), termEntries.associate { it.source to it.target })
                val targetTerm = Translator::class.java.getDeclaredMethod("targetTermText", TermEntity::class.java,
                    String::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
                val cleanName = Translator::class.java.getDeclaredMethod("sanitizeVisibleCharacterNameResult", String::class.java)
                    .apply { isAccessible = true }
                val convert = Translator::class.java.getDeclaredMethod("toTraditionalChinese", String::class.java)
                    .apply { isAccessible = true }
                context.assets.open(TRADITIONAL_GLOSSARY_ASSET).bufferedReader(Charsets.UTF_8).useLines { rows ->
                    rows.drop(1).filter(String::isNotBlank).forEach { row ->
                        val (source, reference, expected) = row.split('\t')
                        val term = TermEntity(jpTerm = source, cnTerm = reference, category = "character")
                        assertEquals(source, expected, targetTerm.invoke(translator, term, "zh-Hant", true))
                        if (source !in terms.drop(3).map { it.jpTerm }) {
                            assertEquals(source, expected, convert.invoke(translator, cleanName.invoke(translator, expected)))
                        }
                        assertEquals(source, expected, FgoDialogueSymbols.normalizeTranslatedPunctuation(expected))
                    }
                }
                assertEquals("希翁", dao.findExactCharacterName("シオン"))

                repo.setTargetLanguage("zh-Hans")
                assertEquals("希翁", translator.translate("シオン", translateAsName = true).translatedText)
                assertEquals("万符必应破戒", translator.translate("破戒すべき全ての符").translatedText)
                repo.setTargetLanguage("en")
                assertEquals("Sion", translator.translate("シオン", translateAsName = true).translatedText)
                assertEquals("Rule Breaker", translator.translate("破戒すべき全ての符").translatedText)
                repo.setTargetLanguage("zh-Hant")
                model("sakura")
                assertEquals("奧伯龍", translator.translate("オベロン", translateAsName = true).translatedText)
                assertEquals("萬符必應破戒", translator.translate("破戒すべき全ての符").translatedText)
                model("deepseek-chat")
                assertEquals("奧伯隆", translator.translate("オベロン", translateAsName = true).translatedText)

                localDao.upsertCharacterName(LocalCharacterNameEntity("test-hant-local", "シオン", "希翁"))
                translator.clearGlossaryCache()
                assertEquals("希翁", translator.translate("シオン", translateAsName = true).translatedText)
                localDao.deleteCharacterName("test-hant-local")
                dao.upsertCharacterNames(listOf(CharacterNameEntity(jpName = "シオン", cnName = "另一译名", enName = "Sion")))
                translator.clearGlossaryCache()
                assertEquals("另一譯名", translator.translate("シオン", translateAsName = true).translatedText)
            } finally {
                localDao.deleteCharacterName("test-hant-local")
                repo.setTargetLanguage(oldTarget)
                repo.setPlayerProfile(oldProfile.name, oldProfile.gender)
                repo.saveApiSettings(oldBackend, oldKey, oldUrl,
                    oldModel.ifBlank { SettingsRepository.defaultApiModel(oldBackend) })
                translator.clearGlossaryCache()
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
