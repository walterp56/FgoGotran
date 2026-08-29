package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import java.text.Normalizer

data class SpecialSecondPersonPromptMapping(
    val sourceForm: String,
    val targetTranslation: String
)

/**
 * Shared detection and translation guidance for marked second-person forms.
 *
 * This intentionally mirrors [SpecialFirstPersonPronouns]: detection is local,
 * and only forms matched in the current request are added to the prompt.
 * Ordinary second-person words such as あなた/君/お前 remain in the generic
 * address-pronoun rule rather than being forced into exact mappings.
 */
internal object SpecialSecondPersonPronouns {
    private enum class DetectionPolicy {
        SUBSTRING,
        STANDALONE_SECOND_PERSON
    }

    private data class Rule(
        val sourceForms: List<String>,
        val simplifiedTarget: String,
        val traditionalTarget: String = simplifiedTarget,
        val detectionPolicy: DetectionPolicy = DetectionPolicy.SUBSTRING
    )

    private data class Detection(
        val sourceForm: String,
        val rule: Rule
    )

    private val rules = listOf(
        Rule(
            sourceForms = listOf("貴様", "きさま", "キサマ"),
            simplifiedTarget = "你这家伙",
            traditionalTarget = "你這傢伙"
        ),
        Rule(
            sourceForms = listOf("てめえ", "てめェ", "テメエ", "てめー", "てめ"),
            simplifiedTarget = "你这混蛋",
            traditionalTarget = "你這混蛋"
        ),
        Rule(
            sourceForms = listOf("汝", "なんじ"),
            simplifiedTarget = "汝"
        ),
        Rule(
            sourceForms = listOf("卿"),
            simplifiedTarget = "卿",
            detectionPolicy = DetectionPolicy.STANDALONE_SECOND_PERSON
        ),
        Rule(
            sourceForms = listOf("うぬ"),
            simplifiedTarget = "汝"
        )
    )

    private val standaloneFollowers = listOf(
        "ながら", "ならば", "である", "だった", "自身", "自ら",
        "には", "にも", "では", "から", "まで", "より", "こそ",
        "しか", "だけ", "さえ", "って", "とて", "なら", "です",
        "じゃ", "は", "が", "の", "を", "に", "へ", "で", "と",
        "も", "だ", "め"
    )
    private val standaloneBoundaryCharacters = setOf(
        '、', '。', '，', ',', '！', '!', '？', '?', '；', ';', '：', ':',
        '…', '‥', '—', '―', '─', '━', '「', '」', '『', '』', '（', '）',
        '(', ')', '【', '】', '[', ']'
    )

    private val ruleBySourceForm = rules
        .flatMap { rule -> rule.sourceForms.map { sourceForm -> sourceForm to rule } }
        .toMap()
    private val sourcePattern = Regex(
        ruleBySourceForm.keys
            .sortedByDescending(String::length)
            .joinToString("|") { Regex.escape(it) }
    )

    fun promptMappings(
        text: String,
        targetChineseLocale: String
    ): List<SpecialSecondPersonPromptMapping> {
        return detect(text)
            .distinctBy { it.sourceForm }
            .map { detection ->
                SpecialSecondPersonPromptMapping(
                    sourceForm = detection.sourceForm,
                    targetTranslation = detection.rule.targetFor(targetChineseLocale)
                )
            }
    }

    private fun detect(text: String): List<Detection> {
        if (text.isBlank()) return emptyList()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return sourcePattern.findAll(normalized)
            .mapNotNull { match ->
                ruleBySourceForm[match.value]?.let { rule ->
                    if (rule.accepts(normalized, match.range)) {
                        Detection(match.value, rule)
                    } else {
                        null
                    }
                }
            }
            .toList()
    }

    private fun Rule.accepts(text: String, range: IntRange): Boolean {
        return when (detectionPolicy) {
            DetectionPolicy.SUBSTRING -> true
            DetectionPolicy.STANDALONE_SECOND_PERSON ->
                isStandaloneSecondPersonOccurrence(text, range)
        }
    }

    private fun isStandaloneSecondPersonOccurrence(text: String, range: IntRange): Boolean {
        val previous = text.getOrNull(range.first - 1)
        if (previous != null && previous.isCompoundPrefixCharacter()) return false

        val followingIndex = range.last + 1
        if (followingIndex >= text.length) return true
        val following = text.substring(followingIndex)
        val next = following.first()
        return next.isWhitespace() ||
            next in standaloneBoundaryCharacters ||
            standaloneFollowers.any(following::startsWith)
    }

    private fun Char.isCompoundPrefixCharacter(): Boolean {
        return this in '\u3400'..'\u9FFF' ||
            this in '\uF900'..'\uFAFF' ||
            this in '\u30A0'..'\u30FF' ||
            this in '\uFF66'..'\uFF9D' ||
            this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '0'..'9'
    }

    private fun Rule.targetFor(targetChineseLocale: String): String {
        return if (
            SettingsRepository.normalizeTargetChineseLocale(targetChineseLocale) ==
            SettingsRepository.TARGET_LOCALE_TRADITIONAL
        ) {
            traditionalTarget
        } else {
            simplifiedTarget
        }
    }
}
