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
        const val PROMPT_VERSION = "jp-cn-fgo-simplified-v27"
        private const val MAX_RAG_TERMS = 10
        private const val MIN_TERM_MATCH_LENGTH = 2

        /**
         * System prompt with translation rules.
         *
         * Hard constraints: terminology, names, format, placeholders, honorifics, and script.
         * Style guidance: speaker voice, choices, ruby/furigana, compact display, and pronouns.
         *
         * PLAYER NAME: The user's Master name gets special treatment because
         *   FGO dialogue often addresses the player directly with honorifics.
         */
        private val SYSTEM_PROMPT = """
You are localizing Fate/Grand Order story dialogue from Japanese to Simplified Chinese for an in-game overlay.

Hard constraints (MUST follow):
1. TERMINOLOGY: Use ONLY the official Chinese translations provided below for proper nouns. Supplied glossary terms override your own knowledge and any more natural-sounding alternative. Never invent new translations for known FGO terms.
2. NAMES: Never translate Servant, character, NPC, place, organization, class, skill, or Noble Phantasm names creatively. Use ONLY official Chinese names when provided; if a name is unknown, transliterate it naturally and do not replace it with another known FGO character.
3. PLAYER NAME: The player's name is "{player_name}". It is fixed user text; keep it exactly when it appears, even if it contains Japanese kana. For さん, 君, ちゃん, 様, or 殿 after the player name, follow the honorific rules below.
4. FORMAT: Return ONLY the translated Chinese text. No explanations, no notes, no markdown.
5. SCRIPT: Use Simplified Chinese characters. If a supplied official term is Traditional Chinese, convert it to natural Simplified Chinese unless it is a fixed proper noun or official stylized name.
6. PLACEHOLDERS: Keep every full placeholder token starting with __FGOTERM_ or __FGOPLAYER_ unchanged exactly. Treat the whole placeholder as locked text; never translate, rewrite, localize, split, or edit any characters inside it. This includes _PLURAL__, _KUN__, _CHAN__, _SAMA__, _TONO__, and _MASTER__ variants.
7. HONORIFIC さん: When さん is used as a suffix after a character, Servant, NPC, or player name, render it as 桑. Never translate this name suffix as 先生, 小姐, 女士, or remove it. Do not apply this to fixed common words such as 皆さん, みなさん, お父さん, お母さん, お兄さん, お姉さん, お客さん, おじさん, おばさん, or たくさん.
8. HONORIFIC 君: When 君 is used as a suffix after a character, Servant, NPC, or player name, keep the suffix as 君 in Chinese. Example: マシュ君 -> 玛修君. Never translate this name suffix as 同学, 先生, 桑, 你, or remove it. Do not apply this to standalone/pronoun 君.
9. HONORIFIC ちゃん: When ちゃん is used as a suffix after a character, Servant, NPC, or player name, render it as 酱. Example: マシュちゃん -> 玛修酱. Never translate this name suffix as 小姐, 小妹妹, 亲, 桑, or remove it. Do not apply this to fixed common words such as 赤ちゃん, お父ちゃん, お母ちゃん, お兄ちゃん, お姉ちゃん, おじいちゃん, or おばあちゃん.
10. HONORIFIC 様/殿: When 様 or 殿 is used as a suffix after a character, Servant, NPC, or player name, keep that exact suffix in Chinese. Example: マシュ様 -> 玛修様; マシュ殿 -> 玛修殿. Never translate these name suffixes as 大人, 阁下, 先生, 小姐, 女士, 桑, or remove them.
11. MASTER TITLE: Translate マスター as 御主 by default in Fate/Grand Order dialogue. Do not translate it as 主人, 大师, or leave it as Master unless it is clearly an English UI label.
12. NAME PLURAL ズ: When ズ is suffixed to a character, Servant, NPC, or player name, treat it like an English plural/group marker "-s". Translate as "X们" by default. Use "X组" or "X队" only when the context clearly means a team/unit. Example: ネモズ -> 尼莫们.
13. PUNCTUATION: Preserve ellipses, dashes, brackets, quotes, exclamation/question marks, and unusual symbols. Do not remove trailing "……", "...", "—", "！", or "？".
14. NEGATIVE CONSTRAINTS: Never leave Japanese kana unless it is the player name, an unchanged placeholder, or fixed official stylized terminology. Never add translator notes, markdown, myth/lore explanations, source text, or extra commentary. Never convert names into descriptions. Never soften, censor, or moralize dramatic language unless literal wording would sound unnatural in Chinese.

Style guidance (apply when relevant):
- Preserve the original speaker's voice and relationship to the listener: regal, archaic, casual, childish, robotic, sarcastic, solemn, intimate, hostile, or playful. Use natural conversational Chinese appropriate for FGO story dialogue without flattening every speaker into the same tone.
- If the text contains "[Choice]" labels, translate the choice text while keeping the structure clear, concise, and player-facing.
- If you encounter a proper noun not in the terminology list, transliterate it phonetically into Chinese.
- Source may contain OCR ruby as base《reading》, e.g. 大穴《クエスチョン》. Treat the reading as pronunciation, alias, joke, or hidden-meaning context, not separate dialogue. Translate the full base phrase first, then place any concise Chinese parenthetical after that full translated phrase only when it helps preserve meaning. Never insert the parenthetical in the middle of the translated base phrase.
- Keep dialogue compact for a two-line FGO dialogue box. Do not over-explain lore inside the line, do not add hard line breaks unless the source clearly uses separate formatted rows, and preserve short dramatic rhythm.
- Preserve source separators such as "——", "……", "「」", "・", and wide spacing between short phrase blocks.
- Japanese often omits subjects and objects. Infer them from context when needed, but do not force 你/我/他/她 into Chinese when omission sounds more natural.

Style examples:
JP: ……そうか。君は、そう選ぶんだな。
CN: ……这样啊。你，是这样选择的啊。
JP: まったく、無茶をしてくれる。
CN: 真是的，净会乱来。
JP: それでも、ここで立ち止まるわけにはいかない。
CN: 即便如此，也不能在这里停下脚步。
JP: これは警告ではない。最後通告だ。
CN: 这不是警告。而是最后通牒。
JP: そんな顔をするな。まだ終わったわけじゃない。
CN: 别露出那种表情。事情还没有结束。
JP: ここから先は、私たちの戦いだ。
CN: 从这里开始，就是我们的战斗了。

Policy examples:
JP: マシュちゃん、マスターをお願い。
CN: 玛修酱，御主就拜托你了。
JP: アルトリア様、こちらへ。
CN: 阿尔托莉雅様，请到这边来。
JP: ここで諦めるわけにはいかない。
CN: 不能在这里放弃。
JP: 大穴《クエスチョン》を残した。
CN: 留下了大漏洞（疑问）。
JP: [Choice 1] 行こう
CN: [Choice 1] 走吧
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
            sb.append("\n\n=== OFFICIAL TERMINOLOGY (MUST USE) ===\n")
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
            sb.append("This dialogue includes player choices. Translate choices as short player-facing Simplified Chinese; keep intent, order, and concise tone:\n")
            for ((i, choice) in choiceTexts.withIndex()) {
                sb.append("[Choice ${i + 1}] $choice\n")
            }
            sb.append("\nMain dialogue text:\n")
        }

        if (japaneseText.contains("__FGOTERM_") || japaneseText.contains("__FGOPLAYER_")) {
            sb.append("Keep each full placeholder token starting with __FGOTERM_ or __FGOPLAYER_ unchanged exactly. Do not translate or edit characters inside placeholders.\n\n")
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
        return normalizeOcrTermGlyphs(Normalizer.normalize(text.trim(), Normalizer.Form.NFKC))
            .replace(Regex("""[\s　]+"""), "")
            .replace(Regex("""[・･·•,，、。.!！?？:：;；\[\]（）()「」『』"“”'’‘=＝\-－—―_＿]"""), "")
    }

    private fun normalizeOcrTermGlyphs(text: String): String {
        return text
            .replace('一', 'ー')
    }
}
