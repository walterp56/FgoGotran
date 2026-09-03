package com.fgogotran.speech

data class AzureRealtimeTranslationConfig(
    val key: String,
    val region: String,
    val chinaEndpoint: String,
    val targetLanguage: String
)

sealed interface RealtimeVoiceTranslationState {
    data object Disabled : RealtimeVoiceTranslationState
    data object Starting : RealtimeVoiceTranslationState
    data object Listening : RealtimeVoiceTranslationState
    data class Translating(val partialText: String) : RealtimeVoiceTranslationState
    data class Reconnecting(val delayMs: Long) : RealtimeVoiceTranslationState
    data class Error(val message: String) : RealtimeVoiceTranslationState
}

sealed interface AzureTranslationEvent {
    data object SessionStarted : AzureTranslationEvent
    data object SessionStopped : AzureTranslationEvent
    data class Partial(val text: String) : AzureTranslationEvent
    data class Final(val text: String) : AzureTranslationEvent
    data class Canceled(
        val errorCode: String,
        val details: String
    ) : AzureTranslationEvent
}
