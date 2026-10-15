package com.fgogotran.translation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class GeneralSceneTextFields(
    val name: String?,
    val dialogue: String?,
    val choices: List<String>
)

/** Validates translation text independently of optional voice delivery metadata. */
internal object GeneralTranslationResponseContract {
    fun parseStrings(element: JsonElement, expectedCount: Int): List<String> {
        require(expectedCount >= 0) { "Expected translation item count must not be negative" }
        val array = element as? JsonArray
            ?: throw IllegalArgumentException("Translation response must be a JSON array")
        require(array.size == expectedCount) {
            "Translation response item count ${array.size} != expected $expectedCount"
        }
        return array.mapIndexed { index, value ->
            nonEmptyString(value, "Translation response item $index")
        }
    }

    fun parseScene(
        obj: JsonObject,
        expectName: Boolean,
        expectDialogue: Boolean,
        expectedChoiceCount: Int
    ): GeneralSceneTextFields {
        require(expectedChoiceCount >= 0) { "Expected scene choice count must not be negative" }
        val name = requestedField(obj, "name", expectName)
        val dialogue = requestedField(obj, "dialogue", expectDialogue)
        val choices = obj["choices"]?.let { parseStrings(it, expectedChoiceCount) }
            ?: if (expectedChoiceCount == 0) emptyList() else {
                throw IllegalArgumentException("Scene response missing choices")
            }
        return GeneralSceneTextFields(name, dialogue, choices)
    }

    private fun requestedField(obj: JsonObject, key: String, expected: Boolean): String? {
        val value = obj[key]
        if (!expected) {
            require(value == null || value is JsonNull) {
                "Scene response unrequested $key must be missing or null"
            }
            return null
        }
        return nonEmptyString(value, "Scene response $key")
    }

    private fun nonEmptyString(element: JsonElement?, field: String): String {
        val primitive = element as? JsonPrimitive
        require(primitive != null && primitive.isString) { "$field must be a JSON string" }
        val text = primitive.content.trim()
        require(text.isNotEmpty()) { "$field must not be blank" }
        return text
    }
}
