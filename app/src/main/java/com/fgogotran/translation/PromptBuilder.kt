package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.TermEntity
import com.fgogotran.util.FgoLogger
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

enum class PromptOutputFormat(val logName: String) {
    PLAIN_TEXT("plain_text"),
    JSON_ARRAY("json_array"),
    JSON_OBJECT("json_object")
}

enum class TranslationPromptProfile {
    GENERAL,
    BATTLE_SUBTITLE
}

enum class HonorificPromptRule(val sourceSuffix: String) {
    SAN("さん"),
    KUN("くん"),
    CHAN("ちゃん"),
    TONO("殿"),
    TAN("たん"),
    TYA("てゃ"),
    SAMA("様"),
    SHI("氏"),
    CCHI("っち")
}

data class HonorificPromptMatch(
    val rule: HonorificPromptRule,
    val presentExceptions: List<String> = emptyList()
)

data class NamePluralPromptUsage(
    val inNameField: Boolean = false,
    val inOtherText: Boolean = false
) {
    val isPresent: Boolean
        get() = inNameField || inOtherText
}

data class PromptContext(
    val outputFormat: PromptOutputFormat = PromptOutputFormat.PLAIN_TEXT,
    val targetChineseLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,
    val promptProfile: TranslationPromptProfile = TranslationPromptProfile.GENERAL,
    val isCropMode: Boolean = false,
    val isDialogue: Boolean = true,
    val isUnattributedDialogue: Boolean = false,
    val requestVoiceHint: Boolean = false,
    val hasPlaceholders: Boolean = false,
    val hasMasks: Boolean = false,
    val hasLineBreaks: Boolean = false,
    val hasMasterWord: Boolean = false,
    val needsPlayerNameRule: Boolean = false,
    val hasChoices: Boolean = false,
    val hasName: Boolean = false,
    val hasRuby: Boolean = false,
    val hasPauseMarks: Boolean = false,
    val honorificMatches: List<HonorificPromptMatch> = emptyList(),
    val namePluralUsage: NamePluralPromptUsage = NamePluralPromptUsage(),
    val hasKatakana: Boolean = false,
    val hasAddressPronouns: Boolean = false,
    val hasBenefactivePassiveCausative: Boolean = false,
    val characterContextPrompt: String = "",
    val specialFirstPersonMappings: List<SpecialFirstPersonPromptMapping> = emptyList(),
    val specialSecondPersonMappings: List<SpecialSecondPersonPromptMapping> = emptyList(),
    val hasAmbiguousRoman: Boolean = false
)

/**
 * Constructs system and user prompts for the LLM translation backends.
 *
 * ## System prompt structure
 * 1. Tiny base role and output contract
 * 2. Small safety, style, and feature blocks for the current source shape
 *
 * ## User prompt structure
 * 1. Optional choice text context (if player choices are on screen)
 * 2. The actual Japanese dialogue text to translate
 *
 * ## RAG (Retrieval-Augmented Generation)
 * The [extractTermMatches] method finds FGO-specific proper nouns in the JP text
 * so Translator can lock them as placeholders and restore official Chinese after
 * the model responds. This keeps terminology consistent across backends.
 */
@Singleton
class PromptBuilder @Inject constructor() {

