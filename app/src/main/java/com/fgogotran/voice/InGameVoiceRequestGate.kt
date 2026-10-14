package com.fgogotran.voice

/** Request order is reserved before profile/API work. Audio cache lifetime is independent. */
class InGameVoiceRequestGate(private val sessionCurrent: (Long) -> Boolean) {
    data class Request(val id: Long, val runnerToken: Long, val lineKey: String)
    private var serial = 0L
    private var latest: Request? = null
    private var cacheMaterial: String? = null

    @Synchronized fun reserve(token: Long, lineKey: String): Request? {
        if (!sessionCurrent(token)) return null
        if (latest?.runnerToken == token && latest?.lineKey == lineKey) return null
        if (latest?.runnerToken != token) cacheMaterial = null
        return Request(++serial, token, lineKey).also { latest = it }
    }

    @Synchronized fun isCurrent(request: Request): Boolean =
        latest == request && sessionCurrent(request.runnerToken)

    @Synchronized fun attachCacheMaterial(request: Request, material: String): Boolean {
        if (!isCurrent(request)) return false
        if (cacheMaterial == material) return false
        cacheMaterial = material
        return true
    }

    @Synchronized fun clearFailed(request: Request) {
        if (latest == request) { latest = null; cacheMaterial = null }
    }

    @Synchronized fun invalidate(token: Long) {
        if (latest?.runnerToken == token) { ++serial; latest = null; cacheMaterial = null }
    }
}
