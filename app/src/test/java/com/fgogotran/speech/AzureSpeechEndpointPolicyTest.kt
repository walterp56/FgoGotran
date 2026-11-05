package com.fgogotran.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AzureSpeechEndpointPolicyTest {
    @Test
    fun acceptsChinaResourceEndpointAndNormalizesHost() {
        assertEquals(
            "https://my-fgo-speech.cognitiveservices.azure.cn",
            AzureSpeechEndpointPolicy.normalizeChinaResourceEndpoint(
                " HTTPS://MY-FGO-SPEECH.COGNITIVESERVICES.AZURE.CN/ "
            )
        )
    }

    @Test
    fun rejectsNonAzureHostToProtectSpeechKey() {
        assertFailsWith<IllegalArgumentException> {
            AzureSpeechEndpointPolicy.normalizeChinaResourceEndpoint("https://example.com")
        }
    }

    @Test
    fun rejectsHttpEndpoint() {
        assertFailsWith<IllegalArgumentException> {
            AzureSpeechEndpointPolicy.normalizeChinaResourceEndpoint(
                "http://my-fgo-speech.cognitiveservices.azure.cn"
            )
        }
    }

    @Test
    fun rejectsEndpointWithPathOrQuery() {
        assertFailsWith<IllegalArgumentException> {
            AzureSpeechEndpointPolicy.normalizeChinaResourceEndpoint(
                "https://my-fgo-speech.cognitiveservices.azure.cn/speech?key=bad"
            )
        }
    }
}
