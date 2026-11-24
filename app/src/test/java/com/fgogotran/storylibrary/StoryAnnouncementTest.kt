package com.fgogotran.storylibrary

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for `story/latest/announcements.json`, the document behind the Story
 * Library announcement board. The migrated native entries must keep parsing.
 */
class StoryAnnouncementTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `localized object and plain string both decode`() {
        val document = json.decodeFromString(
            StoryAnnouncementsDocument.serializer(),
            """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-11-25T00:00:00Z",
              "announcements": [
                {
                  "id": "2026-11-01-2140-packs",
                  "title": {"en": "Packs live", "zh-Hans": "语言包已上线", "zh-Hant": "語言包已上線"},
                  "body": "Same text for every language",
                  "date": "2026-11-01",
                  "level": "success"
                }
              ]
            }
            """.trimIndent()
        )
        val item = document.announcements.single()
        assertEquals(1, document.schemaVersion)
        assertEquals("Packs live", item.title.forLanguage("en"))
        assertEquals("语言包已上线", item.title.forLanguage("zh-Hans"))
        assertEquals("語言包已上線", item.title.forLanguage("zh-Hant"))
        assertEquals("Same text for every language", item.body.forLanguage("en"))
        assertEquals("Same text for every language", item.body.forLanguage("zh-Hant"))
        assertEquals("2026-11-01", item.displayDate)
        assertEquals("success", item.level)
    }

    @Test
    fun `missing parts fall back and unknown fields are ignored`() {
        val document = json.decodeFromString(
            StoryAnnouncementsDocument.serializer(),
            """
            {"announcements":[{"id":"b","title":"Only English","futureField":1}]}
            """.trimIndent()
        )
        val item = document.announcements.single()
        // A language without its own text falls back to English.
        assertEquals("Only English", item.title.forLanguage("zh-Hant"))
        assertEquals("Only English", item.title.forLanguage("zh-Hans"))
        assertEquals("", item.body.forLanguage("en"))
        assertEquals("", item.date)
        assertEquals("info", item.level)
        assertEquals(0, document.schemaVersion)
    }

    @Test
    fun `empty document is valid`() {
        val document = json.decodeFromString(StoryAnnouncementsDocument.serializer(), "{}")
        assertTrue(document.announcements.isEmpty())
        assertTrue(document.generatedAt.isEmpty())
    }

    /**
     * The board is a hand-editable CDN file. `story_builder/announcements.json` and
     * the old native catalogue are bare arrays, and publishing one of those by hand
     * must not blank the board.
     */
    @Test
    fun `a bare announcement array decodes like the wrapped document`() {
        val document = StoryAnnouncementsDocumentCodec.decode(
            """
            [
              {"id":"a","title":"Bare array","body":"Body","date":"2026-11-25","level":"success"}
            ]
            """.trimIndent()
        )
        val item = document.announcements.single()
        assertEquals("Bare array", item.title.forLanguage("en"))
        assertEquals("success", item.level)
        assertEquals(0, document.schemaVersion)
    }

    @Test
    fun `the wrapped document still decodes through the codec`() {
        val document = StoryAnnouncementsDocumentCodec.decode(
            """
            {"schemaVersion":1,"announcements":[{"id":"b","title":{"en":"Wrapped"},"body":"x","date":"2026-11-25"}]}
            """.trimIndent()
        )
        assertEquals(1, document.schemaVersion)
        assertEquals("Wrapped", document.announcements.single().title.forLanguage("en"))
        assertEquals("info", document.announcements.single().level)
    }
}
