package com.fgogotran.translation

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Local ships these production-built requests; Python never copies prompt rules. */
class LocalConnectionPromptFixtureTest {
    @Test
    fun `local connection requests match the production general builders`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "FgoGotranLocal/pyproject.toml").isFile }
        val translator = File(root, "app/src/main/java/com/fgogotran/translation/Translator.kt").readText()
        val source = Regex("API_TEST_JAPANESE_TEXT = \"([^\"]+)\"").find(translator)!!.groupValues[1]
        val terms = File(root, "term_builder/term.tsv").readLines(Charsets.UTF_8)
        val columns = terms.first().split('\t')
        val chaldea = terms.drop(1).map { it.split('\t') }.single { it[0] == "カルデア" }
        val builder = PromptBuilder()
        val requests = buildJsonObject {
            for (target in listOf("zh-Hans", "zh-Hant", "en")) {
                val context = builder.buildPromptContext(
                    outputFormat = PromptOutputFormat.PLAIN_TEXT,
                    sourceText = source,
                    targetLanguage = target,
                    playerName = "",
                    playerReferenceText = source,
                )
                // This fixture's Chinese place name has identical simplified/traditional spelling.
                val place = chaldea[columns.indexOf(if (target == "en") "en_term" else "cn_term")]
                assertEquals(if (target == "en") "Chaldea" else "迦勒底", place)
                val glossary = TranslationGlossaryBuilder.build(
                    source, context, listOf(TranslationGlossaryEntry("カルデア", place)),
                    currentSpeaker = "", includeCurrentSpeaker = false,
                )
                val system = builder.buildSystemPrompt(context)
                val user = buildGeneralSingleUserPrompt(builder, source, emptyList(), glossaryEntries = glossary)
                assertTrue(user.contains(source) && user.contains(place))
                put(target, buildJsonObject {
                    put("promptVersion", PromptBuilder.promptVersionFor(target, battleSubtitle = false))
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", system) })
                        add(buildJsonObject { put("role", "user"); put("content", user) })
                    })
                })
            }
        }
        val document = buildJsonObject {
            put("schemaVersion", 1)
            put("sourceText", source)
            put("scope", "Production general plain-text builders with prepared glossary; no live OCR, database lookup, or Sakura prompt.")
            put("sourceSha256", buildJsonObject {
                for (name in listOf("PromptBuilder.kt", "GeneralPromptRequests.kt", "TranslationGlossaryBuilder.kt", "Translator.kt")) {
                    val relative = "app/src/main/java/com/fgogotran/translation/$name"
                    put(relative, sourceHash(File(root, relative)))
                }
                put("term_builder/term.tsv", sourceHash(File(root, "term_builder/term.tsv")))
            })
            put("requests", requests)
        }
        val destination = File(root, "FgoGotranLocal/src/fgogotran_local/connection_prompts.json")
        if (System.getenv("FGO_LOCAL_EXPORT_PROMPTS") == "1") {
            destination.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document) + "\n", Charsets.UTF_8)
        }
        assertTrue(destination.isFile, "Export first with FGO_LOCAL_EXPORT_PROMPTS=1")
        assertEquals(document, Json.parseToJsonElement(destination.readText(Charsets.UTF_8)),
            "Local connection prompts are stale; regenerate with FGO_LOCAL_EXPORT_PROMPTS=1")
    }

    private fun sourceHash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
