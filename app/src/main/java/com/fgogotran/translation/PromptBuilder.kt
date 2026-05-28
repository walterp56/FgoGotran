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
 * 1. 7 translation rules (terminology, style, names, player name, format, context, fallback)
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
        const val PROMPT_VERSION = "jp-cn-fgo-simplified-v7"
        private const val MAX_RAG_TERMS = 10
        private const val MIN_TERM_MATCH_LENGTH = 2

        /**
         * System prompt with 7 translation rules.
         *
         * Rule 1 (TERMINOLOGY): Forces the LLM to use the provided glossary rather than
         *   inventing translations for FGO proper nouns.
         * Rule 4 (PLAYER NAME): The user's Master name gets special treatment because
         *   FGO dialogue often addresses the player directly with honorifics.
         * Rule 5 (FORMAT): Strict "no explanations" constraint — the output goes directly
         *   into the overlay, so any extra text would be visible to the user.
         * Rule 7 (UNKNOWN TERMS): Phonetic transliteration prevents the LLM from
         *   hallucinating a creative translation for terms not in the glossary.
         */
        private val SYSTEM_PROMPT = """
You are translating Fate/Grand Order game dialogue from Japanese to Simplified Chinese.

Rules (MUST follow):
1. TERMINOLOGY: Use ONLY the official Chinese translations provided below for proper nouns. Never invent new translations.
2. STYLE: Maintain the original speaker's tone. Use natural conversational Chinese appropriate for game dialogue.
3. NAMES: Never translate servant/character names creatively. Use ONLY the official Chinese names.
4. PLAYER NAME: The player's name is "{player_name}". When this name appears (with or without honorifics like さん/殿/君), use "{player_name}" in Chinese.
5. FORMAT: Return ONLY the translated Chinese text. No explanations, no notes, no markdown.
6. CONTEXT AWARENESS: If the text contains "[Choice]" labels, translate the choice text while keeping the structure clear.
7. UNKNOWN TERMS: If you encounter a proper noun not in the terminology list, transliterate it phonetically into Chinese.
8. SCRIPT: Use Simplified Chinese characters. If a supplied official term is Traditional Chinese, convert it to natural Simplified Chinese unless it is a fixed proper noun or official stylized name.
""".trimIndent()
    }

    private val tag = "PromptBuilder"

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

        // Append matched terminology as a reference table for the LLM
        if (matchedTerms.isNotEmpty()) {
            sb.append("\n\n=== OFFICIAL TERMINOLOGY ===\n")
            for (term in matchedTerms) {
                sb.append("${term.jpTerm} -> ${term.cnTerm} [${term.category}]\n")
            }
        }

        FgoLogger.debug(tag, "System prompt: ${sb.length} chars, ${matchedTerms.size} RAG terms")
        return sb.toString()
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
            sb.append("This dialogue includes player choices:\n")
            for ((i, choice) in choiceTexts.withIndex()) {
                sb.append("[Choice ${i + 1}] $choice\n")
            }
            sb.append("\nMain dialogue text:\n")
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
        val normalizedText = normalizeForTermMatch(japaneseText)
        if (normalizedText.isBlank()) return emptyList()

        val matches = terms.asSequence()
            .mapNotNull { term ->
                val matchedLength = longestMatchedNeedleLength(normalizedText, term)
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

    private fun longestMatchedNeedleLength(text: String, term: TermEntity): Int {
        return candidateNeedles(term)
            .filter { text.contains(it) }
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

    private fun normalizeForTermMatch(text: String): String {
        return Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
            .replace(Regex("""[\s　]+"""), "")
            .replace(Regex("""[・･·•,，、。.!！?？:：;；\[\]（）()「」『』"“”'’‘=＝\-－—―_＿]"""), "")
    }
}
