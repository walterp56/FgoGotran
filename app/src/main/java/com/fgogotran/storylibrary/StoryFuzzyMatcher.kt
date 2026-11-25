package com.fgogotran.storylibrary

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Approximate matcher for chapter-database lookups.
 *
 * The published `fgogotran-ocr-v2` contract is an exact one: normalized OCR text
 * either produces the stored hash or it does not. Real captures still differ from
 * the source in ways normalization cannot absorb — a wave dash read as `〜` while
 * the source uses `～`, `ー` recognized as `一`, a small katakana promoted to full
 * size (`スラニョっ` captured as `スラニヨっ`), one dropped or doubled character —
 * so an exact-only lookup silently loses otherwise perfect lines.
 *
 * This matcher scores character-bigram sets with a Dice coefficient and accepts a
 * candidate only when it is clearly better than the runner-up (or when every close
 * candidate renders the same line). A rejected ambiguous line keeps the original
 * text instead of borrowing a neighbouring line's translation.
 *
 * While a script is confirmed, [selectInWindow] instead scores the next lines in
 * story order with OCR coverage; that is what turns "line 1 matched, line 2 was
 * only half recognized" into line 2's translation rather than a miss.
 */
internal object StoryFuzzyMatcher {

    /** Minimum Dice score for a dialogue line. */
    const val DIALOGUE_THRESHOLD = 0.72

    /** Choices are short, so they need a stricter score. */
    const val CHOICE_THRESHOLD = 0.78

    /** Speaker names are short and reused, so they need a stricter score. */
    const val NAME_THRESHOLD = 0.75

    /**
     * A partially recognized line is normal inside one confirmed script, so the
     * scene window accepts a lower score than the whole-pack lookup. Coverage
     * (fraction of the OCR bigrams present in the line) carries truncated text.
     */
    const val WINDOW_THRESHOLD = 0.45

    /** Rivals inside the window must trail the winner by this much. */
    private const val WINDOW_MARGIN = 0.10

    /** Skipping the next line needs a much stronger score than taking it. */
    private const val WINDOW_SKIP_EXTRA = 0.15

    /** Candidates within this distance of the best score are treated as rivals. */
    private const val AMBIGUITY_WINDOW = 0.04

    /** Keys shorter than this only match exactly; bigram scores are noise there. */
    private const val MIN_FUZZY_CHARS = 4

    /** Coverage only counts from this capture length; shorter keys are ambiguous. */
    private const val COVERAGE_MIN_CHARS = 4

    /** Below this length the window's single-glyph rule is unsafe. */
    private const val SHORT_EDIT_MIN_CHARS = 3

    private const val LENGTH_SLACK = 2
    private const val LENGTH_RATIO = 0.3

    /** Small preference for the script the reader is currently following. */
    private const val FLOW_SCRIPT_BONUS = 0.03
    private const val FLOW_SEQ_BONUS = 0.02
    private const val FLOW_SEQ_WINDOW = 8

    /** Repeated lines are usually repeated by the same speaker. */
    private const val SPEAKER_BONUS = 0.02

    class Entry(
        val id: Int,
        val key: String,
        val speakerKey: String,
        val scriptId: String,
        val seq: Int,
        private val cn: String,
        private val tw: String,
        private val en: String
    ) {
        internal val length: Int = Character.codePointCount(key, 0, key.length)
        internal val bigrams: LongArray = StoryFuzzyMatcher.bigramsOf(key)

        fun text(locale: StoryLocale): String = when (locale) {
            StoryLocale.CN -> cn
            StoryLocale.TW -> tw
            StoryLocale.EN -> en
        }
    }

    data class Match(val entry: Entry, val score: Double)

    fun query(key: String, speakerKey: String = ""): Entry =
        Entry(-1, key, speakerKey, "", -1, "", "", "")

    fun bigramsOf(key: String): LongArray {
        val count = Character.codePointCount(key, 0, key.length)
        if (count == 0) return LongArray(0)
        if (count == 1) return longArrayOf(key.codePointAt(0).toLong())
        val points = IntArray(count)
        var index = 0
        var offset = 0
        while (offset < key.length) {
            val point = key.codePointAt(offset)
            points[index++] = point
            offset += Character.charCount(point)
        }
        val raw = LongArray(count - 1) { (points[it].toLong() shl 21) xor points[it + 1].toLong() }
        raw.sort()
        var unique = 0
        for (position in raw.indices) {
            if (position == 0 || raw[position] != raw[position - 1]) raw[unique++] = raw[position]
        }
        return raw.copyOf(unique)
    }

