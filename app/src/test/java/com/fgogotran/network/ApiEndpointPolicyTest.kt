package com.fgogotran.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiEndpointPolicyTest {
    @Test
    fun acceptsHttpsEndpoint() {
        val result = ApiEndpointPolicy.validateCustomOpenAiEndpoint(
            "https://api.example.com/v1/chat/completions"
        )

        assertEquals("https://api.example.com/v1/chat/completions", result.url)
        assertFalse(result.isPrivateLanHttp)
    }

    @Test
    fun acceptsPrivateIpv4HttpEndpoints() {
        val endpoints = listOf(
            "http://10.0.0.2:18080/v1/chat/completions",
            "http://172.16.0.2/v1/chat/completions",
            "http://172.31.255.254/v1/chat/completions",
            "http://192.168.3.18:18080/v1/chat/completions",
            "http://127.0.0.1:8080/v1/chat/completions",
            "http://169.254.10.2/v1/chat/completions"
        )

        endpoints.forEach { endpoint ->
            assertTrue(
                ApiEndpointPolicy.validateCustomOpenAiEndpoint(endpoint).isPrivateLanHttp,
                endpoint
            )
        }
    }

    @Test
    fun acceptsPrivateIpv6HttpEndpoints() {
        val endpoints = listOf(
            "http://[fd00::1]:18080/v1/chat/completions",
            "http://[fe80::1]:18080/v1/chat/completions",
            "http://[::1]:18080/v1/chat/completions"
        )

        endpoints.forEach { endpoint ->
            assertTrue(
                ApiEndpointPolicy.validateCustomOpenAiEndpoint(endpoint).isPrivateLanHttp,
                endpoint
            )
        }
    }

    @Test
    fun rejectsPublicOrHostnameHttpEndpoints() {
        val endpoints = listOf(
            "http://8.8.8.8/v1/chat/completions",
            "http://172.15.255.255/v1/chat/completions",
            "http://172.32.0.1/v1/chat/completions",
            "http://example.com/v1/chat/completions",
            "http://local-ai.example/v1/chat/completions",
            "http://[2001:4860:4860::8888]/v1/chat/completions"
        )

        endpoints.forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                ApiEndpointPolicy.validateCustomOpenAiEndpoint(endpoint)
            }
        }
    }

    @Test
    fun rejectsAmbiguousOrUnsafeUrls() {
        val endpoints = listOf(
            "",
            "192.168.3.18:18080/v1/chat/completions",
            "ftp://192.168.3.18/v1/chat/completions",
            "http://user:password@192.168.3.18/v1/chat/completions",
            "http://192.168.003.018/v1/chat/completions",
            "http://192.168.3.18:99999/v1/chat/completions",
            "http://192.168.3.18/v1/chat/completions#debug",
            "http://192.168.3.18:18080/health"
        )

        endpoints.forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                ApiEndpointPolicy.validateCustomOpenAiEndpoint(endpoint)
            }
        }
    }

    @Test
    fun localHttpProbeReturnsFalseForInvalidInput() {
        assertFalse(ApiEndpointPolicy.isPrivateLanHttp("https://api.example.com/v1/chat/completions"))
        assertFalse(ApiEndpointPolicy.isPrivateLanHttp("http://8.8.8.8/v1/chat/completions"))
        assertTrue(ApiEndpointPolicy.isPrivateLanHttp("http://192.168.3.18:18080/v1/chat/completions"))
    }
}