    companion object {
        const val PROMPT_VERSION = "jp-cn-fgo-target-v86"
        const val BATTLE_PROMPT_VERSION = "battle-subtitle-v2"
        private const val MAX_RAG_TERMS = 5
        private const val MIN_TERM_MATCH_LENGTH = 2
        private val pauseDashPattern = Regex("""[—―─━ー－\-一]{2,}""")
        private val maskPattern = Regex("""\?{3,}|？{3,}|[■□▇█]""")
        private val honorificExceptionsByRule = mapOf(
            HonorificPromptRule.SAN to setOf(
                "皆さん", "みなさん", "たくさん", "お父さん", "父さん", "お母さん", "母さん",
                "お兄さん", "兄さん", "お姉さん", "姉さん", "お客さん", "おじさん", "おばさん",
                "叔父さん", "叔母さん"
            ),
            HonorificPromptRule.CHAN to setOf(
                "赤ちゃん", "お父ちゃん", "父ちゃん", "お母ちゃん", "母ちゃん", "お兄ちゃん",
                "兄ちゃん", "お姉ちゃん", "姉ちゃん", "おじいちゃん", "じいちゃん",
                "おばあちゃん", "ばあちゃん"
            ),
            HonorificPromptRule.TAN to setOf(
                "かんたん", "ぼたん", "ひょうたん", "牛たん", "ぎゅうたん", "たんたん"
            ),
            HonorificPromptRule.CCHI to setOf(
                "こっち", "そっち", "あっち", "どっち", "ぼっち", "えっち", "わっち", "めっちゃ"
            ),
            HonorificPromptRule.SAMA to setOf(
                "皆様", "みな様", "お客様", "神様", "王様", "奥様", "お嬢様", "殿様"
            ),
            HonorificPromptRule.TONO to setOf(
                "殿様", "神殿", "宮殿", "御殿", "殿堂", "殿方"
            ),
            HonorificPromptRule.SHI to setOf("彼氏")
        )
        internal val HONORIFIC_EXCEPTION_PHRASES = honorificExceptionsByRule.values
            .flatten()
            .toSet()
        private val honorificExceptionPattern = Regex(
            HONORIFIC_EXCEPTION_PHRASES
                .sortedByDescending(String::length)
                .joinToString("|") { Regex.escape(it) }
        )
        private val addressPronounPattern =
            Regex("""あなた|貴方|あんた|お前|おまえ|そなた|其方|お主""")
        private val katakanaWordPattern = Regex("""[ァ-ヶｦ-ﾟー]{2,}""")
        private val namePluralZuCandidatePattern =
            Regex("""[\p{IsHan}\u3040-\u30FF\uFF66-\uFF9DA-Za-z0-9・ー]ズ(?![\u30A0-\u30FF\uFF66-\uFF9Dー])""")
        private val benefactiveAuxPattern = Regex(
            """(?:て|で)(?:く(?:れ|ださ)|もら(?:う|っ|え)|いただ(?:く|い|け)|あげ|や(?:る|っ|ろ))"""
        )
        private val passiveCausativeAuxPattern = Regex(
            """(?:させられ|せられ|させ|され|られ)(?:る|た|て|ない|なかった|ず|そう|よう|ている|ていた|ます|ません)"""
        )
        /**
         * These blocks are intentionally assembled in a stable order and
         * concatenated into one natural-language prompt.
         *
         * Keeping rare rules conditional lowers prompt noise while preserving the
         * safety rules that must apply to every request.
         */
        private val BASE_TRANSLATION_PROMPT = """
            You are an expert Japanese-to-Chinese localizer for Fate/Grand Order.
            Translate Fate/Grand Order Japanese faithfully into natural {target_chinese} for an in-game overlay; be concise without losing information.
            Preserve complete meaning, viewpoint, tone, character voice, relationships, intentional ambiguity, and ellipsis.
            Use only {target_chinese}; leave no kana unless a rule allows it.
            """.trimIndent()

        private val BATTLE_SUBTITLE_BASE_PROMPT = """
            You are an expert Japanese-to-Chinese localizer for Fate/Grand Order battle subtitles.
            Translate only the current OCR-captured battle subtitle into concise, natural {target_chinese} for immediate overlay display.
            Preserve every visible sentence and fragment in order, with its full meaning, action roles, negation, modality, address, ambiguity, tone, register, sentence type, repetition, and intensity. Translate the capture as a whole; OCR newlines are visual wrapping.
            Use only this capture. Never infer a speaker or unavailable context from FGO knowledge, writing style, or animation; never invent or repair missing OCR text, censor, soften, summarize, complete, or omit content.
            Use only {target_chinese}; leave no kana unless a rule allows it.
            """.trimIndent()

        private val BATTLE_PRONOUN_FIDELITY_PROMPT = """
            - Preserve explicit personal references, who performs and receives each action, and whose things are involved. For omitted subjects, objects, or possessors, prefer natural Chinese omission or restructuring; never infer them from an assumed speaker, FGO lore, animation, or unavailable context. Preserve unresolved ambiguity.
            """.trimIndent()

        private val CROP_BASE_PROMPT = """
            Translate visible Fate/Grand Order Japanese OCR faithfully into natural {target_chinese}; be concise without losing information.
            Use only {target_chinese}; do not infer text outside the crop.
            """.trimIndent()

        private val PLAIN_OUTPUT_PROMPT = """
            Return only the Chinese translation; no notes, markdown, labels, wrappers, or source text.
            """.trimIndent()

        private val JSON_OBJECT_OUTPUT_PROMPT = """
            Return JSON only with exactly the requested keys; no extra keys or text.
            """.trimIndent()

        private val JSON_ARRAY_OUTPUT_PROMPT = """
            Return a JSON array only; preserve item count and order.
            """.trimIndent()

        private val PLACEHOLDER_PROMPT = """
            - Keep every placeholder token starting with __FGO unchanged exactly.
            """.trimIndent()

        private val MASK_PROMPT = """
            - Preserve masks (???, ？？？, ■, □, ▇, █) exactly; never guess them.
            """.trimIndent()

        private val PLAYER_NAME_PROMPT = """
            - Player name: "{player_name}". Keep it exactly if it appears.
            """.trimIndent()

        private val DIALOGUE_STYLE_PROMPT = """
            - Preserve characterization and register in natural Chinese.
            """.trimIndent()

        private val PRONOUN_FIDELITY_PROMPT = """
            - Preserve stated personal references, who performs and receives each action, and whose things are involved. Speaker identity alone does not establish the actor or possessor.
            - When subjects, objects, or possessors are omitted, prefer natural Chinese omission or restructuring. Express a personal reference only when the Japanese source and relevant Japanese context clearly establish it and accurate, natural Chinese needs it. Preserve unresolved ambiguity rather than guessing identity or ownership.
            """.trimIndent()

        private val BATTLE_PUNCTUATION_PROMPT = """
            - Preserve every visible FGO punctuation mark and wrapper exactly in type, nesting, position, order, and repetition, including 「」, 『』, quotes, （）, (), brackets, ellipses, long dashes, 、。！？, and clusters such as ！！？？. Never drop punctuation, invent unmatched wrappers, move terminal punctuation outside its closing wrapper, or collapse expressive clusters. Add internal Chinese punctuation only where natural syntax requires it.
            """.trimIndent()

        private val UNATTRIBUTED_DIALOGUE_PROMPT = """
            - No speaker name was detected. It may be narration, an unidentified voice, or inner thought. Determine the viewpoint from the current Japanese and previous Japanese context; do not invent or automatically inherit a speaker.
            """.trimIndent()

        private val PARTICIPANT_DIRECTION_PROMPT = """
            - For benefactives, causatives, and 〜(ら)れる, determine the grammatical function from Japanese syntax and context. Preserve action direction and possession even when restructuring Chinese; do not automatically translate every 〜(ら)れる as passive.
            """.trimIndent()

        private val SOURCE_FIDELITY_CHECK_PROMPT = """
            - Before returning, check for unsupported additions, omitted meaning, and changed action roles. Correct only errors supported by the source; keep the requested output format.
            """.trimIndent()

        private val LINE_BREAK_PROMPT = """
            - Keep each source sentence's meaning in its corresponding Chinese sentence; preserve line breaks only when meaningful.
            """.trimIndent()

        private val MASTER_PROMPT = """
            - In FGO dialogue, マスター->御主, not 主人/大师/Master unless it is an English UI label.
            """.trimIndent()

        private val CROP_STYLE_PROMPT = """
            - Preserve OCR row order and every visible fragment; never merge, split, add, omit, or complete text.
            - Preserve numbers, percentages, levels, ranks, counts, and text-like icons.
            - Dialogue: preserve voice. Other text: concise game-UI style. Never add names or labels.
            """.trimIndent()

        private val VOICE_HINT_PROMPT = """
            - voice_hint describes delivery only and must not change translation; use null when unclear.
            """.trimIndent()

        private val CHOICE_PROMPT = """
            - Choices are Master/player replies, not narration or objective description; this role never licenses a first-person pronoun absent from the choice source.
            - Preserve the original sentence type; do not expand partial or attitude choices into full explanations.
            """.trimIndent()

        private val NAME_PROMPT = """
            - Unknown names/proper nouns -> concise FGO/TYPE-MOON-style Chinese transliteration; never leave Japanese or substitute another character.
            - Name-box: preserve every visible title, suffix, annotation/state, ?, A/B, bracket, and ruby.
            """.trimIndent()

        private val RUBY_PROMPT = """
            - base《ruby》 -> Chinese base《ruby》; translate both naturally and never omit ruby.
            - English-style ruby may stay English; use 《》 only.
            """.trimIndent()

        private val PAUSE_PROMPT = """
            - Preserve dramatic pauses; normalize dots to …… and long dashes to ───.
            """.trimIndent()

        private val ADDRESS_PRONOUN_PROMPT = """
            - Translate Japanese second-person address by tone/relationship; never keep it as Japanese or a name.
            """.trimIndent()

        private val KATAKANA_STYLE_PROMPT = """
            - Common katakana loanwords may use compact English.
            - Translate/transliterate other unprotected katakana; never leave names, organizations, classes, Noble Phantasms, skills, yokai, nicknames, or attacks in kana.
            """.trimIndent()

        private val AMBIGUOUS_ROMAN_PROMPT = """
            - ロマン is a character/name only when clearly a person; otherwise translate it as 浪漫.
            """.trimIndent()

    }

