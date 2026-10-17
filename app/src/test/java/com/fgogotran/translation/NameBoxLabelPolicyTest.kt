package com.fgogotran.translation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameBoxLabelPolicyTest {
    private val builder = PromptBuilder()
    private val targets = listOf("zh-Hans", "zh-Hant", "en")

    // Real extracted labels span more than roster names. These are request fixtures,
    // not a new translation dictionary or assertions about what a live LLM will return.
    private val corpusLabels = listOf(
        "エリザベートたち", "ネモたち", "妖精たち", "ホームズ", "スルーズ", "ウルズ", "タマモーズ",
        "アナウンス", "謎の少女", "ダ・ヴィンチちゃん", "太歳星君", "シートン（？）", "ニキチッチ（偽）",
        "マシュ《ナレーター》", "カーター《ラウム》", "大黒天１号＆２号", "クー・フーリン＆子ギル＆マシュ",
        "ＢＢ／ＧＯ", "彼女", "みんな", "ぐんしん！", "右から左へ抜けている紫式部",
        "プトレマイオス＆ヘファイスティオン＆エルメロイⅡ世"
    )

    @Test
    fun `name instructions preserve label meaning without character-specific production examples`() {
        for (target in targets) {
            val prompt = builder.buildSystemPrompt(nameContext(target, "エリザベートたち"))
            for (rule in listOf(
                "translate the complete visible speaker label as a concise in-game label",
                "preserve descriptive or expressive wording",
                "without expanding it into dialogue or explanation",
                "glossary spellings for matching proper-name parts",
                "Translate roles/descriptions by meaning",
                "Never infer a hidden identity",
                "Preserve group meaning, titles/familiar address, states, uncertainty, letters/numbers, brackets, separators, and ruby",
                "follow the shared honorific/ruby rules",
                "Interpret endings by function, not spelling alone",
                "Preserve source pronouns and explicit group descriptions without inventing gender, relationships, or group members"
            )) assertTrue(prompt.contains(rule), "$target: $rule")
            for (example in corpusLabels) assertFalse(prompt.contains(example), "$target: $example")
            assertFalse(prompt.contains("in the name field, Xズ->"))
            assertFalse(prompt.contains("Name-group suffix in the name field:"))
        }
    }

    @Test
    fun `Chinese named groups prefer compact locale-appropriate markers without gendered expansion`() {
        for ((target, plural) in listOf("zh-Hans" to "们", "zh-Hant" to "們")) {
            val prompt = builder.buildSystemPrompt(nameContext(target, "ネモたち"))
            assertTrue(prompt.contains("For named groups, prefer the translated name plus $plural;"))
            assertTrue(prompt.contains("do not expand it into a name plus a gendered pronoun"))
            assertFalse(prompt.contains("{name_plural}"))
            assertFalse(prompt.contains("the translated name plus ${if (plural == "们") "們" else "们"};"))
        }
    }

    @Test
    fun `English uses contextual group meaning not forced plural names or Chinese markers`() {
        val prompt = builder.buildSystemPrompt(nameContext("en", "ネモたち"))
        assertTrue(prompt.contains("established FGO NA spelling or standard romanization for unknown proper names"))
        assertTrue(prompt.contains("keep appropriate Japanese or Western name order"))
        assertTrue(prompt.contains("plurals or a person with associates, according to the source and explicit context"))
        assertTrue(prompt.contains("do not assume multiple versions of one character"))
        assertFalse(prompt.contains("For named groups, prefer the translated name plus"))
        assertFalse(prompt.contains("{name_plural}"))
    }

    @Test
    fun `corpus name requests retain complete source and share the same name policy across targets`() {
        for (target in targets) {
            for (label in corpusLabels) {
                val context = nameContext(target, label)
                val prompt = builder.buildSystemPrompt(context)
                assertTrue(prompt.contains("Name-box: translate the complete visible speaker label"), "$target: $label")
                assertFalse(context.namePluralUsage.isPresent, "$target: $label")
                assertEquals(
                    "Current Japanese:\n$label",
                    buildGeneralSingleUserPrompt(builder, label, emptyList(), translateAsName = true),
                    "$target: $label"
                )
            }
        }
    }

    @Test
    fun `standalone duplicate name source does not masquerade as dialogue for plural cards`() {
        for (target in targets) {
            for (label in listOf("ホームズ", "スルーズ", "タマモーズ")) {
                // Mirrors translate(..., translateAsName = true), which supplies both fields.
                val context = builder.buildPromptContext(
                    outputFormat = PromptOutputFormat.PLAIN_TEXT,
                    sourceText = label,
                    targetLanguage = target,
                    hasName = true,
                    nameText = label,
                    isDialogue = false
                )
                assertFalse(context.namePluralUsage.isPresent, "$target: $label")
                assertFalse(builder.buildSystemPrompt(context).contains("If Xズ clearly denotes"), "$target: $label")
            }
        }
    }

    @Test
    fun `same spelling in an actual dialogue or a choice still receives existing contextual guidance`() {
        for (target in targets) {
            val dialogue = builder.buildPromptContext(
                outputFormat = PromptOutputFormat.JSON_OBJECT,
                sourceText = "ホームズ", targetLanguage = target,
                hasName = true, nameText = "ホームズ", isDialogue = true
            )
            assertTrue(dialogue.namePluralUsage.isPresent)
            val choice = builder.buildPromptContext(
                outputFormat = PromptOutputFormat.JSON_OBJECT,
                sourceText = "ホームズ", choiceTexts = listOf("タマモーズに任せよう。"),
                targetLanguage = target, hasName = true, nameText = "ホームズ", isDialogue = false
            )
            assertTrue(choice.namePluralUsage.isPresent)
            for (context in listOf(dialogue, choice)) {
                val prompt = builder.buildSystemPrompt(context)
                assertTrue(prompt.contains("If Xズ clearly denotes a character/name group"))
                assertTrue(prompt.contains("otherwise treat ズ as part of the ordinary word"))
                assertFalse(prompt.contains("Name-group suffix in the name field:"))
            }
        }
    }

    @Test
    fun `ordinary dialogue crop and battle receive no new name instructions or version changes`() {
        for (target in targets) {
            val dialogue = builder.buildPromptContext(
                outputFormat = PromptOutputFormat.PLAIN_TEXT, sourceText = "タマモーズが来た。", targetLanguage = target
            )
            val expected = if (target == "en") {
                "- If Xズ clearly denotes a character/name group, use \"the Xs\"; otherwise treat ズ as part of the ordinary word."
            } else {
                "- If Xズ clearly denotes a character/name group, use X${if (target == "zh-Hant") "們" else "们"}; " +
                    "otherwise treat ズ as part of the ordinary word."
            }
            assertTrue(builder.buildSystemPrompt(dialogue).contains(expected), target)
            for (context in listOf(
                dialogue,
                builder.buildPromptContext(PromptOutputFormat.JSON_ARRAY, "ホームズ", targetLanguage = target, isCropMode = true),
                builder.buildPromptContext(
                    PromptOutputFormat.PLAIN_TEXT, "ホームズ", targetLanguage = target,
                    promptProfile = TranslationPromptProfile.BATTLE_SUBTITLE
                )
            )) {
                assertFalse(builder.buildSystemPrompt(context).contains("Name-box: translate the complete visible speaker label"))
            }
            assertEquals(
                if (target == "en") "jp-en-fgo-target-v5-plain-layout" else "jp-cn-fgo-target-v97-plain-layout",
                PromptBuilder.promptVersionFor(target, battleSubtitle = false)
            )
            assertEquals(
                if (target == "en") "battle-subtitle-en-v4-plain-layout" else "battle-subtitle-v10-plain-layout",
                PromptBuilder.promptVersionFor(target, battleSubtitle = true)
            )
        }
    }

    @Test
    fun `existing honorific and ruby cards stay conditional alongside the name card`() {
        for (target in targets) {
            val plain = builder.buildSystemPrompt(nameContext(target, "ホームズ"))
            assertFalse(plain.contains("base〈ruby〉"))
            val ruby = builder.buildSystemPrompt(nameContext(target, "遺分體〈エレメンツ〉"))
            assertTrue(ruby.contains("base〈ruby〉"))
            assertTrue(ruby.contains("follow the shared honorific/ruby rules"))
            assertTrue(builder.buildPromptContext(
                PromptOutputFormat.PLAIN_TEXT, sourceText = "", targetLanguage = target,
                hasName = true, nameText = "ダ・ヴィンチちゃん", isDialogue = false
            ).honorificMatches.any { it.rule == HonorificPromptRule.CHAN })
        }
    }

    @Test
    fun `parenthesized uncertainty accepts source-supported width variants and combined labels`() {
        for ((source, output) in listOf(
            "シートン（？）" to "西顿（？）",
            "シートン（？）" to "西頓（？）",
            "シートン（？）" to "Seton (?)",
            "エリザベートたち（？）" to "伊丽莎白们（？）",
            "エリザベートたち（？）" to "伊莉莎白們（？）",
            "ホームズ（？）＆シートン（？）" to "Holmes (?) & Seton (?)",
            "シートン（？）？" to "Seton (?)?",
            "シートン( ? )" to "西顿（？）"
        )) assertTrue(hasAllowedVisibleNamePunctuation(source, output), "$source -> $output")
    }

    @Test
    fun `uncertainty allowance rejects invented markers mismatches sentences and embedded lines`() {
        for ((source, output) in listOf(
            "シートン" to "Seton (?)",
            "シートン（？）" to "Seton (?) (?)",
            "シートン（？）" to "Seton (?",
            "シートン（？）" to "Seton (?)?",
            "シートン（？）" to "Seton (!) ",
            "シートン（？）" to "Seton? Name (?)",
            "シートン（？）" to "Seton (?)。",
            "シートン（？）" to "Seton\n(?)",
            "シートン（？）" to "Seton\r(?)"
        )) assertFalse(hasAllowedVisibleNamePunctuation(source, output), "$source -> $output")
    }

    @Test
    fun `plain labels expressive final marks and literal punctuation keep their previous allowance`() {
        for ((source, output) in listOf(
            "ホームズ" to "Holmes", "アナウンス" to "P.A. System", "みんな" to "Everyone",
            "彼女" to "她", "マシュ《ナレーター》" to "Mash (Narrator)", "ＢＢ／ＧＯ" to "BB/GO",
            "ぐんしん！" to "军神！", "ぐんしん！" to "War God!", "ホームズ？" to "Holmes?"
        )) assertTrue(hasAllowedVisibleNamePunctuation(source, output), "$source -> $output")
        for ((source, output) in listOf(
            "ホームズ" to "Holmes!", "ホームズ？" to "Holmes!", "ぐんしん！" to "War! God!",
            "ホームズ？" to "Holmes? Extra"
        )) assertFalse(hasAllowedVisibleNamePunctuation(source, output), "$source -> $output")
    }

    @Test
    fun `long real label translations fit the bounded shared name allowance`() {
        assertEquals(48, NAME_BOX_MAX_TRANSLATED_LENGTH)
        for ((source, output, base) in listOf(
            Triple("右から左へ抜けている紫式部", "In-One-Ear-and-Out-the-Other Murasaki Shikibu", "Murasaki Shikibu"),
            Triple("羅刹王・髑髏烏帽子蘆屋道満", "Rakshasa King - Skull Eboshi Ashiya Douman", "Ashiya Douman"),
            Triple("プトレマイオス＆ヘファイスティオン＆エルメロイⅡ世", "Ptolemy & Hephaestion & El-Melloi II", "Ptolemy")
        )) {
            assertTrue(output.length > 32, output)
            assertTrue(EnglishNameValidation.isValidFullName(source, output, base, NAME_BOX_MAX_TRANSLATED_LENGTH), output)
            assertTrue(hasAllowedVisibleNamePunctuation(source, output), output)
        }
        assertTrue(EnglishNameValidation.isValidFullName("人物", "A".repeat(48), "A", NAME_BOX_MAX_TRANSLATED_LENGTH))
        assertFalse(EnglishNameValidation.isValidFullName("人物", "A".repeat(49), "A", NAME_BOX_MAX_TRANSLATED_LENGTH))
    }

    @Test
    fun `existing name validation uses revised allowances without removing other safeguards`() {
        val translator = translatorSource()
        val validation = function(translator, "isBadLlmNameTranslation")
        assertTrue(validation.contains("translated.length > NAME_BOX_MAX_TRANSLATED_LENGTH"))
        assertTrue(validation.contains("!hasAllowedVisibleNamePunctuation(sourceText, translated)"))
        assertFalse(validation.contains("translated.length > 32"))
        for (guard in listOf(
            "TextNormalizer.isQuestionMaskOnly(sourceText)", "translated.isBlank()", "translated.any(::isJapaneseKana)",
            "EnglishNameValidation.containsCjkOrKana(translated)", "EnglishNameValidation.hasLatinLetter(translated)",
            "normalizeNameLookup(sourceText) == normalizeNameLookup(translated)", "isKnownCharacterTranslationForDifferentName("
        )) assertTrue(validation.contains(guard), guard)
        assertTrue(translator.contains("NAME_WITH_STATE_MAX_TRANSLATED_LENGTH = NAME_BOX_MAX_TRANSLATED_LENGTH"))
        assertTrue(translator.contains("COMBINED_NAME_MAX_TRANSLATED_LENGTH = NAME_BOX_MAX_TRANSLATED_LENGTH"))
        val scene = function(translator, "translateScene")
        assertTrue(scene.contains("sanitizeSceneNameTranslation(")) // cached names
        assertTrue(scene.contains("isBadLlmNameTranslation(nameForLlm, cachedName"))
        assertTrue(scene.contains("isBadLlmNameTranslation(sourceName, maskedSafeName")) // fresh names
        assertTrue(function(translator, "validateLlmNameResult").contains("isBadLlmNameTranslation(")) // fallback names
        assertTrue(function(translator, "retryUntranslatedSingle").contains("initialMessages = checkNotNull(generalInitialMessages)"))
    }

    private fun nameContext(target: String, label: String): PromptContext = builder.buildPromptContext(
        outputFormat = PromptOutputFormat.JSON_OBJECT, sourceText = "", targetLanguage = target,
        hasName = true, nameText = label, isDialogue = false
    )

    private fun translatorSource(): String = listOf(
        File("src/main/java/com/fgogotran/translation/Translator.kt"),
        File("app/src/main/java/com/fgogotran/translation/Translator.kt")
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun function(source: String, name: String): String =
        Regex("(?m)^    (?:private )?(?:suspend )?fun ").split(source).drop(1)
            .first { it.startsWith("$name(") }
}
