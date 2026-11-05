package com.fgogotran.translation

import com.fgogotran.terminology.CharacterNameEntity
import com.fgogotran.terminology.TermEntity
import com.fgogotran.text.EmphasisText
import java.text.Normalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GlossaryOcrEdit(val start: Int, val before: String, val after: String, val canonical: String)
internal data class GlossaryOcrCorrection(val text: String, val edits: List<GlossaryOcrEdit> = emptyList()) {
    val changed: Boolean get() = edits.isNotEmpty()
}

internal fun glossaryOcrCachePolicy(vararg corrections: GlossaryOcrCorrection?): String =
    if (corrections.any { it?.changed == true }) GlossaryOcrCorrector.CACHE_POLICY else ""

/** Empty policy must preserve every pre-existing cache key byte for byte. */
internal fun extendGlossaryOcrCacheMaterial(material: List<String>, policy: String): List<String> =
    if (policy.isBlank()) material else material + "ocr=$policy"

/** Immutable, dictionary-bounded one-glyph fallback. Never changes raw OCR or invents name parts. */
internal class GlossaryOcrCorrector(characters: List<CharacterNameEntity>, terms: List<TermEntity>) {
    private data class Form(val text: String, val owner: String)
    private data class Candidate(val surface: String, val owner: String)
    private class Index(forms: List<Form>, minimum: Int) {
        val exact = forms.map { it.text }.filter(String::isNotBlank).toSet()
        val exactBuckets = exact.groupBy { it.first() }.mapValues { (_, forms) -> forms.sortedByDescending(String::length) }
        val variants: Map<String, Candidate?>
        val buckets: Map<Char, List<String>>
        init {
            val generated = mutableMapOf<String, MutableSet<Candidate>>()
            for (form in forms) {
                if (form.text.length !in minimum..MAX_LENGTH || !form.text.all(::isCanonicalWordChar)) continue
                for ((index, char) in form.text.withIndex()) {
                    val mistaken = CONFUSABLES[char] ?: continue
                    val variant = form.text.replaceRange(index, index + 1, mistaken.toString())
                    generated.getOrPut(variant) { linkedSetOf() }.add(Candidate(form.text, form.owner))
                }
            }
            variants = generated.mapValues { (_, candidates) -> candidates.singleOrNull() }
            buckets = variants.keys.groupBy { it.first() }.mapValues { (_, keys) -> keys.sortedByDescending(String::length) }
        }
    }

    private val names = Index(characters.flatMap { row ->
        forms(row.jpName, row.aliases)
    }, 3)
    private val words = Index(terms.flatMap { row -> forms(row.jpTerm, row.aliases) }, 4)

    fun correctName(text: String, playerName: String = ""): GlossaryOcrCorrection {
        if (EmphasisText.has(text) || text.contains('〈')) return GlossaryOcrCorrection(text)
        val key = NameBoxLookupPolicy.lookupKey(text)
        if (key in names.exact || key == NameBoxLookupPolicy.lookupKey(playerName)) return GlossaryOcrCorrection(text)
        // Per-character normalization keeps indices aligned. Multi-codepoint compositions are
        // left alone, even if whole-string NFKC could have made a match.
        val view = searchView(text)
        val start = view.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return GlossaryOcrCorrection(text)
        val end = view.indexOfLast { !it.isWhitespace() } + 1
        val candidate = names.variants[view.substring(start, end)] ?: return GlossaryOcrCorrection(text)
        return replace(text, view, start, candidate)
    }