    private val tag = "PromptBuilder"

    private data class TermSearchText(
        val sourceText: String,
        val compactText: String,
        val sourceIndices: List<Int>
    )

    fun buildPromptContext(
        outputFormat: PromptOutputFormat,
        sourceText: String,
        choiceTexts: List<String> = emptyList(),
        targetChineseLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,
        hasName: Boolean = false,
        nameText: String? = null,
        forceRuby: Boolean = false,
        isCropMode: Boolean = false,
        isDialogue: Boolean = !isCropMode,
        requestVoiceHint: Boolean = false,
        playerName: String = "",
        currentSpeaker: String = "",
        characterContextPrompt: String = "",
        isChoiceBatch: Boolean = false,
        promptProfile: TranslationPromptProfile = TranslationPromptProfile.GENERAL
    ): PromptContext {
        val isBattleSubtitle = promptProfile == TranslationPromptProfile.BATTLE_SUBTITLE
        val cleanNameText = nameText
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { isBattleSubtitle }
        val relevantChoiceTexts = choiceTexts.takeUnless { isBattleSubtitle }.orEmpty()
        val primarySourceText = listOfNotNull(cleanNameText, sourceText.takeIf { it.isNotBlank() })
            .joinToString("\n")
        val otherText = (listOf(sourceText) + relevantChoiceTexts).joinToString("\n")
        val combinedText = (listOf(primarySourceText) + relevantChoiceTexts).joinToString("\n")
        val cleanPlayerName = playerName.trim()
        val normalizedTargetLocale = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale)
        return PromptContext(
            outputFormat = outputFormat,
            targetChineseLocale = normalizedTargetLocale,
            promptProfile = promptProfile,
            isCropMode = isCropMode,
            isDialogue = isDialogue,
            isUnattributedDialogue = !isBattleSubtitle &&
                !isCropMode &&
                isDialogue &&
                !isChoiceBatch &&
                currentSpeaker.isBlank(),
            requestVoiceHint = requestVoiceHint && !isBattleSubtitle,
            hasPlaceholders = containsPlaceholder(combinedText),
            hasMasks = containsMask(combinedText),
            hasLineBreaks = !isBattleSubtitle &&
                (containsLineBreak(primarySourceText) || relevantChoiceTexts.any(::containsLineBreak)),
            hasMasterWord = containsMasterWord(combinedText),
            needsPlayerNameRule = cleanPlayerName.isNotBlank() && combinedText.contains(cleanPlayerName),
            hasChoices = !isBattleSubtitle &&
                (relevantChoiceTexts.isNotEmpty() || isChoiceBatch),
            hasName = hasName && !isBattleSubtitle,
            hasRuby = !isBattleSubtitle && !isCropMode && (forceRuby || containsRuby(combinedText)),
            hasPauseMarks = containsPauseMarks(combinedText),
            honorificMatches = detectHonorificPromptMatches(combinedText),
            namePluralUsage = detectNamePluralPromptUsage(
                nameText = cleanNameText,
                otherText = otherText,
                enabled = !isCropMode
            ),
            hasKatakana = containsKatakanaWord(combinedText),
            hasAddressPronouns = containsAddressPronoun(combinedText),
            hasBenefactivePassiveCausative = containsBenefactivePassiveCausative(combinedText),
            characterContextPrompt = characterContextPrompt.trim().takeUnless { isBattleSubtitle }.orEmpty(),
            specialFirstPersonMappings = SpecialFirstPersonPronouns.promptMappings(
                combinedText,
                normalizedTargetLocale
            ),
            specialSecondPersonMappings = SpecialSecondPersonPronouns.promptMappings(
                combinedText,
                normalizedTargetLocale
            ),
            hasAmbiguousRoman = containsAmbiguousRoman(combinedText)
        )
    }

    /**
     * Builds the system prompt for source text that has already had locked RAG terms protected.
     *
     * @param playerName the user's FGO Master name for personalization
     * @return complete system prompt string ready to send to the LLM
     */
    fun buildSystemPrompt(
        playerName: String,
        context: PromptContext = PromptContext()
    ): String {
        val sb = StringBuilder()
        val blockNames = mutableListOf<String>()
        val targetChinese = targetChinesePromptLabel(context.targetChineseLocale)
        val isBattleSubtitle =
            context.promptProfile == TranslationPromptProfile.BATTLE_SUBTITLE
        val basePrompt = when {
            context.isCropMode -> CROP_BASE_PROMPT
            isBattleSubtitle -> BATTLE_SUBTITLE_BASE_PROMPT
            else -> BASE_TRANSLATION_PROMPT
        }
        appendPromptBlock(
            sb,
            blockNames,
            when {
                context.isCropMode -> "crop_base"
                isBattleSubtitle -> "battle_base"
                else -> "base"
            },
            applyTargetChinese(basePrompt, targetChinese)
        )
        appendPromptBlock(
            sb,
            blockNames,
            outputBlockName(context.outputFormat),
            outputPromptBlock(context.outputFormat)
        )
        if (context.isDialogue || context.hasChoices || context.isCropMode) {
            appendPromptBlock(
                sb,
                blockNames,
                if (isBattleSubtitle) "battle_pronoun_fidelity" else "pronoun_fidelity",
                if (isBattleSubtitle) {
                    BATTLE_PRONOUN_FIDELITY_PROMPT
                } else {
                    buildPronounFidelityPrompt()
                }
            )
        }
        if (isBattleSubtitle) {
            appendPromptBlock(
                sb,
                blockNames,
                "battle_punctuation",
                BATTLE_PUNCTUATION_PROMPT
            )
        }
        if (context.hasPlaceholders) {
            appendPromptBlock(sb, blockNames, "placeholder", PLACEHOLDER_PROMPT)
        }
        if (context.hasMasks) {
            appendPromptBlock(sb, blockNames, "mask", MASK_PROMPT)
        }
        if (context.needsPlayerNameRule) {
            appendPromptBlock(
                sb,
                blockNames,
                "player_name",
                PLAYER_NAME_PROMPT.replace("{player_name}", playerName.ifBlank { "Master" })
            )
        }
        if (context.isCropMode) {
            appendPromptBlock(sb, blockNames, "crop_style", CROP_STYLE_PROMPT)
            if (context.hasMasterWord) {
                appendPromptBlock(sb, blockNames, "master", MASTER_PROMPT)
            }
        } else {
            if (context.isDialogue && !isBattleSubtitle) {
                appendPromptBlock(sb, blockNames, "dialogue_style", DIALOGUE_STYLE_PROMPT)
                if (context.characterContextPrompt.isNotBlank()) {
                    appendPromptBlock(
                        sb,
                        blockNames,
                        "character_context",
                        buildCharacterContextPrompt(context.characterContextPrompt)
                    )
                }
                if (context.isUnattributedDialogue) {
                    appendPromptBlock(
                        sb,
                        blockNames,
                        "unattributed_dialogue",
                        UNATTRIBUTED_DIALOGUE_PROMPT
                    )
                }
            }
            if (!isBattleSubtitle && context.hasLineBreaks) {
                appendPromptBlock(sb, blockNames, "line_break", LINE_BREAK_PROMPT)
            }
            if (context.hasMasterWord) {
                appendPromptBlock(sb, blockNames, "master", MASTER_PROMPT)
            }
        }
        if (!isBattleSubtitle && context.requestVoiceHint) {
            appendPromptBlock(sb, blockNames, "voice_hint", VOICE_HINT_PROMPT)
        }
        featurePromptBlocks(context).forEach { (name, block) ->
            appendPromptBlock(sb, blockNames, name, applyTargetChinese(block, targetChinese))
        }
        if (context.isDialogue || context.hasChoices || context.isCropMode) {
            appendPromptBlock(sb, blockNames, "source_fidelity_check", buildSourceFidelityCheckPrompt())
        }
        FgoLogger.debug(
            tag,
            "System prompt combination: profile=${context.promptProfile}, " +
                "format=${context.outputFormat.logName}, " +
                "target=${context.targetChineseLocale}, blocks=${blockNames.joinToString("+")}, " +
                "chars=${sb.length}"
        )
        return sb.toString()
    }

    private fun outputBlockName(outputFormat: PromptOutputFormat): String {
        return when (outputFormat) {
            PromptOutputFormat.PLAIN_TEXT -> "plain_output"
            PromptOutputFormat.JSON_ARRAY -> "json_array_output"
            PromptOutputFormat.JSON_OBJECT -> "json_object_output"
        }
    }

    private fun outputPromptBlock(outputFormat: PromptOutputFormat): String {
        return when (outputFormat) {
            PromptOutputFormat.PLAIN_TEXT -> PLAIN_OUTPUT_PROMPT
            PromptOutputFormat.JSON_ARRAY -> JSON_ARRAY_OUTPUT_PROMPT
            PromptOutputFormat.JSON_OBJECT -> JSON_OBJECT_OUTPUT_PROMPT
        }
    }

    private fun applyTargetChinese(block: String, targetChinese: String): String {
        return block.replace("{target_chinese}", targetChinese)
    }

    private fun targetChinesePromptLabel(targetChineseLocale: String): String {
        return when (SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale)) {
            SettingsRepository.TARGET_LOCALE_TRADITIONAL -> "Traditional Chinese"
            else -> "Simplified Chinese"
        }
    }

    private fun appendPromptBlock(
        sb: StringBuilder,
        blockNames: MutableList<String>,
        name: String,
        block: String
    ) {
        if (block.isBlank()) return
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(block.trim())
        blockNames += name
    }

    private fun featurePromptBlocks(context: PromptContext): List<Pair<String, String>> {
        val isBattleSubtitle =
            context.promptProfile == TranslationPromptProfile.BATTLE_SUBTITLE
        return buildList {
            if (!context.isCropMode && context.hasBenefactivePassiveCausative) {
                add("participant_direction" to PARTICIPANT_DIRECTION_PROMPT)
            }
            if (!isBattleSubtitle && context.hasChoices) add("choices" to CHOICE_PROMPT)
            if (!isBattleSubtitle && context.hasName) add("name" to NAME_PROMPT)
            if (context.namePluralUsage.isPresent) {
                add(
                    "name_plural" to buildNamePluralPrompt(
                        context.namePluralUsage,
                        context.targetChineseLocale
                    )
                )
            }
            if (!isBattleSubtitle && context.hasRuby) add("ruby" to RUBY_PROMPT)
            if (context.hasPauseMarks) add("pause" to PAUSE_PROMPT)
            if (context.honorificMatches.isNotEmpty()) {
                add(
                    "honorific" to buildHonorificPrompt(
                        context.honorificMatches,
                        context.targetChineseLocale
                    )
                )
            }
            if (context.hasAddressPronouns) add("address_pronoun" to ADDRESS_PRONOUN_PROMPT)
            if (context.specialSecondPersonMappings.isNotEmpty()) {
                add(
                    "special_second_person" to buildSpecialSecondPersonPrompt(
                        context.specialSecondPersonMappings
                    )
                )
            }
            if (context.hasKatakana) add("katakana_style" to KATAKANA_STYLE_PROMPT)
            if (context.specialFirstPersonMappings.isNotEmpty()) {
                add(
                    "special_first_person" to buildSpecialFirstPersonPrompt(
                        context.specialFirstPersonMappings
                    )
                )
            }
            if (context.hasAmbiguousRoman) add("ambiguous_roman" to AMBIGUOUS_ROMAN_PROMPT)
        }
    }

    /**
     * Builds the user prompt containing the JP text to translate.
     *
     * @param japaneseText the dialogue text from OCR
     * @param choiceTexts optional player choice strings appearing on the same screen
     * @return complete user prompt string
     */
    fun buildUserPrompt(
        japaneseText: String,
        choiceTexts: List<String>
    ): String {
        val sb = StringBuilder()

        // Prepend choice context if present — helps the LLM understand
        // that these are separate interactive elements, not dialogue lines
        if (choiceTexts.isNotEmpty()) {
            sb.append("Player choices (context only; do not output):\n")
            for ((i, choice) in choiceTexts.withIndex()) {
                sb.append("${i + 1}. $choice\n")
            }
            sb.append('\n')
        }
        sb.append("Source:\n")
        sb.append(japaneseText)

        FgoLogger.debug(tag, "User prompt: ${sb.length} chars, choices=${choiceTexts.size}")
        return sb.toString()
    }

    fun buildCropUserPrompt(
        japaneseText: String
    ): String {
        val sb = StringBuilder()
        val lines = japaneseText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        sb.append("Return a JSON array of exactly ${lines.size} strings, one per OCR row, in order.\n")
        sb.append("OCR rows:\n")
        lines.forEachIndexed { index, line ->
            sb.append("${index + 1}. ")
            sb.append(line)
            if (index != lines.lastIndex) sb.append('\n')
        }

        FgoLogger.debug(tag, "Crop user prompt: ${sb.length} chars")
        return sb.toString()
    }

    private fun containsRuby(text: String): Boolean {
        return '《' in text && '》' in text
    }

    private fun containsPlaceholder(text: String): Boolean {
        return "__FGO" in text
    }

    private fun containsMask(text: String): Boolean {
        return maskPattern.containsMatchIn(text)
    }

    private fun containsLineBreak(text: String): Boolean {
        return '\n' in text || '\r' in text
    }

    private fun containsMasterWord(text: String): Boolean {
        return "マスター" in text
    }

    private fun containsPauseMarks(text: String): Boolean {
        return FgoDialogueSymbols.containsLongPause(text) ||
                pauseDashPattern.containsMatchIn(text)
    }

    private fun detectHonorificPromptMatches(text: String): List<HonorificPromptMatch> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val presentExceptions = honorificExceptionPattern.findAll(normalized)
            .map { it.value }
            .distinct()
            .toList()
        val withoutKnownExceptions = honorificExceptionPattern.replace(normalized) { match ->
            " ".repeat(match.value.length)
        }
        return HonorificPromptRule.entries.mapNotNull { rule ->
            if (!withoutKnownExceptions.contains(rule.sourceSuffix)) {
                return@mapNotNull null
            }
            val relevantExceptions = honorificExceptionsByRule[rule].orEmpty()
            HonorificPromptMatch(
                rule = rule,
                presentExceptions = presentExceptions.filter { it in relevantExceptions }
            )
        }
    }

    private fun containsAddressPronoun(text: String): Boolean {
        return addressPronounPattern.containsMatchIn(text)
    }

    private fun containsBenefactivePassiveCausative(text: String): Boolean {
        val normalized = Normalizer.normalize(
            TextNormalizer.stripRubyAnnotations(text),
            Normalizer.Form.NFKC
        )
        return benefactiveAuxPattern.containsMatchIn(normalized) ||
            passiveCausativeAuxPattern.containsMatchIn(normalized)
    }

    private fun containsKatakanaWord(text: String): Boolean {
        return katakanaWordPattern.containsMatchIn(text)
    }

    private fun detectNamePluralPromptUsage(
        nameText: String?,
        otherText: String,
        enabled: Boolean
    ): NamePluralPromptUsage {
        if (!enabled) return NamePluralPromptUsage()
        return NamePluralPromptUsage(
            inNameField = nameText?.let(namePluralZuCandidatePattern::containsMatchIn) == true,
            inOtherText = namePluralZuCandidatePattern.containsMatchIn(otherText)
        )
    }

    internal fun buildSpecialFirstPersonPrompt(
        mappings: List<SpecialFirstPersonPromptMapping>
    ): String {
        val rules = mappings.joinToString("; ") { mapping ->
            "${mapping.sourceForm} -> ${mapping.targetTranslation}"
        }
        return "- [FP] $rules; exact first-person mappings, not names or 我."
    }

    internal fun buildSpecialSecondPersonPrompt(
        mappings: List<SpecialSecondPersonPromptMapping>
    ): String {
        val rules = mappings.joinToString("; ") { mapping ->
            "${mapping.sourceForm} -> ${mapping.targetTranslation}"
        }
        return "- [2P] $rules; exact second-person mappings, not names or generic 你."
    }

    internal fun buildPronounFidelityPrompt(): String = PRONOUN_FIDELITY_PROMPT

    internal fun buildSourceFidelityCheckPrompt(): String = SOURCE_FIDELITY_CHECK_PROMPT

    internal fun buildCharacterContextPrompt(prompt: String): String {
        return buildString {
            appendLine(
                "- Apply character context only to the current dialogue, never names or choices. " +
                    "It is voice/register guidance, not evidence for unstated participants, possession, " +
                    "or relationships; do not add tics absent from current JP."
            )
            append("- ")
            append(prompt.trim())
        }
    }

    internal fun buildNamePluralPrompt(
        usage: NamePluralPromptUsage,
        targetChineseLocale: String
    ): String {
        val plural = if (
            SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        ) {
            "們"
        } else {
            "们"
        }
        return when {
            usage.inNameField && usage.inOtherText ->
                "- Name-group suffix: in the name field, Xズ->X$plural. Elsewhere, use X$plural only " +
                    "when Xズ clearly denotes a character/name group; otherwise ズ is part of the word."
            usage.inNameField -> "- Name-group suffix in the name field: Xズ->X$plural."
            usage.inOtherText ->
                "- If Xズ clearly denotes a character/name group, use X$plural; otherwise treat ズ as " +
                    "part of the ordinary word."
            else -> ""
        }
    }

    private fun buildHonorificPrompt(
        matches: List<HonorificPromptMatch>,
        targetChineseLocale: String
    ): String {
        val traditional = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        val mappings = matches.joinToString("; ") { match ->
            when (match.rule) {
                HonorificPromptRule.SAN -> "XXさん->XX桑"
                HonorificPromptRule.KUN -> "XXくん->XX君"
                HonorificPromptRule.CHAN -> "XXちゃん->XX${if (traditional) "醬" else "酱"}"
                HonorificPromptRule.TONO -> "XX殿->XX${if (traditional) "閣下" else "阁下"}"
                HonorificPromptRule.TAN -> "XXたん->XX炭"
                HonorificPromptRule.TYA -> "XXてゃ->XX${if (traditional) "寶" else "宝"}"
                HonorificPromptRule.SAMA -> "XX様->XX大人"
                HonorificPromptRule.SHI -> "XX氏->XX氏"
                HonorificPromptRule.CCHI -> "XXっち->小XX (never XX小)"
            }
        }
        val presentExceptions = matches
            .flatMap { it.presentExceptions }
            .distinct()
        return buildString {
            append("- Names only: ")
            append(mappings)
            append('.')
            if (presentExceptions.isNotEmpty()) {
                append(" Current-source exceptions (ordinary/kinship/title, not name suffixes): ")
                append(presentExceptions.joinToString(", "))
                append('.')
            }
        }
    }

    private fun containsAmbiguousRoman(text: String): Boolean {
        return "ロマン" in text
    }

    /**
     * Finds FGO terminology terms that appear in the Japanese text.
     *
     * Matching strategy:
     * 1. Exact substring match against the term's primary JP name (e.g., "マシュ")
     * 2. Alias match: aliases are stored as a comma-separated JSON-like string
     *    (e.g., `["マシュ・キリエライト","盾兵"]`) — we strip JSON wrapper chars
     *    and check each alias as a substring.
     *
     * @param japaneseText the full OCR-extracted JP dialogue text
     * @param terms all known FGO terms from the glossary database
     * @return subset of terms that appear in the text
     */
    fun extractTermMatches(japaneseText: String, terms: List<TermEntity>): List<TermEntity> {
        val searchText = buildTermSearchText(japaneseText)
        if (searchText.compactText.isBlank()) return emptyList()

        val matches = terms.asSequence()
            .mapNotNull { term ->
                val matchedLength = longestMatchedNeedleLength(searchText, term)
                if (matchedLength > 0) term to matchedLength else null
            }
            .sortedWith(
                compareByDescending<Pair<TermEntity, Int>> { it.second }
                    .thenBy { it.first.category }
                    .thenBy { it.first.jpTerm }
            )
            .map { it.first }
            .distinctBy { it.jpTerm }
            .take(MAX_RAG_TERMS)
            .toList()

        FgoLogger.debug(tag, "Term matching: ${matches.size} of ${terms.size} terms matched")
        if (matches.isNotEmpty()) {
            FgoLogger.debug(
                tag,
                "Matched terms: ${
                    matches.joinToString(limit = MAX_RAG_TERMS) {
                        "${it.jpTerm}->${it.cnTerm}"
                    }
                }"
            )
        }
        return matches
    }

    private fun longestMatchedNeedleLength(text: TermSearchText, term: TermEntity): Int {
        return candidateNeedles(term)
            .filter { text.containsNeedle(it) }
            .maxOfOrNull { it.length }
            ?: 0
    }

    private fun candidateNeedles(term: TermEntity): List<String> {
        return buildList {
            normalizeForTermMatch(term.jpTerm)
                .takeIf { it.length >= MIN_TERM_MATCH_LENGTH }
                ?.let(::add)
            term.aliases.orEmpty()
                .split(',', '，', '\n')
                .map { it.trim('"', '\'', '[', ']', ' ', '\t', '\r') }
                .map(::normalizeForTermMatch)
                .filter { it.length >= MIN_TERM_MATCH_LENGTH }
                .forEach(::add)
        }.distinct()
    }

    private fun buildTermSearchText(text: String): TermSearchText {
        val compactText = StringBuilder()
        val sourceIndices = mutableListOf<Int>()
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        for (index in normalized.indices) {
            val normalizedChar = normalizeOcrTermGlyphs(normalized[index].toString())
            for (char in normalizedChar) {
                if (!char.isTermMatchSeparator()) {
                    compactText.append(char)
                    sourceIndices += index
                }
            }
        }
        return TermSearchText(normalized, compactText.toString(), sourceIndices)
    }

    private fun TermSearchText.containsNeedle(needle: String): Boolean {
        if (needle.isBlank()) return false
        var startIndex = 0
        while (startIndex <= compactText.length - needle.length) {
            val matchStart = compactText.indexOf(needle, startIndex)
            if (matchStart < 0) return false

            val matchEnd = matchStart + needle.length - 1
            val sourceStart = sourceIndices[matchStart]
            val sourceEndExclusive = sourceIndices[matchEnd] + 1
            if (!needle.requiresKatakanaBoundary() ||
                hasKatakanaWordBoundary(sourceStart, sourceEndExclusive)
            ) {
                return true
            }
            startIndex = matchStart + 1
        }
        return false
    }

    private fun TermSearchText.hasKatakanaWordBoundary(start: Int, endExclusive: Int): Boolean {
        val before = sourceText.getOrNull(start - 1)
        val after = sourceText.getOrNull(endExclusive)
        return before?.isKatakanaWordChar() != true && after?.isKatakanaWordChar() != true
    }

    private fun normalizeForTermMatch(text: String): String {
        return normalizeOcrTermGlyphs(Normalizer.normalize(text.trim(), Normalizer.Form.NFKC))
            .replace(Regex("""[\s　]+"""), "")
            .replace(Regex("""[・･·•,，、。.!！?？:：;；\[\]（）()「」『』"“”'’‘=＝\-－—―_＿]"""), "")
    }

    private fun normalizeOcrTermGlyphs(text: String): String {
        return text
            .replace('一', 'ー')
    }

    private fun String.requiresKatakanaBoundary(): Boolean {
        return isNotBlank() && all { it.isKatakanaWordChar() }
    }

    private fun Char.isKatakanaWordChar(): Boolean {
        return this in '\u30A1'..'\u30FA' ||
                this == 'ー' ||
                this in '\u31F0'..'\u31FF' ||
                this in '\uFF66'..'\uFF9D' ||
                this == 'ｰ'
    }

    private fun Char.isTermMatchSeparator(): Boolean {
        return isWhitespace() || this in setOf(
            '　', '・', '･', '·', '•', ',', '，', '、', '。', '.', '!',
            '！', '?', '？', ':', '：', ';', '；', '[', ']', '（',
            '）', '(', ')', '「', '」', '『', '』', '"', '“', '”',
            '\'', '’', '‘', '=', '＝', '-', '－', '—', '―', '_', '＿'
        )
    }
}
