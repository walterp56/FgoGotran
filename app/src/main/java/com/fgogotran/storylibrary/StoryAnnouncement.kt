package com.fgogotran.storylibrary

import com.fgogotran.data.SettingsRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject

/**
 * Story Library announcement board, served from `story/latest/announcements.json`
 * on the CDN.
 *
 * The board used to live on the native-assets page and shipped inside the native
 * catalogue. It moved here so a translation update can be announced without
 * touching the native release pipeline. `story_builder/package-story-release.mjs`
 * generates the document from `story_builder/announcements.json`.
 *
 * Unknown fields are ignored and every field has a default, so an older app keeps
 * working when the document grows.
 */
@Serializable
data class StoryAnnouncementsDocument(
    val schemaVersion: Int = 0,
    val generatedAt: String = "",
    val announcements: List<StoryAnnouncement> = emptyList()
)

/**
 * Decodes the announcement document.
 *
 * The published shape is the object above, but the board is a hand-editable CDN
 * file: a bare array of announcements is accepted too, which is also the shape of
 * `story_builder/announcements.json` and of the old native catalogue.
 */
object StoryAnnouncementsDocumentCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): StoryAnnouncementsDocument {
        val element = json.parseToJsonElement(text)
        val document = if (element is JsonArray) {
            buildJsonObject { put("announcements", element) }
        } else {
            element
        }
        return json.decodeFromJsonElement(StoryAnnouncementsDocument.serializer(), document)
    }
}

@Serializable
data class StoryAnnouncement(
    val id: String = "",
    @Serializable(with = StoryAnnouncementTextSerializer::class)
    val title: StoryAnnouncementText = StoryAnnouncementText(),
    @Serializable(with = StoryAnnouncementTextSerializer::class)
    val body: StoryAnnouncementText = StoryAnnouncementText(),
    val date: String = "",
    val level: String = "info"
) {
    /** The date shown on the board, or an empty string when the document omits it. */
    val displayDate: String get() = date.trim()
}

/** One announcement text in the three shipping languages. */
@Serializable
data class StoryAnnouncementText(
    val en: String = "",
    @SerialName("zh-Hans") val zhHans: String = "",
    @SerialName("zh-Hant") val zhHant: String = ""
) {
    fun forLanguage(languageTag: String): String = when (languageTag) {
        SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED -> zhHans.ifBlank { en }
        SettingsRepository.TARGET_LANGUAGE_TRADITIONAL -> zhHant.ifBlank { en }
        else -> en.ifBlank { zhHans.ifBlank { zhHant } }
    }

    fun isBlank(): Boolean = en.isBlank() && zhHans.isBlank() && zhHant.isBlank()
}

/**
 * Accepts either the localized object (`{"en":…,"zh-Hans":…,"zh-Hant":…}`) or a
 * plain string, which becomes the text for every language. The native board used
 * the same tolerant shape, so migrated entries keep parsing.
 */
object StoryAnnouncementTextSerializer :
    JsonTransformingSerializer<StoryAnnouncementText>(StoryAnnouncementText.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val plain = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element
        return buildJsonObject {
            put("en", JsonPrimitive(plain))
            put("zh-Hans", JsonPrimitive(plain))
            put("zh-Hant", JsonPrimitive(plain))
        }
    }
}