    fun correctText(text: String, playerName: String = ""): GlossaryOcrCorrection {
        val edits = mutableListOf<GlossaryOcrEdit>()
        val parsed = EmphasisText.decode(text)
        val corrected = run {
            val plain = parsed.text
            val view = searchView(plain)
            val protected = BooleanArray(plain.length)
            fun protect(start: Int, end: Int) { for (i in start until end) protected[i] = true }
            // Ruby readings and configured player literals are not glossary repair targets.
            RUBY.findAll(plain).forEach { protect(it.range.first, it.range.last + 1) }
            if (playerName.isNotBlank()) {
                val player = searchView(playerName)
                var at = view.indexOf(player)
                while (at >= 0) { protect(at, at + player.length); at = view.indexOf(player, at + 1) }
            }
            for (at in view.indices) for (literal in words.exactBuckets[view[at]].orEmpty()) {
                val end = at + literal.length
                if (view.startsWith(literal, at) && boundary(view, at, end)) protect(at, end)
            }
            val output = StringBuilder(plain)
            var at = 0
            while (at < view.length) {
                val matched = words.buckets[view[at]].orEmpty().firstOrNull { variant ->
                    val end = at + variant.length
                    view.startsWith(variant, at) && boundary(view, at, end) &&
                        (at until end).none { protected[it] }
                }
                if (matched == null) { at++; continue }
                val candidate = words.variants[matched]
                if (candidate != null) {
                    val result = replace(plain, view, at, candidate)
                    for (edit in result.edits) {
                        output.replace(edit.start, edit.start + edit.before.length, edit.after)
                        edits += edit
                    }
                }
                // An ambiguous longest match must never fall back to a shorter name part.
                at += matched.length
            }
            output.toString()
        }
        // Every edit is one UTF-16 unit for one unit, so annotation offsets are exact.
        // Do not use generic edit-distance remapping, which can discard ambiguous ranges.
        return GlossaryOcrCorrection(if (edits.isEmpty()) text else EmphasisText.encode(corrected, parsed.ranges), edits)
    }

    private fun replace(original: String, view: String, start: Int, candidate: Candidate): GlossaryOcrCorrection {
        val before = view.substring(start, start + candidate.surface.length)
        val differences = before.indices.filter { before[it] != candidate.surface[it] }
        if (differences.size != 1) return GlossaryOcrCorrection(original)
        val offset = start + differences.single()
        val replacement = candidate.surface[differences.single()]
        if (CONFUSABLES[replacement] != original[offset]) return GlossaryOcrCorrection(original)
        return GlossaryOcrCorrection(original.replaceRange(offset, offset + 1, replacement.toString()),
            listOf(GlossaryOcrEdit(offset, original[offset].toString(), replacement.toString(), candidate.owner)))
    }

    companion object {
        const val CACHE_POLICY = "ocr-glossary-v1"
        private const val MAX_LENGTH = 64
        private val CONFUSABLES = mapOf('ニ' to '二', 'ロ' to '口', 'エ' to '工', 'カ' to '力',
            'タ' to '夕', 'ト' to '卜', 'ヘ' to 'へ')
        private val RUBY = Regex("〈[^〉]*〉")
        fun mightNeedCorrection(text: String): Boolean = text.any { it in CONFUSABLES.values }
        private fun forms(source: String, aliases: String?): List<Form> {
            val owner = NameBoxLookupPolicy.lookupKey(source)
            return (listOf(source) + NameBoxLookupPolicy.storedAliases(aliases)).map {
                Form(NameBoxLookupPolicy.lookupKey(it), owner)
            }.filter { it.text.isNotBlank() }.distinct()
        }
        private fun searchView(text: String): String = text.map { char ->
            Normalizer.normalize(char.toString(), Normalizer.Form.NFKC).singleOrNull() ?: char
        }.joinToString("")
        private fun isCanonicalWordChar(char: Char): Boolean = char in '\u30A1'..'\u30FA' || char == 'ー' ||
            char in "・· ＝=／/＆&－-"
        private fun isWordChar(char: Char?): Boolean = char != null &&
            (char in '\u30A1'..'\u30FA' || char in '\uFF66'..'\uFF9D' || char == 'ー' ||
                char in '\u31F0'..'\u31FF' || char in CONFUSABLES.values || char == 'ｰ')
        private fun boundary(text: String, start: Int, end: Int): Boolean =
            !isWordChar(text.getOrNull(start - 1)) && !isWordChar(text.getOrNull(end))
    }
}

/** Same generation guard as the character snapshot: never publish an index built across reloads. */
internal class GlossaryOcrCorrectorCache(
    private val loadCharacters: suspend () -> List<CharacterNameEntity>,
    private val loadTerms: suspend () -> List<TermEntity>
) {
    private val lock = Any()
    private val loading = Mutex()
    private var generation = Any()
    private var cached: GlossaryOcrCorrector? = null

    fun clear() = synchronized(lock) { generation = Any(); cached = null }
    suspend fun snapshot(): GlossaryOcrCorrector {
        while (true) {
            synchronized(lock) { cached?.let { return it } }
            val result = loading.withLock {
                val token = synchronized(lock) { cached?.let { return@withLock it }; generation }
                val characters = loadCharacters()
                val terms = loadTerms()
                val candidate = GlossaryOcrCorrector(characters, terms)
                synchronized(lock) {
                    if (token === generation) { cached = candidate; candidate } else null
                }
            }
            if (result != null) return result
        }
    }
}
