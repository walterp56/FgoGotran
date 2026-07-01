package com.fgogotran.translation

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
    val hasChoices: Boolean = false,
    val hasName: Boolean = false,
    val hasRuby: Boolean = false,
    val hasMasks: Boolean = false,
    val hasPauseMarks: Boolean = false,
    val hasHonorifics: Boolean = false,
    val hasKatakana: Boolean = false,
    val hasSpecialFirstPerson: Boolean = false,
    val hasAmbiguousRoman: Boolean = false,
    val isBatch: Boolean = false,
    val isRetry: Boolean = false
)

/**
 * Constructs system and user prompts for the LLM translation backends.
 *
 * ## System prompt structure
 * 1. Core role, priority rules, output contract, and general style
 * 2. Small conditional rule blocks for the current source shape
 * 3. Injected RAG terminology table (JP → official CN with category)
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
        const val PROMPT_VERSION = "jp-cn-fgo-simplified-v37"
        private const val MAX_RAG_TERMS = 5
        private const val MIN_TERM_MATCH_LENGTH = 2
        private val hiddenOrMaskedTextPattern = Regex("""[■□▇█]+|[?？]{3,}""")
        private val pauseDashPattern = Regex("""[—―─━ー－\-一]{2,}""")
        private val honorificPattern = Regex("""さん|君|ちゃん|様|殿|氏""")
        private val katakanaWordPattern = Regex("""[ァ-ヶｦ-ﾟー]{2,}""")
        private val specialFirstPersonPattern = Regex("""アテシ|アタシ|あたし""")

        /**
         * These blocks are intentionally assembled in a stable order:
         * core -> priority -> output -> style -> conditional rules -> RAG table.
         *
         * Keeping rare rules conditional lowers prompt noise while preserving the
         * safety rules that must apply to every request.
         */
        private val CORE_PROMPT = """
You localize Fate/Grand Order Japanese story text into natural, compact Simplified Chinese for an in-game overlay.
Translate meaning, tone, and speaker voice. Prefer short readable Chinese over long literal wording.
Use Simplified Chinese internally; the app may convert the final result to Traditional Chinese later.
""".trimIndent()

        private val PRIORITY_RULES_PROMPT = """
Priority rules:
1. Keep every placeholder starting with __FGOTERM_ or __FGOPLAYER_ unchanged exactly, including _PLURAL__, _KUN__, _CHAN__, _SAMA__, _TONO__, _SHI__, and _MASTER__ variants.
2. Preserve mask blocks such as ■, □, ▇, and █ exactly; never guess hidden text.
3. Player name: "{player_name}". Keep it exactly if it appears, even if it contains Japanese kana.
4. Use supplied official terminology exactly. It overrides your knowledge and natural alternatives.
5. Text inside <keep id="n">...</keep> is already translated official Chinese. Use its meaning in the sentence, keep the inner text exactly, and do not output the keep tags.
6. If rules conflict, follow this order: placeholders/masks/player name > official terminology > requested output format > meaning > style.
7. Do not leave Japanese kana unless it is the player name, an unchanged placeholder, a preserved mask, or fixed official stylized terminology.
""".trimIndent()

        private val OUTPUT_RULES_PROMPT = """
Output:
- Follow the user's requested format exactly: plain text, JSON array, or JSON object.
- Add no notes, markdown, source text, explanations, lore commentary, or extra JSON keys.
- For JSON output, return valid JSON only.
""".trimIndent()

        private val GENERAL_STYLE_PROMPT = """
General FGO style:
- Preserve speaker voice and relationship: regal, archaic, casual, childish, robotic, sarcastic, solemn, intimate, hostile, or playful.
- Keep dialogue concise for a two-line FGO dialogue box. Do not over-explain lore or add hard line breaks unless the source clearly uses separate rows.
- Japanese may omit subjects/objects. Add 你/我/他/她 only when Chinese needs it. Use 他 when gender is unknown or male. If the source clearly says 彼女, 彼女たち, 女の子, 女性, 少女, 姫, 王女, 女王, 女神, 魔女, 娘, 妹, 姉, 母, or another explicit female referent, use 她.
- Translate マスター as 御主 by default in FGO dialogue, not 主人, 大师, or Master unless clearly an English UI label.
""".trimIndent()

        private val CHOICE_PROMPT = """
Choice rules:
- Translate choices as short player-facing options in the same order.
- Preserve intent and emotional nuance, but avoid making choices long.
- Do not merge, split, reorder, or explain choices.
""".trimIndent()

        private val BATCH_PROMPT = """
Batch/list rules:
- Keep every item aligned one-to-one with input order.
- Do not merge, split, skip, or add items.
""".trimIndent()

        private val NAME_PROMPT = """
Name rules:
- Unknown Servant, character, NPC, place, organization, class, skill, and Noble Phantasm names must be natural Chinese transliterations, not descriptions or another known FGO name.
- If a name is not in the glossary, transliterate it as a concise Simplified Chinese Fate/Grand Order character name.
- Never return an unknown Japanese name unchanged.
""".trimIndent()

        private val RUBY_PROMPT = """
Ruby/furigana rules:
- Source may contain base《ruby》.
- Omit pronunciation-only ruby.
- If ruby adds alias, joke, hidden meaning, or intended wording, reflect it naturally.
- Use a short Chinese parenthetical only when both meanings matter. Do not mechanically output base（ruby）.
""".trimIndent()

        private val MASK_PROMPT = """
Mask/hidden-text rules:
- Preserve hidden text such as ???, ？？？, ■, □, ▇, and █ exactly.
- Do not guess hidden speaker names or hidden dialogue content.
- If a line has too little readable text, return that line unchanged or empty according to the requested format.
""".trimIndent()

        private val PAUSE_PROMPT = """
Pause symbol rules:
- Preserve dramatic pauses naturally.
- In FGO dialogue, normalize pause dots to compact ……: OCR variants like ··, ······, ・・, ・・・, .., ..., …, ……, or ……… should render as …….
- Normalize horizontal line pauses to ———: OCR variants like ——, ———, ----, ーーー, ───, or standalone 一一一 should render as ———.
""".trimIndent()

        private val HONORIFIC_PROMPT = """
Honorific rules:
- Name suffixes: さん -> 桑, 君 -> 君, ちゃん -> 酱, 様/殿/氏 unchanged.
- Apply only when attached to a name or player name.
- Do not apply to common words such as 皆さん, みなさん, 赤ちゃん, お父さん, お母さん, お兄さん, お姉さん, お客さん, おじさん, おばさん, たくさん, or 彼氏.
- Name plural ズ means an English-style group marker. Use X们 by default; use X组 or X队 only when context clearly means a team/unit.
""".trimIndent()

        private val KATAKANA_STYLE_PROMPT = """
Katakana style rules:
- Katakana common English-style words may stay compact English when natural.
- Do not apply this to character names, organizations, classes, Noble Phantasms, skills, or supplied official terms.
""".trimIndent()

        private val SPECIAL_FIRST_PERSON_PROMPT = """
First-person pronoun rules:
- アテシ, アタシ, and あたし are first-person pronouns, not names.
- Translate them by speaker voice as 我, 咱, or 人家, including when they appear sentence-final after punctuation.
""".trimIndent()

        private val AMBIGUOUS_ROMAN_PROMPT = """
Ambiguous name/common-word rule:
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
        hasName: Boolean = false,
        isBatch: Boolean = false,
        forceRuby: Boolean = false,
        isRetry: Boolean = false
    ): PromptContext {
        val combinedText = (listOf(sourceText) + choiceTexts)
            .joinToString("\n")
        return PromptContext(
            outputFormat = outputFormat,
            hasChoices = choiceTexts.isNotEmpty(),
            hasName = hasName,
            hasRuby = forceRuby || containsRuby(combinedText),
            hasMasks = containsHiddenOrMaskedText(combinedText),
            hasPauseMarks = containsPauseMarks(combinedText),
            hasHonorifics = containsHonorifics(combinedText),
            hasKatakana = containsKatakanaWord(combinedText),
            hasSpecialFirstPerson = containsSpecialFirstPerson(combinedText),
            hasAmbiguousRoman = containsAmbiguousRoman(combinedText),
            isBatch = isBatch,
            isRetry = isRetry
        )
    }

    /**
     * Builds the system prompt with injected RAG terminology and player name.
     *
     * @param matchedTerms FGO terms found in the current dialogue (from [extractTermMatches])
     * @param playerName the user's FGO Master name for personalization
     * @return complete system prompt string ready to send to the LLM
     */
    fun buildSystemPrompt(
        matchedTerms: List<TermEntity>,
        playerName: String,
        context: PromptContext = PromptContext()
    ): String {
        val sb = StringBuilder()
        val blockNames = mutableListOf<String>()
        appendPromptBlock(sb, blockNames, "core", CORE_PROMPT)
        appendPromptBlock(
            sb,
            blockNames,
            "priority",
            PRIORITY_RULES_PROMPT.replace("{player_name}", playerName.ifBlank { "Master" })
        )
        appendPromptBlock(sb, blockNames, "output", OUTPUT_RULES_PROMPT)
        appendPromptBlock(sb, blockNames, "style", GENERAL_STYLE_PROMPT)
        conditionalPromptBlocks(context).forEach { (name, block) ->
            appendPromptBlock(sb, blockNames, name, block)
        }
        if (appendMatchedTerminology(sb, matchedTerms)) {
            blockNames += "terminology_table"
        }
        FgoLogger.debug(
            tag,
            "System prompt combination: format=${context.outputFormat.logName}, " +
                "blocks=${blockNames.joinToString("+")}, rag=${matchedTerms.size}, chars=${sb.length}"
        )
        return sb.toString()
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

    private fun conditionalPromptBlocks(context: PromptContext): List<Pair<String, String>> {
        return buildList {
            if (context.isBatch) add("batch" to BATCH_PROMPT)
            if (context.hasChoices) add("choices" to CHOICE_PROMPT)
            if (context.hasName) add("name" to NAME_PROMPT)
            if (context.hasRuby) add("ruby" to RUBY_PROMPT)
            if (context.hasMasks) add("mask" to MASK_PROMPT)
            if (context.hasPauseMarks) add("pause" to PAUSE_PROMPT)
            if (context.hasHonorifics) add("honorific" to HONORIFIC_PROMPT)
            if (context.hasKatakana) add("katakana_style" to KATAKANA_STYLE_PROMPT)
            if (context.hasSpecialFirstPerson) add("special_first_person" to SPECIAL_FIRST_PERSON_PROMPT)
            if (context.hasAmbiguousRoman) add("ambiguous_roman" to AMBIGUOUS_ROMAN_PROMPT)
        }
    }

    private fun appendMatchedTerminology(sb: StringBuilder, matchedTerms: List<TermEntity>): Boolean {
        if (matchedTerms.isEmpty()) return false

        sb.append("\n\n=== OFFICIAL TERMINOLOGY (MUST USE) ===\n")
        for (term in matchedTerms) {
            sb.append("${term.jpTerm} -> ${term.cnTerm} [${term.category}]\n")
        }
        return true
    }

    /**
     * Builds the user prompt containing the JP text to translate.
     *
     * @param japaneseText the dialogue text from OCR
     * @param choiceTexts optional player choice strings appearing on the same screen
     * @return complete user prompt string
     */
    fun buildUserPrompt(japaneseText: String, choiceTexts: List<String>): String {
        val sb = StringBuilder()

        // Prepend choice context if present — helps the LLM understand
        // that these are separate interactive elements, not dialogue lines
        if (choiceTexts.isNotEmpty()) {
            sb.append("This dialogue includes player choices. Translate choices as short player-facing Simplified Chinese; keep intent, order, and concise tone:\n")
            for ((i, choice) in choiceTexts.withIndex()) {
                sb.append("[Choice ${i + 1}] $choice\n")
            }
            sb.append("\nMain dialogue text:\n")
        }

        if (japaneseText.contains("__FGOTERM_") || japaneseText.contains("__FGOPLAYER_")) {
            sb.append("Keep each full placeholder token starting with __FGOTERM_ or __FGOPLAYER_ unchanged exactly. Do not translate or edit characters inside placeholders.\n\n")
        }
        if (japaneseText.contains("<keep")) {
            sb.append("Text inside <keep id=\"n\">...</keep> is locked official Chinese. Use its meaning, keep the inner text exactly, remove the keep tags, and translate all Japanese outside tags.\n\n")
        }

        sb.append(japaneseText)

        FgoLogger.debug(tag, "User prompt: ${sb.length} chars, choices=${choiceTexts.size}")
        return sb.toString()
    }

    private fun containsRuby(text: String): Boolean {
        return '《' in text && '》' in text
    }

    private fun containsHiddenOrMaskedText(text: String): Boolean {
        return hiddenOrMaskedTextPattern.containsMatchIn(text)
    }

    private fun containsPauseMarks(text: String): Boolean {
        return FgoDialogueSymbols.containsLongPause(text) ||
                pauseDashPattern.containsMatchIn(text)
    }

    private fun containsHonorifics(text: String): Boolean {
        return honorificPattern.containsMatchIn(text)
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
