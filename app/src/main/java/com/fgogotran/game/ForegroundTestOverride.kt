package com.fgogotran.game

/**
 * Session-only developer override. Actual FGO package state remains separate so
 * disabling the override always restores normal foreground behavior immediately.
 */
class ForegroundTestOverride {
    var externalPackageName: String? = null
        private set

    fun observeExternalPackage(packageName: String?): Boolean {
        val target = packageName?.takeIf(String::isNotBlank)
        if (externalPackageName == target) return false
        externalPackageName = target
        return true
    }

    fun isEffective(enabled: Boolean, isFgoForeground: Boolean): Boolean =
        isFgoForeground || (enabled && externalPackageName != null)
}
