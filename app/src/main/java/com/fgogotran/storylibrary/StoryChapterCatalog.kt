package com.fgogotran.storylibrary

import com.fgogotran.data.SettingsRepository
import kotlinx.serialization.Serializable

/**
 * Story Library menu catalog, served from `story/latest/catalog.json` on the CDN.
 *
 * `story_builder/package-story-release.mjs` generates this document from
 * `story_builder/catalog.tsv`. Unknown fields are ignored so the app keeps working
 * when the catalog grows.
 */
@Serializable
data class StoryCatalogDocument(
    val manifestVersion: Int = 0,
    val schemaVersion: Int = 0,
    val minimumAppVersion: String = "",
    val baseUrl: String = "",
    val generatedAt: String = "",
    val chapters: List<StoryCatalogChapter> = emptyList()
)

@Serializable
data class StoryCatalogChapter(
    val chapterId: String = "",
    val titles: StoryChapterTitles = StoryChapterTitles(),
    val category: String = "",
    val latest: Boolean = false,
    val status: String = "",
    val order: String = "",
    val available: Boolean = false,
    val contentVersion: String? = null,
    val dbUrl: String? = null,
    val dbSha256: String? = null,
    val dbSize: Long = 0L,
    val entryCount: Int = 0,
    val coverage: StoryChapterCoverage? = null
)

@Serializable
data class StoryChapterTitles(
    val jp: String = "",
    val cn: String = "",
    val tw: String = "",
    val en: String = ""
)

@Serializable
data class StoryChapterCoverage(
    val cn: Double = 0.0,
    val tw: Double = 0.0,
    val en: Double = 0.0
)

enum class StoryChapterCategory(val catalogValue: String) {
    MAIN_STORY("Main Story"),
    EVENTS("Events"),
    INTERLUDES("Interludes");

    companion object {
        fun fromCatalog(value: String): StoryChapterCategory? =
            entries.firstOrNull { it.catalogValue.equals(value.trim(), ignoreCase = true) }
    }
}

object StoryCatalogRules {
    const val CATALOG_URL = "https://cdn.fgogotran.com/story/latest/catalog.json"

    /** Announcement board document; published next to [CATALOG_URL]. */
    const val ANNOUNCEMENTS_URL = "https://cdn.fgogotran.com/story/latest/announcements.json"
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val FINISHED = "finished"
    const val PROGRESSING = "progressing"

    fun title(chapter: StoryCatalogChapter, targetLocale: String): String {
        val titles = chapter.titles
        return when (SettingsRepository.normalizeTargetLanguage(targetLocale)) {
            SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED -> titles.cn.ifBlank { titles.jp }
            SettingsRepository.TARGET_LANGUAGE_TRADITIONAL -> titles.tw.ifBlank { titles.jp }
            SettingsRepository.TARGET_LANGUAGE_ENGLISH -> titles.en.ifBlank { titles.jp }
            else -> titles.jp
        }.ifBlank { chapter.chapterId }
    }

    fun category(chapter: StoryCatalogChapter): StoryChapterCategory? =
        StoryChapterCategory.fromCatalog(chapter.category)

    fun isFinished(chapter: StoryCatalogChapter): Boolean =
        chapter.status.equals(FINISHED, ignoreCase = true)

    fun isNewest(chapter: StoryCatalogChapter): Boolean = chapter.latest

    /** Coverage for the active target locale, 0..1. */
    fun coverage(chapter: StoryCatalogChapter, targetLocale: String): Double {
        val coverage = chapter.coverage ?: return 0.0
        return when (SettingsRepository.normalizeTargetLanguage(targetLocale)) {
            SettingsRepository.TARGET_LANGUAGE_SIMPLIFIED -> coverage.cn
            SettingsRepository.TARGET_LANGUAGE_TRADITIONAL -> coverage.tw
            else -> coverage.en
        }
    }

    fun isDownloadable(chapter: StoryCatalogChapter): Boolean =
        chapter.available && !chapter.dbUrl.isNullOrBlank() && !chapter.dbSha256.isNullOrBlank() &&
            chapter.dbSize > 0L
}
