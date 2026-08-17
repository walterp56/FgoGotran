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
    val hasSpecialFirstPerson: Boolean = false,
    val hasAmbiguousRoman: Boolean = false,
    val isRetry: Boolean = false
)

/**
 * Constructs system and user prompts for the LLM translation backends.
 *
 * ## System prompt structure
 * 1. Tiny base role and output contract
 * 2. Small safety, style, and feature blocks for the current source shape
 * 3. Injected RAG terminology table (JP → official CN)
 *
 * ## User prompt structure
 * 1. Optional choice text context (if player choices are on screen)
 * 2. The actual Japanese dialogue text to translate
 *
 * ## RAG (Retrieval-Augmented Generation)
 * The [extractTermMatches] method finds FGO-specific proper nouns in the JP text
 * and adds their official Chinese translations to the system prompt.
 * This ensures consistent terminology (servant names, Noble Phantasm names, etc.)
 * across all translations regardless of the LLM backend used.
 */
@Singleton
class PromptBuilder @Inject constructor() {

    companion object {
        const val PROMPT_VERSION = "jp-cn-fgo-target-v56"
        private const val MAX_RAG_TERMS = 5
        private const val MIN_TERM_MATCH_LENGTH = 2
        private val pauseDashPattern = Regex("""[—―─━ー－\-一]{2,}""")
        private val maskPattern = Regex("""\?{3,}|？{3,}|[■□▇█]""")
        private val honorificPattern = Regex("""さん|くん|ちゃん|様|殿|氏""")
        private val addressPronounPattern =
            Regex("""あなた|貴方|あんた|お前|おまえ|貴様|汝|そなた|其方|お主|てめえ?|卿""")
        private val katakanaWordPattern = Regex("""[ァ-ヶｦ-ﾟー]{2,}""")
        private val specialFirstPersonPattern = Regex("""アテシ|アタシ|あたし""")

        /**
         * These blocks are intentionally assembled in a stable order and
         * concatenated into one natural-language prompt.
         *
         * Keeping rare rules conditional lowers prompt noise while preserving the
         * safety rules that must apply to every request.
         */
        private val BASE_TRANSLATION_PROMPT = """
            You localize Fate/Grand Order Japanese text into natural, compact {target_chinese} for an in-game overlay.
            Translate meaning and tone. Use {target_chinese} consistently; do not mix Chinese scripts.
            Do not leave Japanese kana unless a rule says to preserve it.
            """.trimIndent()

        private val CROP_BASE_PROMPT = """
            You translate visible Japanese text from a user-selected Fate/Grand Order screen crop into natural, compact {target_chinese}.
            Translate only the visible source text. Do not infer missing text outside the crop.
            Use {target_chinese} consistently; do not mix Chinese scripts.
            """.trimIndent()

        private val PLAIN_OUTPUT_PROMPT = """
            Return only the translated Chinese text. No notes, markdown, source text, labels, wrappers, or explanations.
            """.trimIndent()

        private val JSON_OBJECT_OUTPUT_PROMPT = """
            Return valid JSON only with the keys requested by the user message. No extra keys, notes, markdown, source text, labels, or explanations.
            """.trimIndent()

        private val JSON_ARRAY_OUTPUT_PROMPT = """
            Return a valid JSON array only. Preserve item count and input order. No notes, markdown, source text, labels, or explanations.
            """.trimIndent()

        private val PLACEHOLDER_PROMPT = """
            - Keep every placeholder token starting with __FGO unchanged exactly.
            """.trimIndent()

        private val MASK_PROMPT = """
            - Preserve hidden or mask text such as ???, ？？？, ■, □, ▇, and █ exactly; never guess hidden text.
            """.trimIndent()

        private val PLAYER_NAME_PROMPT = """
            - Player name: "{player_name}". Keep it exactly if it appears.
            """.trimIndent()

        private val DIALOGUE_STYLE_PROMPT = """
            - Preserve speaker voice and relationship: regal, archaic, casual, childish, robotic, sarcastic, solemn, intimate, hostile, or playful.
            - Preserve ambiguity when natural; add pronouns only when the source clearly identifies the referent. Use 他 unless a female referent is clear.
            """.trimIndent()

        private val LINE_BREAK_PROMPT = """
            - Preserve source line breaks only when meaningful.
            """.trimIndent()

        private val MASTER_PROMPT = """
            - Translate マスター as 御主 by default in FGO dialogue, not 主人, 大师, or Master unless clearly an English UI label.
            """.trimIndent()

        private val CROP_STYLE_PROMPT = """
            - Preserve the source line order.
            - Treat each OCR line as visible screen text; do not merge, split, drop, or add rows.
            - If the source is a partial sentence, translate only the visible part naturally; do not complete it.
            - Preserve numbers, percentages, levels, ranks, icons-as-text, and item counts.
            - If the text is dialogue, preserve tone and speaker voice naturally.
            - If the text is UI, profile, skill, item, mission, or battle text, translate concisely like game UI text.
            - Do not add speaker names, labels, or missing context that is not visible in the crop.
            """.trimIndent()

        private val VOICE_HINT_PROMPT = """
            - voice_hint describes delivery only and must not change translation; use null when unclear.
            """.trimIndent()

        private val CHOICE_PROMPT = """
            - When player choices are requested as output, keep each option short, natural, and in the same order; do not merge, split, or explain them.
            """.trimIndent()

        private val NAME_PROMPT = """
            - Unknown names and proper nouns must be natural Chinese transliterations, not descriptions or another known character.
            - If a name is not in the glossary, transliterate it as a concise {target_chinese} Fate/Grand Order/TYPE-MOON-style name.
            - For speaker name-box text, every visible part belongs to the rendered name. Preserve and translate visible annotations such as base《role》, base（state）, titles, suffixes, and question marks; do not drop them as pronunciation ruby.
            - Never return an unknown Japanese name unchanged.
            """.trimIndent()

        private val RUBY_PROMPT = """
            - Source may contain ruby/furigana in base《ruby》 form.
            - Always render every visible ruby pair in Chinese base《ruby》 form; do not omit ruby even when it is pronunciation-only, similar, or the same meaning.
            - Translate the base naturally and translate/render the ruby text inside 《》.
            - Compact English is allowed inside 《》 when the ruby itself is English-style and it reads naturally in Chinese.
            - Do not use parentheses for ruby; use 《》 for every returned ruby pair.
            """.trimIndent()

        private val PAUSE_PROMPT = """
            - Preserve dramatic pauses naturally.
            - Normalize pause dots to compact …… and long dash pauses to ───.
            """.trimIndent()

        private val HONORIFIC_PROMPT = """
            - Name suffixes: さん -> 桑, くん -> 君, ちゃん -> 酱, 様/殿/氏 unchanged.
            - Apply only when attached to a name or player name.
            - Do not apply suffix rules to common words such as 皆さん, みなさん, 赤ちゃん, お父さん, お母さん, お兄さん, お姉さん, お客さん, おじさん, おばさん, たくさん, or 彼氏.
            - Name plural ズ means an English-style group marker; use X们 by default.
            """.trimIndent()

        private val ADDRESS_PRONOUN_PROMPT = """
            - Japanese second-person address forms such as あなた, 貴方, あんた, お前, おまえ, 貴様, 汝, そなた, 其方, お主, てめえ, and 卿 should be translated by tone and relationship.
            - Do not leave these words as Japanese or treat them as names.
            """.trimIndent()

        private val KATAKANA_STYLE_PROMPT = """
            - Katakana common English-style words may stay compact English when natural.
            - Do not apply this to names, organizations, classes, Noble Phantasms, skills, or protected placeholders.
            - Translate or Chinese-transliterate unprotected kana yokai names, nicknames, and attack-like terms; do not leave them as kana.
            """.trimIndent()

        private val SPECIAL_FIRST_PERSON_PROMPT = """
            - アテシ, アタシ, and あたし are first-person pronouns, not names.
            - Translate them by speaker voice as 我, 咱, or 人家.
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
        isRetry: Boolean = false
    ): PromptContext {
        val combinedText = (listOf(sourceText) + choiceTexts)
            .joinToString("\n")
        val cleanPlayerName = playerName.trim()
        return PromptContext(
            outputFormat = outputFormat,
            targetChineseLocale = SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale),
            isCropMode = isCropMode,
            isDialogue = isDialogue,
            requestVoiceHint = requestVoiceHint,
            hasPlaceholders = containsPlaceholder(combinedText),
            hasMasks = containsMask(combinedText),
            hasLineBreaks = containsLineBreak(sourceText) || choiceTexts.any(::containsLineBreak),
            hasMasterWord = containsMasterWord(combinedText),
            needsPlayerNameRule = cleanPlayerName.isNotBlank() && combinedText.contains(cleanPlayerName),
            hasChoices = choiceTexts.isNotEmpty(),
            hasName = hasName,
            hasRuby = !isCropMode && (forceRuby || containsRuby(combinedText)),
            hasPauseMarks = containsPauseMarks(combinedText),
            hasHonorifics = containsHonorifics(combinedText),
            hasKatakana = containsKatakanaWord(combinedText),
            hasAddressPronouns = containsAddressPronoun(combinedText),
            hasSpecialFirstPerson = containsSpecialFirstPerson(combinedText),
            hasAmbiguousRoman = containsAmbiguousRoman(combinedText),
            isRetry = isRetry
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
            if (context.hasChoices) add("choices" to CHOICE_PROMPT)
            if (context.hasName) add("name" to NAME_PROMPT)
            if (context.hasRuby) add("ruby" to RUBY_PROMPT)
            if (context.hasPauseMarks) add("pause" to PAUSE_PROMPT)
            if (context.hasHonorifics) add("honorific" to HONORIFIC_PROMPT)
            if (context.hasAddressPronouns) add("address_pronoun" to ADDRESS_PRONOUN_PROMPT)
            if (context.hasKatakana) add("katakana_style" to KATAKANA_STYLE_PROMPT)
            if (context.hasSpecialFirstPerson) add("special_first_person" to SPECIAL_FIRST_PERSON_PROMPT)
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
        choiceTexts: List<String>,
        targetChineseLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    ): String {
        val sb = StringBuilder()
        val targetChinese = targetChinesePromptLabel(targetChineseLocale)

        sb.append("Translate this Fate/Grand Order Japanese text into $targetChinese for the in-game overlay.\n")
        sb.append("Return only the translated Chinese text that should appear on screen.\n\n")

        // Prepend choice context if present — helps the LLM understand
        // that these are separate interactive elements, not dialogue lines
        if (choiceTexts.isNotEmpty()) {
            sb.append("Choice context only. Do not output these choices; use them only to understand the scene, and translate only the main dialogue text:\n")
            for ((i, choice) in choiceTexts.withIndex()) {
                sb.append("[Choice ${i + 1}] $choice\n")
            }
            sb.append("\nMain dialogue text:\n")
        }

        if (japaneseText.contains("__FGO")) {
            sb.append("Keep each full placeholder token starting with __FGO unchanged exactly. Do not translate or edit characters inside placeholders.\n\n")
        }
        sb.append(japaneseText)

        FgoLogger.debug(tag, "User prompt: ${sb.length} chars, choices=${choiceTexts.size}")
        return sb.toString()
    }

    fun buildCropUserPrompt(
        japaneseText: String,
        targetChineseLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    ): String {
        val sb = StringBuilder()
        val targetChinese = targetChinesePromptLabel(targetChineseLocale)
        val lines = japaneseText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        sb.append("Translate each cropped Fate/Grand Order OCR line into $targetChinese.\n")
        sb.append("Return a JSON array with exactly ${lines.size} string(s), in the same order.\n")
        sb.append("Do not merge, split, drop, add, explain, or infer text outside the crop.\n\n")
        if (japaneseText.contains("__FGO")) {
            sb.append("Keep each full placeholder token starting with __FGO unchanged exactly. Do not translate or edit characters inside placeholders.\n\n")
        }
        sb.append("Cropped OCR lines:\n")
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
        return honorificPattern.containsMatchIn(text)
    }

    private fun containsAddressPronoun(text: String): Boolean {
        return addressPronounPattern.containsMatchIn(text)
    }

    private fun containsKatakanaWord(text: String): Boolean {
        return katakanaWordPattern.containsMatchIn(text)
    }

    private fun containsSpecialFirstPerson(text: String): Boolean {
        return specialFirstPersonPattern.containsMatchIn(text)
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