    fun score(first: LongArray, second: LongArray): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val shared = sharedCount(first, second)
        return (2.0 * shared) / (first.size + second.size)
    }

    private fun sharedCount(first: LongArray, second: LongArray): Int {
        var left = 0
        var right = 0
        var shared = 0
        while (left < first.size && right < second.size) {
            val a = first[left]
            val b = second[right]
            when {
                a < b -> left++
                a > b -> right++
                else -> {
                    shared++
                    left++
                    right++
                }
            }
        }
        return shared
    }

    /**
     * Score used inside one confirmed script: the better of symmetric similarity
     * and OCR coverage, so a half-read line still matches its own next entry.
     *
     * Coverage only counts when the capture is a prefix of the stored line, because
     * that is what a truncated read looks like: OCR loses the tail, never the head.
     * Without that rule any short fragment scores 1.0 inside a long line that merely
     * contains it, which lets the window jump onto the wrong line.
     */
    fun windowScore(query: Entry, candidate: Entry): Double {
        if (query.length == 0 || candidate.length == 0) return 0.0
        if (query.bigrams.isEmpty() || candidate.bigrams.isEmpty()) return 0.0
        val shared = sharedCount(query.bigrams, candidate.bigrams)
        val dice = (2.0 * shared) / (query.bigrams.size + candidate.bigrams.size)
        val coverage = if (query.length >= COVERAGE_MIN_CHARS && candidate.key.startsWith(query.key)) {
            shared.toDouble() / query.bigrams.size
        } else {
            0.0
        }
        return max(dice, coverage)
    }

    /**
     * Picks the next line inside the story window around the last confirmed match.
     *
     * The window is ordered, so the first candidate is the logical next line. A
     * different candidate may win only with a clearly stronger score, which is how
     * a skipped or missed capture is absorbed without guessing.
     *
     * Very short lines (one to three characters) carry too few character bigrams for
     * a score, so they use position instead: an exact short line anywhere in the
     * window wins, otherwise the immediate next line may absorb a single glyph error
     * (OCR reads `ー` as `一`, or drops one character).
     */
    fun selectInWindow(query: Entry, window: List<Entry>, locale: StoryLocale): Match? {
        if (query.key.isEmpty() || window.isEmpty()) return null
        if (query.length < MIN_FUZZY_CHARS) {
            window.firstOrNull { it.key == query.key }?.let { return Match(it, 1.0) }
            val next = window.first()
            if (query.length >= SHORT_EDIT_MIN_CHARS &&
                next.length >= SHORT_EDIT_MIN_CHARS &&
                withinOneEdit(query.key, next.key)
            ) {
                return Match(next, windowScore(query, next))
            }
            return null
        }
        val scored = ArrayList<Scored>(window.size)
        for (candidate in window) {
            val value = windowScore(query, candidate)
            if (value >= WINDOW_THRESHOLD) scored.add(Scored(candidate, value))
        }
        if (scored.isEmpty()) return null
        scored.sortWith(compareByDescending<Scored> { it.score }.thenBy { it.entry.seq })
        val best = scored.first()
        val next = window.first()
        // Skipping the next line changes the flow, so it must rest on symmetric
        // similarity; coverage alone only justifies taking the line immediately ahead.
        if (best.entry.seq != next.seq &&
            score(query.bigrams, best.entry.bigrams) < WINDOW_THRESHOLD + WINDOW_SKIP_EXTRA
        ) {
            return null
        }
        val rival = scored.firstOrNull { it.entry.text(locale) != best.entry.text(locale) }
        if (rival != null && best.score - rival.score < WINDOW_MARGIN) return null
        return Match(best.entry, best.score)
    }

    /** True when one insertion, deletion, or substitution turns [first] into [second]. */
    private fun withinOneEdit(first: String, second: String): Boolean {
        val a = first.codePoints().toArray()
        val b = second.codePoints().toArray()
        if (abs(a.size - b.size) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            edits++
            if (edits > 1) return false
            when {
                a.size > b.size -> i++
                a.size < b.size -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return edits + (a.size - i) + (b.size - j) <= 1
    }

    /**
     * Picks the single best candidate, or null when the result would be a guess.
     *
     * @param flowScriptId script the reader followed on the previous line, if any.
     * @param flowSeq sequence of the previous match, used to break duplicates.
     */
    fun select(
        query: Entry,
        candidates: List<Entry>,
        threshold: Double,
        locale: StoryLocale,
        flowScriptId: String?,
        flowSeq: Int
    ): Match? {
        if (query.key.isEmpty()) return null
        val tolerance = max(LENGTH_SLACK, ceil(query.length * LENGTH_RATIO).toInt())
        val scored = ArrayList<Scored>(16)
        for (candidate in candidates) {
            if (abs(candidate.length - query.length) > tolerance) continue
            val raw = score(query.bigrams, candidate.bigrams)
            if (raw < threshold) continue
            scored.add(Scored(candidate, raw + evidenceBonus(query, candidate, flowScriptId, flowSeq)))
        }
        if (scored.isEmpty()) return null
        scored.sortWith(
            compareByDescending<Scored> { it.score }.thenBy { abs(it.entry.seq - flowSeq) }
        )
        val best = scored.first()
        if (query.length < MIN_FUZZY_CHARS) {
            // Short lines only ever match the same folded key.
            return if (best.entry.key == query.key) Match(best.entry, best.score) else null
        }
        val rivals = scored.filter { it.score >= best.score - AMBIGUITY_WINDOW }
        if (rivals.size == 1) return Match(best.entry, best.score)
        if (rivals.map { it.entry.text(locale) }.toSet().size == 1) {
            return Match(best.entry, best.score)
        }
        if (flowScriptId != null) {
            val inFlow = rivals.filter { it.entry.scriptId == flowScriptId }
            if (inFlow.isNotEmpty()) {
                val picked = inFlow.minByOrNull { abs(it.entry.seq - flowSeq) }
                if (picked != null) return Match(picked.entry, picked.score)
            }
        }
        return null
    }

    /**
     * Small nudge from the surrounding scene. It only reorders candidates that
     * already cleared [threshold]; it never promotes a weak match.
     */
    private fun evidenceBonus(
        query: Entry,
        entry: Entry,
        flowScriptId: String?,
        flowSeq: Int
    ): Double {
        var bonus = 0.0
        if (query.speakerKey.isNotEmpty() && query.speakerKey == entry.speakerKey) {
            bonus += SPEAKER_BONUS
        }
        if (flowScriptId != null && entry.scriptId == flowScriptId) {
            bonus += FLOW_SCRIPT_BONUS
            if (flowSeq >= 0 && abs(entry.seq - flowSeq) <= FLOW_SEQ_WINDOW) {
                bonus += FLOW_SEQ_BONUS
            }
        }
        return bonus
    }

    private class Scored(val entry: Entry, val score: Double)
}
