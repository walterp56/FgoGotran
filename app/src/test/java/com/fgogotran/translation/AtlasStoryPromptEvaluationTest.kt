package com.fgogotran.translation

import android.icu.text.Transliterator
import com.fgogotran.text.EmphasisText
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Optional offline Atlas export: no network, credentials, oracle translations or Sakura builders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AtlasStoryPromptEvaluationTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java/com/fgogotran/translation/PromptBuilder.kt").isFile }
    private val builder = PromptBuilder()

    @Test fun exportExactGeneralWireRequestsWhenFixtureIsExplicitlyProvided() {
        val fixturePath = System.getenv("FGO_ATLAS_EVAL_INPUT")?.takeIf(String::isNotBlank) ?: return
        val outputPath = requireNotNull(System.getenv("FGO_ATLAS_EVAL_OUTPUT"))
        val file = File(fixturePath)
        val fixture = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        val profiles = File(root, "app/src/main/assets/translation/character_context_prompts.tsv")
            .useLines(Charsets.UTF_8) { buildCharacterContextIndex(parseCharacterContextProfiles(it)) }
        val overrides = File(root, "app/src/main/assets/translation/traditional_glossary_overrides.tsv")
            .useLines(Charsets.UTF_8) { TraditionalGlossaryOverrides.parse(it) }
        val converter = Transliterator.getInstance("Hans-Hant")
        val productionOnly = System.getenv("FGO_ATLAS_EVAL_PRODUCTION_ONLY") == "true"
        val variants = if (productionOnly) listOf("production") else listOf("baseline", "candidate")
        val destination = File(outputPath)
        require(!destination.exists()) { "Choose a new immutable export directory" }
        destination.mkdirs()
        for (variant in variants) {
            val requests = fixture.getValue("cases").jsonArray.flatMap { element ->
                val case = element.jsonObject
                fixture.getValue("targets").jsonArray.map { t ->
                    val target = t.jsonPrimitive.content
                    val source = fromWire(case.text("source"))
                    val rows = case["rows"]?.jsonArray?.map { fromWire(it.jsonPrimitive.content) }.orEmpty()
                    val choices = case.text("mode") == "choices"
                    val speaker = case.text("speaker")
                    val profile = profiles.profilesByAlias[normalizeCharacterContextSpeakerName(speaker)]
                        .takeUnless { choices }
                    val prepared = profile?.let { prepareGeneralCharacterContext(it, source, target) }
                    val context = builder.buildPromptContext(
                        outputFormat = if (choices) PromptOutputFormat.JSON_ARRAY else PromptOutputFormat.PLAIN_TEXT,
                        sourceText = source.ifEmpty { rows.joinToString("\n") },
                        choiceTexts = if (choices) rows else emptyList(), targetLanguage = target,
                        isDialogue = !choices, currentSpeaker = speaker,
                        characterContextPrompt = prepared?.prompt.orEmpty(), isChoiceBatch = choices,
                        playerName = "九条京香", playerGender = "male"
                    )
                    val matches = case["glossary"]?.jsonArray?.map { term ->
                        val entry = term.jsonObject
                        val jp = entry.text("source")
                        val cn = entry.text("cn_reference")
                        val translated = when (target) {
                            "en" -> entry.text("en")
                            "zh-Hant" -> overrides.findTarget(listOf(jp), cn) ?: converter.transliterate(cn)
                            else -> cn
                        }
                        TranslationGlossaryEntry(jp, translated)
                    }.orEmpty()
                    val glossary = TranslationGlossaryBuilder.build(
                        source.ifEmpty { rows.joinToString("\n") }, context, matches, speaker,
                        includeCurrentSpeaker = false
                    )
                    val original = builder.buildSystemPrompt(context)
                    val system = if (variant == "candidate") candidate(original, target) else original
                    val user = if (choices) buildGeneralBatchUserPrompt(rows, speaker, true, glossary)
                    else buildGeneralSingleUserPrompt(builder, source, emptyList(), currentSpeaker = speaker,
                        glossaryEntries = glossary)
                    val messages = listOf(ChatMessage("system", system), ChatMessage("user", user))
                        .map { it.copy(content = EmphasisText.wire(it.content)) }
                    buildJsonObject {
                        put("id", "${case.text("id")}:$target")
                        put("case_id", case.text("id")); put("target_language", target)
                        put("mode", if (choices) "choices" else "dialogue")
                        put("prompt_version", PromptBuilder.promptVersionFor(target, false))
                        put("messages", buildJsonArray { messages.forEach { message ->
                            add(buildJsonObject { put("role", message.role); put("content", message.content) })
                        } })
                        put("prompt_sha256", sha256(messages.joinToString("\u001E") { "${it.role}\u001F${it.content}" }.toByteArray(Charsets.UTF_8)))
                        put("max_tokens", if (choices) 384 else 256)
                        put("contract", buildJsonObject { put("kind", if (choices) "json_array" else "plain_text"); put("count", if (choices) rows.size else 1) })
                        put("input", buildJsonObject {
                            put("source", EmphasisText.wire(source)); put("speaker", speaker)
                            put("rows", JsonArray(rows.map { JsonPrimitive(EmphasisText.wire(it)) }))
                            put("context_policy", "previous-scene reference disabled; source-visible identity only")
                            put("glossary", JsonArray(glossary.map { g -> buildJsonObject {
                                put("source", g.source); put("target", g.target); put("note", g.note)
                            } }))
                        })
                        put("checks", case.getValue("checks"))
                    }
                }
            }
            val result = buildJsonObject {
                put("schema_version", 1); put("scope", "general_non_sakura"); put("variant", variant)
                put("fixture_sha256", sha256(file.readBytes()))
                put("assembly_scope", "Production dialogue-only / choices-only general builders and final emphasis wire encoding; prepared frozen TSV hits with Android ICU and general overrides. No live OCR, DB/cache, sequential context or HTTP repair simulation.")
                put("source_sha256", buildJsonObject {
                    listOf("PromptBuilder.kt", "GeneralPromptRequests.kt", "GeneralScenePromptRequests.kt",
                        "GeneralSceneContext.kt", "TranslationGlossaryBuilder.kt", "Translator.kt").forEach { name ->
                        put(name, sha256(File(root, "app/src/main/java/com/fgogotran/translation/$name").readBytes()))
                    }
                })
                put("candidate_status", if (variant == "candidate") "proposed single core-sentence substitution; verify against compiled final before adoption" else "compiled production core")
                put("requests", JsonArray(requests))
            }
            File(destination, "$variant.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result), Charsets.UTF_8)
        }
    }

    @Test fun generalSceneBuildersKeepRequestedFieldsAndVoiceDeliverySeparate() {
        val scene = buildGeneralSceneUserPrompt("？？？", "誰だ？", emptyList(), false, true, true)
        assertTrue(scene.contains("Name JP (translate): ？？？"))
        assertTrue(scene.contains("choices=exactly 0"))
        assertTrue(scene.contains("name, dialogue, choices."))
        assertFalse(scene.contains("voice_hint"))
        val voice = buildGeneralDialogueWithVoiceHintUserPrompt("行きましょう。", VoiceHintContext(listOf("test-voice"), listOf("cheerful")))
        assertTrue(voice.contains("dialogue, voice_hint."))
        assertTrue(voice.contains("Voice(s): test-voice"))
        assertTrue(voice.contains("never change translation"))
        val contextOnly = buildGeneralSceneUserPrompt("マシュ", "待って！", listOf("うん。", "いや。"), false, false, false)
        assertTrue(contextOnly.contains("Name JP (context only): マシュ"))
        assertTrue(contextOnly.contains("Set name=null; dialogue=null; choices=exactly 2"))
    }

    @Test fun validateRecordedResponsesWithProductionContractsWhenProvided() {
        val responsePath = System.getenv("FGO_ATLAS_EVAL_RESPONSES")?.takeIf(String::isNotBlank) ?: return
        val responseFile = File(responsePath)
        val document = Json.parseToJsonElement(responseFile.readText(Charsets.UTF_8)).jsonObject
        val export = Json.parseToJsonElement(File(requireNotNull(System.getenv("FGO_ATLAS_EVAL_EXPORT_FOR_VALIDATION"))).readText(Charsets.UTF_8)).jsonObject
        assertEquals(export.text("fixture_sha256"), document.text("fixture_sha256"))
        val requests = export.getValue("requests").jsonArray.associate { it.jsonObject.text("id") to it.jsonObject }
        val results = document.getValue("runs").jsonArray.map { element ->
            val run = element.jsonObject
            val request = requests.getValue(run.text("request_id"))
            val contract = request.getValue("contract").jsonObject
            val attempts = run.getValue("attempts").jsonArray.map { attemptElement ->
                val attempt = attemptElement.jsonObject
                val response = attempt.text("response")
                val outcome = runCatching {
                    val fields = if (contract.text("kind") == "json_array") {
                        GeneralTranslationResponseContract.parseStrings(Json.parseToJsonElement(response), contract.getValue("count").jsonPrimitive.int)
                    } else {
                        require(response.isNotBlank()) { "Plain response must not be blank" }
                        listOf(response)
                    }
                    val input = request.getValue("input").jsonObject
                    val sources = if (contract.text("kind") == "json_array") input.getValue("rows").jsonArray.map { it.jsonPrimitive.content }
                        else listOf(input.text("source"))
                    val converter = if (request.text("target_language") == "zh-Hant") Transliterator.getInstance("Hans-Hant") else null
                    fields.zip(sources).map { (field, source) ->
                        val decoded = EmphasisText.fromResponse(fromWire(source), field)
                        require(EmphasisText.plain(decoded).isNotBlank()) { "Annotation decoding must preserve body text" }
                        val converted = if (converter == null) decoded else EmphasisText.transform(decoded, converter::transliterate)
                        EmphasisText.wire(converted)
                    }
                }
                buildJsonObject {
                    put("valid", outcome.isSuccess)
                    put("error", outcome.exceptionOrNull()?.message?.let(::JsonPrimitive) ?: JsonNull)
                    put("decoded_target_text", outcome.getOrNull()?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                }
            }
            buildJsonObject {
                put("id", run.text("id")); put("attempts", JsonArray(attempts))
                put("final_valid", attempts.last().getValue("valid"))
            }
        }
        val output = File(requireNotNull(System.getenv("FGO_ATLAS_EVAL_VALIDATION")))
        require(!output.exists())
        output.writeText(buildJsonObject {
            put("schema_version", 1); put("responses_sha256", sha256(responseFile.readBytes()))
            put("validator", "Production general array contract and emphasis body decoding; plain-text nonblank is not semantic validation")
            put("runs", JsonArray(results))
        }.toString(), Charsets.UTF_8)
    }

    @Test fun wireExportUsesEmphasisEncodingWithoutLeakingInternalControls() {
        val source = fromWire("⟦これは⟧本当だ。")
        assertTrue(EmphasisText.has(source))
        assertEquals("⟦これは⟧本当だ。", EmphasisText.wire(source))
        val user = buildGeneralSingleUserPrompt(builder, source, emptyList())
        val wire = EmphasisText.wire(user)
        assertTrue(wire.contains("visual emphasis only"))
        assertFalse(wire.any { it in '\uE100'..'\uE102' })
    }

    private fun candidate(prompt: String, target: String): String {
        val old = if (target == "en") OLD_EN else OLD_CN
        val replacement = if (target == "en") NEW_EN else NEW_CN
        require(prompt.split(old).size == 2) { "Candidate must replace exactly one current core sentence" }
        return prompt.replace(old, replacement)
    }
    private fun fromWire(value: String): String = EmphasisText.fromResponse("\uE100source\uE101", value)
    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val OLD_CN = "Use neutral modern standard written Chinese; keep register and voice only when the Japanese carries them. Use natural Chinese order, not Japanese syntax."
        private const val NEW_CN = "Use natural modern standard written Chinese. Render idioms and indirect replies by their contextual meaning, preserving source-supported register and voice; avoid Japanese syntax."
        private const val OLD_EN = "Use neutral modern American English; keep register and voice only when the Japanese carries it. Write natural English, not Japanese word order."
        private const val NEW_EN = "Use natural modern American English. Render idioms and indirect replies by their contextual meaning, preserving source-supported register and voice; avoid Japanese syntax."
    }
}
