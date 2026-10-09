package com.fgogotran.translation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale
import kotlin.math.roundToInt

/** A request-local snapshot of the voices and exact styles playback can use. */
data class VoiceHintContext(
    val voiceNames: List<String>,
    val allowedStyles: List<String>
)

internal fun buildVoiceHintInstructions(context: VoiceHintContext): String = buildString {
    appendLine("Voice(s): ${context.voiceNames.joinToString(",")}")
    appendLine("Allowed styles: ${context.allowedStyles.joinToString(",").ifBlank { "none" }}")
    appendLine("voice_hint controls delivery only; never change translation. Use null when neutral/unclear.")
    appendLine("voice_hint must be a JSON object or null, never a string.")
    appendLine("Only inside voice_hint: styles, dragon_styles, intensity, rate, pitch, pause, confidence; never put these at the top level.")
    appendLine("No descriptions, explanations, Markdown, or extra keys.")
    appendLine("""voice_hint field example only (retain required outer keys; do not copy example values): {"voice_hint":{"styles":[],"rate":-1,"confidence":0.8}}""")
    appendLine("styles: zero or one exact allowed style grounded in this line; no substitutes. dragon_styles: [].")
    append("If no style fits, use styles=[] with only clearly justified delivery changes. ")
    append("intensity/confidence 0-1; rate/pitch/pause integers -2..2; omit unchanged values.")
}

/** Bad hint fields must never cause an otherwise valid scene translation to fail. */
internal fun parseVoiceLineHint(
    element: JsonElement?,
    context: VoiceHintContext?
): VoiceLineHint? {
    if (context == null) return null
    val obj = element as? JsonObject ?: return null
    val allowed = context.allowedStyles.toSet()
    val style = sequenceOf("styles", "style", "dragon_styles", "dragonStyles")
        .flatMap { key -> styleValues(obj[key]).asSequence() }
        .map { it.trim().lowercase(Locale.US).replace('_', '-') }
        .firstOrNull { it in allowed }
    val rate = obj.deliveryDelta("rate")
    val pitch = obj.deliveryDelta("pitch")
    val pause = obj.deliveryDelta("pause")
    if (style == null && rate == null && pitch == null && pause == null) return null
    return VoiceLineHint(
        styles = listOfNotNull(style),
        intensity = obj.finiteNumber("intensity")?.coerceIn(0.0, 1.0),
        rate = rate,
        pitch = pitch,
        pause = pause,
        confidence = obj.finiteNumber("confidence")?.coerceIn(0.0, 1.0),
        targetVoiceNames = context.voiceNames.toSet()
    )
}

private fun styleValues(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> element.contentOrNull?.split(',', '，', '|', '/').orEmpty()
    else -> emptyList()
}

private fun JsonObject.finiteNumber(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }

private fun JsonObject.deliveryDelta(key: String): Int? = finiteNumber(key)
    ?.coerceIn(-2.0, 2.0)?.roundToInt()?.takeIf { it != 0 }
