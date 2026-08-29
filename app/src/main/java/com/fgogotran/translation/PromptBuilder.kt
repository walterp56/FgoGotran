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

data class PromptContext(
    val outputFormat: PromptOutputFormat = PromptOutputFormat.PLAIN_TEXT,
    val targetChineseLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED,
    val isCropMode: Boolean = false,
    val isDialogue: Boolean = true,
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
    val hasHonorifics: Boolean = false,
    val hasKatakana: Boolean = false,
    val hasAddressPronouns: Boolean = false,
    val hasBenefactivePassiveCausative: Boolean = false,
    val specialFirstPersonMappings: List<SpecialFirstPersonPromptMapping> = emptyList(),
    val specialSecondPersonMappings: List<SpecialSecondPersonPromptMapping> = emptyList(),
    val hasBeniEnmaDechiTic: Boolean = false,
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
        const val PROMPT_VERSION = "jp-cn-fgo-target-v73"
        private const val MAX_RAG_TERMS = 5
        private const val MIN_TERM_MATCH_LENGTH = 2
        private val pauseDashPattern = Regex("""[—―─━ー－\-一]{2,}""")
        private val maskPattern = Regex("""\?{3,}|？{3,}|[■□▇█]""")
        private val honorificPattern = Regex("""ちゃん|さん|くん|たん|てゃ|っち|様|殿|氏""")
        internal val HONORIFIC_EXCEPTION_PHRASES = setOf(
            "皆さん",
            "みなさん",
            "たくさん",
            "お父さん",
            "父さん",
            "お母さん",
            "母さん",
            "お兄さん",
            "兄さん",
            "お姉さん",
            "姉さん",
            "お客さん",
            "おじさん",
            "おばさん",
            "叔父さん",
            "叔母さん",
            "赤ちゃん",
            "お父ちゃん",
            "父ちゃん",
            "お母ちゃん",
            "母ちゃん",
            "お兄ちゃん",
            "兄ちゃん",
            "お姉ちゃん",
            "姉ちゃん",
            "おじいちゃん",
            "じいちゃん",
            "おばあちゃん",
            "ばあちゃん",
            "かんたん",
            "ぼたん",
            "ひょうたん",
            "牛たん",
            "ぎゅうたん",
            "たんたん",
            "こっち",
            "そっち",
            "あっち",
            "どっち",
            "ぼっち",
            "えっち",
            "わっち",
            "めっちゃ",
            "皆様",
            "みな様",
            "お客様",
            "神様",
            "王様",
            "奥様",
            "お嬢様",
            "殿様",
            "神殿",
            "宮殿",
            "御殿",
            "殿堂",
            "殿方",
            "彼氏"
        )
        private val honorificExceptionPattern = Regex(
            HONORIFIC_EXCEPTION_PHRASES
                .sortedByDescending(String::length)
                .joinToString("|") { Regex.escape(it) }
        )
        private val addressPronounPattern =
            Regex("""あなた|貴方|あんた|お前|おまえ|そなた|其方|お主""")
        private val katakanaWordPattern = Regex("""[ァ-ヶｦ-ﾟー]{2,}""")
        private val benefactiveAuxPattern = Regex(
            """(?:て|で)(?:く(?:れ|ださ)|もら(?:う|っ|え)|いただ(?:く|い|け)|あげ|や(?:る|っ|ろ))"""
        )
        private val passiveCausativeAuxPattern = Regex(
            """(?:させられ|せられ|させ|され|られ)(?:る|た|て|ない|なかった|ず|そう|よう|ている|ていた|ます|ません)"""
        )
        private val beniEnmaSpeakerAliases = listOf("紅閻魔", "红阎魔")
        private val beniEnmaDechiTicPattern = Regex("""でち(?![ゃゅょャュョ])""")

        /**
         * These blocks are intentionally assembled in a stable order and
         * concatenated into one natural-language prompt.
         *
         * Keeping rare rules conditional lowers prompt noise while preserving the
         * safety rules that must apply to every request.
         */
        private val BASE_TRANSLATION_PROMPT = """
            Localize FGO Japanese into natural, compact {target_chinese} for an in-game overlay.
            Preserve meaning and tone. Use only {target_chinese}; leave no kana unless a rule allows it.
            """.trimIndent()

        private val CROP_BASE_PROMPT = """
            Translate visible FGO Japanese OCR into natural, compact {target_chinese}.
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
            - Preserve speaker voice/relationship (regal, archaic, casual, childish, robotic, intimate, hostile, playful) and ambiguity.
            - Do not restore omitted subjects or possessors merely for Chinese completeness. Prefer zero subject, passive/topic-comment, or 那/这+noun; never default to 我/你.
            - Add 我/你/他/她 only when the Japanese marks it explicitly or prior context makes the referent unambiguous; for verb modifiers such as 伸ばした手, do not invent an agent or possessor.
            """.trimIndent()

        private val PARTICIPANT_DIRECTION_PROMPT = """
            - Japanese benefactive direction: てくれる/てくださる = speaker or their side receives benefit; てもらう/ていただく = speaker receives; てあげる/てやる = speaker gives. Do not reverse giver/receiver or add an omitted subject/possessor.
            - Passive/causative: 〜(ら)れる/〜(さ)せる preserve voice and agency. If the agent is omitted, keep it implicit or use passive/topic-comment; do not default to 我/你.
            """.trimIndent()

        private val LINE_BREAK_PROMPT = """
            - Preserve source line breaks only when meaningful.
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
            - Choices are Master/player replies, not narration or objective description.
            - Preserve the original sentence type; do not expand partial or attitude choices into full explanations.
            - Keep first/second-person relationship consistent with the current speaker; do not add an omitted subject.
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

        private val HONORIFIC_PROMPT = """
            - Names only: XXさん->XX桑; XXくん->XX君; XXちゃん->XX{chan}; XX殿->XX{tono}; XXたん->XX炭; XXてゃ->XX{tya}; XX様->XX大人; XX氏->XX氏; XXっち->小XX (never XX小); XXズ->XX{plural}.
            - Never apply inside ordinary words or kinship/titles, e.g. 皆さん, 赤ちゃん, 神様, 王様, 神殿, 彼氏, かんたん, 牛たん, こっち, そっち, あっち, どっち, ぼっち, えっち, わっち.
            """.trimIndent()

        private val BENI_ENMA_DECHI_PROMPT = """
            - 紅閻魔: actual copular verbal tic でち -> natural clause-final 啾; never add 啾 where でち is absent or part of an unrelated word.
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
        forceRuby: Boolean = false,
        isCropMode: Boolean = false,
        isDialogue: Boolean = !isCropMode,
        requestVoiceHint: Boolean = false,
        playerName: String = "",
        currentSpeaker: String = "",
        isChoiceBatch: Boolean = false
    ): PromptContext {
        val combinedText = (listOf(sourceText) + choiceTexts)
            .joinToString("\n")
        val cleanPlayerName = playerName.trim()
        val normalizedTargetLocale = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale)
        return PromptContext(
            outputFormat = outputFormat,
            targetChineseLocale = normalizedTargetLocale,
            isCropMode = isCropMode,
            isDialogue = isDialogue,
            requestVoiceHint = requestVoiceHint,
            hasPlaceholders = containsPlaceholder(combinedText),
            hasMasks = containsMask(combinedText),
            hasLineBreaks = containsLineBreak(sourceText) || choiceTexts.any(::containsLineBreak),
            hasMasterWord = containsMasterWord(combinedText),
            needsPlayerNameRule = cleanPlayerName.isNotBlank() && combinedText.contains(cleanPlayerName),
            hasChoices = choiceTexts.isNotEmpty() || isChoiceBatch,
            hasName = hasName,
            hasRuby = !isCropMode && (forceRuby || containsRuby(combinedText)),
            hasPauseMarks = containsPauseMarks(combinedText),
            hasHonorifics = containsHonorifics(combinedText),
            hasKatakana = containsKatakanaWord(combinedText),
            hasAddressPronouns = containsAddressPronoun(combinedText),
            hasBenefactivePassiveCausative = containsBenefactivePassiveCausative(combinedText),
            specialFirstPersonMappings = SpecialFirstPersonPronouns.promptMappings(
                combinedText,
                normalizedTargetLocale
            ),
            specialSecondPersonMappings = SpecialSecondPersonPronouns.promptMappings(
                combinedText,
                normalizedTargetLocale
            ),
            hasBeniEnmaDechiTic = containsBeniEnmaDechiTic(combinedText, currentSpeaker),
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
        appendPromptBlock(
            sb,
            blockNames,
            if (context.isCropMode) "crop_base" else "base",
            applyTargetChinese(
                if (context.isCropMode) CROP_BASE_PROMPT else BASE_TRANSLATION_PROMPT,
                targetChinese
            )
        )
        appendPromptBlock(
            sb,
            blockNames,
            outputBlockName(context.outputFormat),
            outputPromptBlock(context.outputFormat)
        )
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
            if (context.isDialogue) {
                appendPromptBlock(sb, blockNames, "dialogue_style", DIALOGUE_STYLE_PROMPT)
            }
            if (context.hasLineBreaks) {
                appendPromptBlock(sb, blockNames, "line_break", LINE_BREAK_PROMPT)
            }
            if (context.hasMasterWord) {
                appendPromptBlock(sb, blockNames, "master", MASTER_PROMPT)
            }
        }
        if (context.requestVoiceHint) {
            appendPromptBlock(sb, blockNames, "voice_hint", VOICE_HINT_PROMPT)
        }
        featurePromptBlocks(context).forEach { (name, block) ->
            appendPromptBlock(sb, blockNames, name, applyTargetChinese(block, targetChinese))
        }
        FgoLogger.debug(
            tag,
            "System prompt combination: format=${context.outputFormat.logName}, " +
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
        return buildList {
            if (!context.isCropMode && context.hasBenefactivePassiveCausative) {
                add("participant_direction" to PARTICIPANT_DIRECTION_PROMPT)
            }
            if (context.hasChoices) add("choices" to CHOICE_PROMPT)
            if (context.hasName) add("name" to NAME_PROMPT)
            if (context.hasRuby) add("ruby" to RUBY_PROMPT)
            if (context.hasPauseMarks) add("pause" to PAUSE_PROMPT)
            if (context.hasHonorifics) {
                add("honorific" to buildHonorificPrompt(context.targetChineseLocale))
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
            if (context.hasBeniEnmaDechiTic) {
                add("beni_enma_dechi" to buildBeniEnmaDechiPrompt())
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

    private fun containsHonorifics(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val withoutKnownExceptions = honorificExceptionPattern.replace(normalized, "")
        return honorificPattern.containsMatchIn(withoutKnownExceptions)
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

    internal fun buildBeniEnmaDechiPrompt(): String = BENI_ENMA_DECHI_PROMPT

    private fun buildHonorificPrompt(targetChineseLocale: String): String {
        val traditional = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        return HONORIFIC_PROMPT
            .replace("{chan}", if (traditional) "醬" else "酱")
            .replace("{tono}", if (traditional) "閣下" else "阁下")
            .replace("{tya}", if (traditional) "寶" else "宝")
            .replace("{plural}", if (traditional) "們" else "们")
    }

    private fun containsAmbiguousRoman(text: String): Boolean {
        return "ロマン" in text
    }

    private fun containsBeniEnmaDechiTic(text: String, currentSpeaker: String): Boolean {
        val normalizedSpeaker = Normalizer.normalize(currentSpeaker, Normalizer.Form.NFKC)
        if (beniEnmaSpeakerAliases.none(normalizedSpeaker::contains)) return false

        val normalizedText = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return beniEnmaDechiTicPattern.containsMatchIn(normalizedText)
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
