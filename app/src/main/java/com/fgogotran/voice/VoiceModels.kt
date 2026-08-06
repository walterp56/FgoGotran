package com.fgogotran.voice

data class VoiceProfile(
    val profileId: String,
    val provider: String,
    val locale: String,
    val voiceName: String,
    val style: String,
    val pitch: String,
    val rate: String,
    val volume: String,
    val description: String
)

data class AzureSpeechConfig(
    val key: String,
    val region: String
)

data class VoiceSynthesisRequest(
    val speakerName: String?,
    val spokenText: String,
    val profile: VoiceProfile,
    val styleOverride: String? = null,
    val rateOverride: String? = null
) {
    fun cacheMaterial(): String = buildString {
        append(profile.profileId)
        append('|')
        append(profile.voiceName)
        append('|')
        append(profile.style)
        append('|')
        append(styleOverride.orEmpty())
        append('|')
        append(profile.pitch)
        append('|')
        append(profile.rate)
        append('|')
        append(rateOverride.orEmpty())
        append('|')
        append(speakerName.orEmpty())
        append('|')
        append(spokenText)
    }
}

data class VoiceExpression(
    val styleOverride: String? = null,
    val rateOverride: String? = null
)
