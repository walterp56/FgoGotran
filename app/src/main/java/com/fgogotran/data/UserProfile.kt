package com.fgogotran.data

import com.fgogotran.util.FgoLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the player's FGO profile for translation personalization.
 *
 * The player's Master name is injected into the LLM system prompt so that
 * dialogue addressing the player (e.g., "藤丸立香さん") is translated
 * with the correct name rather than a generic placeholder.
 */
@Singleton
class UserProfile @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val tag = "UserProfile"

    /** Returns the player's Master name, or empty string if not configured. */
    suspend fun getPlayerName(): String {
        val name = settingsRepository.playerName.first().ifBlank { null } ?: ""
        FgoLogger.debug(tag, "Player name: ${name.ifBlank { "(empty)" }}")
        return name
    }
}
