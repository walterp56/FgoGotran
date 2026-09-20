package com.fgogotran.accessibility

/** Android's enabled switch and the service's live binding are separate signals. */
enum class AccessibilityConnectionState {
    DISABLED,
    ENABLED_NOT_CONNECTED,
    CONNECTED,
    UNKNOWN
}

internal fun resolveAccessibilityConnectionState(
    connected: Boolean,
    listedByManager: Boolean?,
    listedInSettings: Boolean?
): AccessibilityConnectionState = when {
    connected -> AccessibilityConnectionState.CONNECTED
    listedByManager == true || listedInSettings == true ->
        AccessibilityConnectionState.ENABLED_NOT_CONNECTED
    listedByManager == false && listedInSettings == false ->
        AccessibilityConnectionState.DISABLED
    else -> AccessibilityConnectionState.UNKNOWN
}
