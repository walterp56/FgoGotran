package com.fgogotran.translation

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Offline fixtures call production general builders, never a provider or the Sakura builder. */
class GeneralPromptEvaluationFixtureTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "tools/translation_eval/cases.json").isFile }
    private val casesFile = System.getenv("FGO_TRANSLATION_EVAL_CASES")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(root, "tools/translation_eval/cases.json")
    private val fixture = Json.parseToJsonElement(casesFile.readText(Charsets.UTF_8)).jsonObject
    private val cases = fixture.getValue("cases").jsonArray.map(JsonElement::jsonObject)
    private val targets = fixture.getValue("targets").jsonArray.map { it.jsonPrimitive.content }
    private val builder = PromptBuilder()
    private val profileIndex = File(root, "app/src/main/assets/translation/character_context_prompts.tsv")
        .useLines(Charsets.UTF_8) { buildCharacterContextIndex(parseCharacterContextProfiles(it)) }

    @Test
    fun `export production general prompt pairs for every fixture and target`() {
        assertEquals(listOf("zh-Hans", "zh-Hant", "en"), targets)
        assertEquals(cases.size, cases.map { it.text("id") }.distinct().size)
        assertTrue(cases.all { it.getValue("checks").jsonArray.isNotEmpty() })
        val requests = requests()
        assertEquals(cases.size * targets.size, requests.size)
        val variant = System.getenv("FGO_TRANSLATION_EVAL_VARIANT")?.trim().orEmpty().ifEmpty { "current" }
        val artifact = buildJsonObject {
            put("schema_version", 1)
            put("scope", "general_non_sakura")
            put("variant", variant)
            put("fixture_sha256", sha256(casesFile.readBytes()))
            put("assembly_scope", "Production general single/batch message builders; prepared fixture glossary/context, not live OCR, DB lookup, Android ICU or provider transport.")
            put("source_sha256", buildJsonObject {
                for (relative in listOf(
                    "app/src/main/java/com/fgogotran/translation/PromptBuilder.kt",
                    "app/src/main/java/com/fgogotran/translation/GeneralPromptRequests.kt",
                    "app/src/main/java/com/fgogotran/translation/GeneralSceneContext.kt",
                    "app/src/main/java/com/fgogotran/translation/GeneralTranslationResponseContract.kt",
                    "app/src/main/java/com/fgogotran/translation/TranslationGlossaryBuilder.kt",
                    "app/src/main/java/com/fgogotran/translation/Translator.kt",
                    "app/src/main/java/com/fgogotran/translation/CharacterContextRepository.kt",
                    "app/src/main/java/com/fgogotran/translation/GeneralCharacterContext.kt",
                    "app/src/main/assets/translation/character_context_prompts.tsv",
                    "term_builder/term.tsv", "term_builder/character_names.tsv"
                )) put(relative, sha256(File(root, relative).readBytes()))
            })
            put("requests", JsonArray(requests))
            put("scene_system_checks", JsonArray(targets.map(::sceneSystemCheck)))
        }
        val customOutput = System.getenv("FGO_TRANSLATION_EVAL_EXPORT")?.takeIf(String::isNotBlank)
        val destination = customOutput?.let(::File)
            ?: File(root, "app/build/reports/translation-eval/prompts.json")
        if (customOutput != null) require(!destination.exists()) { "Use a new explicit export path" }
        destination.parentFile?.mkdirs()
        destination.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), artifact) + "\n", Charsets.UTF_8)
    }

    private fun requests(): List<JsonObject> = cases.flatMap { case -> targets.map { target ->
        val mode = case.text("mode")
        val rows = case["rows"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val source = case.text("source").ifEmpty { rows.joinToString("\n") }
        val nameOnly = mode == "name"
        val choicesOnly = mode == "choices"
        val arrayOutput = choicesOnly || mode == "dialogue_rows"
        val speakerSource = case.text("speaker")
        val resolvedSpeaker = case["speaker_targets"]?.jsonObject?.text(target).orEmpty()
        val speaker = if (resolvedSpeaker.isNotEmpty() && resolvedSpeaker != speakerSource) {
            "$resolvedSpeaker (JP: $speakerSource)"
        } else speakerSource
        val dialogue = !nameOnly && !choicesOnly
        val profile = profileIndex.profilesByAlias[normalizeCharacterContextSpeakerName(speakerSource)]
            .takeIf { dialogue }
        val characterPrompt = profile?.let { prepareGeneralCharacterContext(it, source, target).prompt }.orEmpty()
        val context = builder.buildPromptContext(
            outputFormat = if (arrayOutput) PromptOutputFormat.JSON_ARRAY else PromptOutputFormat.PLAIN_TEXT,
            sourceText = source,
            choiceTexts = if (choicesOnly) rows else emptyList(),
            targetLanguage = target,
            hasName = nameOnly,
            nameText = source.takeIf { nameOnly },
            isDialogue = dialogue,
            playerName = case.text("player_name"),
            playerGender = case.text("player_gender").ifEmpty { "male" },
            playerReferenceText = source,
            currentSpeaker = speaker,
            characterContextPrompt = characterPrompt,
            isChoiceBatch = choicesOnly
        )
        val matched = case["glossary"]?.jsonArray?.map { entry ->
            val value = entry.jsonObject
            TranslationGlossaryEntry(value.text("source"), value.getValue("targets").jsonObject.text(target),
                value["notes"]?.jsonObject?.text(target).orEmpty())
        }.orEmpty()
        val glossary = TranslationGlossaryBuilder.build(source, context, matched, speaker, includeCurrentSpeaker = false)
        val previous = case["previous"]?.jsonArray?.mapIndexed { index, entry ->
            val value = entry.jsonObject
            SceneDialogueContext(
                sourceSpeakerName = value.text("speaker"), translatedSpeakerName = value.text("speaker"),
                sourceDialogue = value.text("source"),
                translatedDialogue = value.getValue("translations").jsonObject.text(target),
                targetLocale = target, dialogueSourceKey = "fixture-$index",
                generalPreparedSourceDialogue = value.text("source")
            )
        }.orEmpty()
        val preparedPrevious = prepareGeneralSceneDialogueContexts(
            previous, target, { it.trim() }, { it.trim() }, { text, _ -> text }
        )
        val system = builder.buildSystemPrompt(context)
        val user = if (arrayOutput) {
            buildGeneralBatchUserPrompt(rows, speaker, choicesOnly, glossary, preparedPrevious)
        } else {
            buildGeneralSingleUserPrompt(builder, source, emptyList(), preparedPrevious, speaker,
                translateAsName = nameOnly, glossaryEntries = glossary)
        }
        if (characterPrompt.isNotEmpty()) assertTrue(system.contains(characterPrompt))
        if (!dialogue && profileIndex.profilesByAlias.containsKey(speakerSource)) {
            assertFalse(system.contains(profileIndex.profilesByAlias.getValue(speakerSource).generalPrompt))
            assertFalse(system.contains(profileIndex.profilesByAlias.getValue(speakerSource).englishPrompt))
        }
        if (case.text("id") == "ruby_and_fragment") assertTrue(context.hasRuby)
        if (case.text("id") == "configured_master_female") assertEquals("female", context.playerGender)
        if (case.text("id") == "configured_master_male") assertEquals("male", context.playerGender)
        if (!arrayOutput) assertTrue(user.contains(source))
        if (case.text("id") in setOf("new_speaker_after_tic", "summon_without_name")) {
            assertTrue(characterPrompt.isEmpty())
            assertTrue(user.contains("Previous translations are fallible reference"))
        }
        buildJsonObject {
            put("id", "${case.text("id")}:$target")
            put("case_id", case.text("id"))
            put("target_language", target)
            put("mode", mode)
            put("prompt_version", PromptBuilder.promptVersionFor(target, battleSubtitle = false))
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("prompt_sha256", sha256("system\u001F$system\u001Euser\u001F$user".toByteArray(Charsets.UTF_8)))
            put("contract", buildJsonObject {
                put("kind", if (arrayOutput) "json_array" else "plain_text")
                put("count", if (arrayOutput) rows.size else 1)
            })
            put("input", JsonObject(case.filterKeys { it !in setOf("id", "checks") }))
            put("checks", case.getValue("checks"))
        }
    } }

    private fun sceneSystemCheck(target: String): JsonObject {
        val context = builder.buildPromptContext(
            PromptOutputFormat.JSON_OBJECT, "まだ、まだ……！", listOf("行こう。", "待とう。"),
            targetLanguage = target, hasName = true, nameText = "マシュ", isDialogue = true
        )
        assertTrue(context.hasChoices && context.hasName && context.isDialogue)
        val system = builder.buildSystemPrompt(context)
        assertTrue(system.isNotBlank())
        val accepted = GeneralTranslationResponseContract.parseScene(
            Json.parseToJsonElement("""{"name":"Name","dialogue":"Still... still!","choices":["Go.","Wait."]}""").jsonObject,
            expectName = true, expectDialogue = true, expectedChoiceCount = 2
        )
        assertEquals(2, accepted.choices.size)
        assertFailsWith<IllegalArgumentException> {
            GeneralTranslationResponseContract.parseScene(
                Json.parseToJsonElement("""{"name":"Name","dialogue":"Text","choices":["Only one"]}""").jsonObject,
                expectName = true, expectDialogue = true, expectedChoiceCount = 2
            )
        }
        return buildJsonObject {
            put("target_language", target)
            put("assembly_scope", "System prompt and production response contract only. Translator's private scene user builder is not exported or copied.")
            put("system", system)
        }
    }

    @Test
    fun `validate imported recorded outputs with production contracts when explicitly provided`() {
        val input = System.getenv("FGO_TRANSLATION_EVAL_RESPONSES")?.takeIf(String::isNotBlank) ?: return
        val inputFile = File(input)
        val document = Json.parseToJsonElement(inputFile.readText(Charsets.UTF_8)).jsonObject
        assertEquals(1, document.getValue("schema_version").jsonPrimitive.int)
        assertEquals(sha256(casesFile.readBytes()), document.text("fixture_sha256"))
        val contracts = requests().associate { it.text("id") to it.getValue("contract").jsonObject }
        val runs = document.getValue("runs").jsonArray
        val ids = runs.map { it.jsonObject.text("id") }
        assertTrue(ids.all(String::isNotBlank) && ids.distinct().size == ids.size && ids.isNotEmpty())
        val results = runs.map { item ->
            val run = item.jsonObject
            val contract = contracts.getValue(run.text("request_id"))
            val attempts = run.getValue("attempts").jsonArray
            assertTrue(attempts.isNotEmpty())
            val checked = attempts.map { attempt ->
                val response = attempt.jsonObject.getValue("response").jsonPrimitive
                require(response.isString) { "Response content must be a string" }
                val outcome = runCatching {
                    if (contract.text("kind") == "json_array") {
                        GeneralTranslationResponseContract.parseStrings(
                            Json.parseToJsonElement(response.content), contract.getValue("count").jsonPrimitive.int
                        )
                    } else require(response.content.isNotBlank()) { "Plain translation must not be blank" }
                }
                buildJsonObject {
                    put("valid", outcome.isSuccess)
                    put("error", outcome.exceptionOrNull()?.message?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
            buildJsonObject {
                put("id", run.text("id"))
                put("attempts", JsonArray(checked))
                put("final_valid", checked.last().getValue("valid"))
            }
        }
        val destination = File(root, "app/build/reports/translation-eval/validation.json")
        destination.parentFile?.mkdirs()
        destination.writeText(buildJsonObject {
            put("schema_version", 1)
            put("responses_sha256", sha256(inputFile.readBytes()))
            put("validator", "Production GeneralTranslationResponseContract; plain text checks nonblank only")
            put("runs", JsonArray(results))
        }.toString() + "\n", Charsets.UTF_8)
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
