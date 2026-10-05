package com.fgogotran.data

/** The saved UI preference is separate from the phone's current light/dark state. */
enum class AppThemeMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromPreference(value: String?): AppThemeMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}
