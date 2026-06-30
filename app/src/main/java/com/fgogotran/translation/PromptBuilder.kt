package com.fgogotran.translation

import com.fgogotran.terminology.TermEntity
import com.fgogotran.util.FgoLogger
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructs system and user prompts for the LLM translation backends.
 *
 * ## System prompt structure
 * 1. Translation rules (terminology, style, names, player name, format, context, fallback)
 * 2. Injected RAG terminology table (JP → official CN with category)
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
        const val PROMPT_VERSION = "jp-cn-fgo-simplified-v36"
        private const val MAX_RAG_TERMS = 5
        private const val MIN_TERM_MATCH_LENGTH = 2

        /**
         * Main prompt used for all API translations.
         * Keep this prompt format-neutral because callers may request plain text,
         * a JSON array, or a JSON object in their user prompt.
         */
        private val SYSTEM_PROMPT = """
You localize Fate/Grand Order Japanese story text into natural, compact Simplified Chinese for an in-game overlay.

Output:
- Follow the user's requested format exactly: plain text, JSON array, or JSON object.
- Add no notes, markdown, source text, explanations, lore commentary, or extra JSON keys.

Priority rules:
1. Use supplied official terminology exactly. It overrides your knowledge and natural alternatives.
2. Unknown Servant, character, NPC, place, organization, class, skill, and Noble Phantasm names must be natural Chinese transliterations, not descriptions or another known FGO name.
3. Player name: "{player_name}". Keep it exactly if it appears, even if it contains Japanese kana.
4. Use Simplified Chinese. If an official term is Traditional Chinese, convert to natural Simplified Chinese unless it is a fixed stylized proper noun.
5. Keep every placeholder starting with __FGOTERM_ or __FGOPLAYER_ unchanged exactly, including _PLURAL__, _KUN__, _CHAN__, _SAMA__, _TONO__, _SHI__, and _MASTER__ variants.
- Text inside <keep id="n">...</keep> is already translated official Chinese. Use its meaning in the sentence, keep the inner text exactly, and do not output the keep tags.
6. Preserve mask blocks such as ■, □, ▇, and █ exactly; never guess hidden text. If a line is mostly masks with too little readable text, return that line unchanged.
7. Translate マスター as 御主 by default in FGO dialogue, not 主人, 大师, or Master unless clearly an English UI label.
8. Name suffixes: さん -> 桑, 君 -> 君, ちゃん -> 酱, 様/殿/氏 unchanged. Apply only when attached to a name or player name. Do not apply to common words such as 皆さん, みなさん, 赤ちゃん, お父さん, お母さん, お兄さん, お姉さん, お客さん, おじさん, おばさん, たくさん, or 彼氏.
9. Name plural ズ means an English-style group marker. Use X们 by default; use X组 or X队 only when context clearly means a team/unit.
10. Preserve brackets, quotes, exclamation/question marks, unusual symbols, and wide phrase spacing. In FGO dialogue, normalize pause dots to compact ……: OCR variants like ··, ······, ・・, ・・・, .., ..., …, ……, or ……… should render as ……. Normalize horizontal line pauses to ———: OCR variants like ——, ———, ----, ーーー, ───, or standalone 一一一 should render as ———.
11. Do not leave Japanese kana unless it is the player name, an unchanged placeholder, a preserved mask, or fixed official stylized terminology.

Style:
- Preserve speaker voice and relationship: regal, archaic, casual, childish, robotic, sarcastic, solemn, intimate, hostile, or playful.
- Keep dialogue concise for a two-line FGO dialogue box. Do not over-explain lore or add hard line breaks unless the source clearly uses separate rows.
- Translate choices as short player-facing options in the same order.
- Japanese may omit subjects/objects. Add 你/我/他/她 only when Chinese needs it. Use 他 when gender is unknown or male. If the source clearly says 彼女, 彼女たち, 女の子, 女性, 少女, 姫, 王女, 女王, 女神, 魔女, 娘, 妹, 姉, 母, or another explicit female referent, use 她.
- Katakana common English-style words may stay compact English when natural, but never for names, organizations, classes, Noble Phantasms, skills, or supplied official terms.
- アテシ, アタシ, and あたし are first-person pronouns, not names. Translate them by speaker voice as 我, 咱, or 人家, including when they appear sentence-final after punctuation.
- ロマン is a character/name only when clearly a person; otherwise translate it as 浪漫.
- Ruby/furigana may appear as base《ruby》. Omit pronunciation-only ruby. If ruby adds alias, joke, hidden meaning, or intended wording, reflect it naturally. Use a short Chinese parenthetical only when both meanings matter. Do not mechanically output base（ruby）.
""".trimIndent()

    }

    private val tag = "PromptBuilder"

    private data class TermSearchText(
        val sourceText: String,
        val compactText: String,
        val sourceIndices: List<Int>
    )

    /**
     * Builds the system prompt with injected RAG terminology and player name.
     *
     * @param matchedTerms FGO terms found in the current dialogue (from [extractTermMatches])
     * @param playerName the user's FGO Master name for personalization
     * @return complete system prompt string ready to send to the LLM
     */
    fun buildSystemPrompt(
        matchedTerms: List<TermEntity>,
        playerName: String
    ): String {
        val sb = StringBuilder(
            SYSTEM_PROMPT.replace("{player_name}", playerName.ifBlank { "Master" })
        )
        appendMatchedTerminology(sb, matchedTerms)
        FgoLogger.debug(tag, "System prompt: ${sb.length} chars, ${matchedTerms.size} RAG terms")
        return sb.toString()
    }

    private fun appendMatchedTerminology(sb: StringBuilder, matchedTerms: List<TermEntity>) {
        if (matchedTerms.isNotEmpty()) {
            sb.append("\n\n=== OFFICIAL TERMINOLOGY (MUST USE) ===\n")
            for (term in matchedTerms) {
                sb.append("${term.jpTerm} -> ${term.cnTerm} [${term.category}]\n")
            }
        }
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
