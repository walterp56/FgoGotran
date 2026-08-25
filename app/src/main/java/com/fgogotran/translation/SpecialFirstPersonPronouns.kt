package com.fgogotran.translation

import com.fgogotran.data.SettingsRepository
import java.text.Normalizer

data class SpecialFirstPersonPromptMapping(
    val sourceForm: String,
    val targetTranslation: String
)

internal data class SpecialFirstPersonRepair(
    val wrongTranslations: List<String>,
    val replacement: String
)

/**
 * Shared detection and translation guidance for marked first-person pronouns.
 *
 * Detection stays local. Only rules matched in the current request are added to the API prompt.
 */
internal object SpecialFirstPersonPronouns {
    private enum class DetectionPolicy {
        SUBSTRING,
        STANDALONE_FIRST_PERSON
    }

    private data class Rule(
        val sourceForms: List<String>,
        val simplifiedTarget: String,
        val traditionalTarget: String = simplifiedTarget,
        val detectionPolicy: DetectionPolicy = DetectionPolicy.SUBSTRING,
        val wrongNameTranslations: List<String> = emptyList()
    )

    private data class Detection(
        val sourceForm: String,
        val rule: Rule
    )

    private val stylizedPronounWrongNameTranslations = listOf(
        "阿西",
        "阿希",
        "阿蒂斯",
        "阿特西",
        "阿特希",
        "阿塔西",
        "阿塔希",
        "阿忒西"
    )

    private val rules = listOf(
        Rule(
            sourceForms = listOf("アテシ"),
            simplifiedTarget = "本姑娘",
            wrongNameTranslations = stylizedPronounWrongNameTranslations
        ),
        Rule(
            sourceForms = listOf("アタシ", "あたし"),
            simplifiedTarget = "人家",
            wrongNameTranslations = stylizedPronounWrongNameTranslations
        ),
        Rule(
            sourceForms = listOf("あーし"),
            simplifiedTarget = "本小姐",
            wrongNameTranslations = stylizedPronounWrongNameTranslations
        ),
        Rule(
            sourceForms = listOf("朕"),
            simplifiedTarget = "朕",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("余"),
            simplifiedTarget = "余",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("吾輩", "我輩", "わがはい"),
            simplifiedTarget = "吾辈",
            traditionalTarget = "吾輩"
        ),
        Rule(
            sourceForms = listOf("わらわ", "妾"),
            simplifiedTarget = "妾身",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("拙者", "それがし"),
            simplifiedTarget = "在下"
        ),
        Rule(
            sourceForms = listOf("拙僧"),
            simplifiedTarget = "贫僧",
            traditionalTarget = "貧僧"
        ),
        Rule(
            sourceForms = listOf("拙尼"),
            simplifiedTarget = "贫尼",
            traditionalTarget = "貧尼",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("小生"),
            simplifiedTarget = "小生",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("俺様", "オレ様"),
            simplifiedTarget = "本大爷",
            traditionalTarget = "本大爺"
        ),
        Rule(
            sourceForms = listOf("やつがれ"),
            simplifiedTarget = "鄙人",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("不肖"),
            simplifiedTarget = "不才",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("あちき", "わちき", "わっち"),
            simplifiedTarget = "奴家",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("あっし"),
            simplifiedTarget = "小的",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("おいら", "オイラ"),
            simplifiedTarget = "咱",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("おら", "オラ"),
            simplifiedTarget = "咱",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
        ),
        Rule(
            sourceForms = listOf("あたい", "アタイ"),
            simplifiedTarget = "老娘",
            detectionPolicy = DetectionPolicy.STANDALONE_FIRST_PERSON
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
    ): List<SpecialFirstPersonPromptMapping> {
        return detect(text)
            .distinctBy { it.sourceForm }
            .map { detection ->
                SpecialFirstPersonPromptMapping(
                    sourceForm = detection.sourceForm,
                    targetTranslation = detection.rule.targetFor(targetChineseLocale)
                )
            }
    }

    fun repairs(
        text: String,
        targetChineseLocale: String = SettingsRepository.TARGET_LOCALE_SIMPLIFIED
    ): List<SpecialFirstPersonRepair> {
        val matchedRules = detect(text)
            .map { it.rule }
            .distinct()
            .filter { it.wrongNameTranslations.isNotEmpty() }
        val replacementTargets = matchedRules
            .map { it.targetFor(targetChineseLocale) }
            .distinct()
        if (replacementTargets.size > 1) return emptyList()

        return matchedRules.map { rule ->
            SpecialFirstPersonRepair(
                wrongTranslations = rule.wrongNameTranslations,
                replacement = rule.targetFor(targetChineseLocale)
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
            DetectionPolicy.STANDALONE_FIRST_PERSON ->
                isStandaloneFirstPersonOccurrence(text, range)
        }
    }

    private fun isStandaloneFirstPersonOccurrence(text: String, range: IntRange): Boolean {
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
