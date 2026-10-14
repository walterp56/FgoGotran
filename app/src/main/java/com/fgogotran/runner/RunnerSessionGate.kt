package com.fgogotran.runner

/** Process-local authority for in-game work. Preferences and accessibility are not authority. */
class RunnerSessionGate {
    private var serial = 0L
    private var activeToken: Long? = null

    @Synchronized fun beginSession(): Long = activeToken ?: (++serial).also { activeToken = it }
    @Synchronized fun captureActiveToken(): Long? = activeToken
    @Synchronized fun isCurrent(token: Long): Boolean = activeToken == token
    @Synchronized fun endSession(expectedToken: Long): Boolean {
        if (activeToken != expectedToken) return false
        activeToken = null
        return true
    }
}
