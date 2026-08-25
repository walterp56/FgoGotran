package com.fgogotran.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Validates user-configured OpenAI-compatible endpoints before they reach the HTTP client.
 *
 * Android's cleartext opt-in is application-wide, so this policy is the security boundary that
 * limits unencrypted requests to numeric addresses on a private local network. Hostnames are not
 * accepted for cleartext endpoints because their DNS result can change after validation.
 */
object ApiEndpointPolicy {
    data class ValidatedEndpoint(
        val url: String,
        val isPrivateLanHttp: Boolean
    )

    fun validateCustomOpenAiEndpoint(rawUrl: String): ValidatedEndpoint {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "API 地址不能为空" }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("API 地址格式无效")
        }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            "API 地址必须使用 https:// 或 http://"
        }
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) {
            "API 地址必须包含有效的服务器地址"
        }
        require(uri.rawUserInfo == null) {
            "API 地址不能包含用户名或密码"
        }
        require(uri.rawFragment == null) {
            "API 地址不能包含 # 片段"
        }
        require(uri.port == -1 || uri.port in 1..65535) {
            "API 地址端口无效"
        }

        val path = uri.path.orEmpty().trimEnd('/')
        require(path.endsWith("/chat/completions", ignoreCase = true)) {
            "API 地址必须指向 Chat Completions 接口，例如 /v1/chat/completions"
        }

        if (scheme == "https") {
            return ValidatedEndpoint(url = trimmed, isPrivateLanHttp = false)
        }

        require(isPrivateLanLiteral(uri.host)) {
            "HTTP 仅允许数字形式的私有局域网地址，例如 192.168.3.18；公网或主机名请使用 HTTPS"
        }
        return ValidatedEndpoint(url = trimmed, isPrivateLanHttp = true)
    }

    fun isPrivateLanHttp(rawUrl: String): Boolean = runCatching {
        validateCustomOpenAiEndpoint(rawUrl).isPrivateLanHttp
    }.getOrDefault(false)

    private fun isPrivateLanLiteral(rawHost: String): Boolean {
        val host = rawHost.removePrefix("[").removeSuffix("]")
        return if (':' in host) {
            isPrivateIpv6Literal(host)
        } else {
            parseIpv4Literal(host)?.let(::isPrivateIpv4) == true
        }
    }

    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            if (part.length > 1 && part.startsWith('0')) return null
            val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun isPrivateIpv4(address: ByteArray): Boolean {
        val first = address[0].toInt() and 0xff
        val second = address[1].toInt() and 0xff
        return first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun isPrivateIpv6Literal(host: String): Boolean {
        if ('%' in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return when (address) {
            is Inet4Address -> isPrivateIpv4(address.address)
            is Inet6Address -> {
                if (address.isAnyLocalAddress) return false
                val first = address.address[0].toInt() and 0xff
                address.isLoopbackAddress || address.isLinkLocalAddress || (first and 0xfe) == 0xfc
            }
            else -> false
        }
    }
}
