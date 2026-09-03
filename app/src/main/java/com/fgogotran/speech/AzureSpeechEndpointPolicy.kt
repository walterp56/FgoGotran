package com.fgogotran.speech

import java.net.URI

/** Validates sovereign-cloud endpoints before a Speech key is sent to them. */
object AzureSpeechEndpointPolicy {
    fun normalizeChinaResourceEndpoint(rawEndpoint: String): String {
        val candidate = rawEndpoint.trim().ifBlank {
            throw IllegalArgumentException("中国 Azure 实时翻译需要填写资源端点")
        }
        val uri = runCatching { URI(candidate) }.getOrElse {
            throw IllegalArgumentException("中国 Azure 资源端点格式无效")
        }
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "中国 Azure 资源端点必须使用 HTTPS"
        }
        require(host.endsWith(CHINA_RESOURCE_HOST_SUFFIX) && host.length > CHINA_RESOURCE_HOST_SUFFIX.length) {
            "中国 Azure 资源端点必须是 *.cognitiveservices.azure.cn"
        }
        require(uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) {
            "中国 Azure 资源端点不能包含账号、端口、查询参数或片段"
        }
        require(uri.path.isNullOrBlank() || uri.path == "/") {
            "请填写 Azure 门户显示的资源根端点"
        }
        return "https://$host"
    }

    private const val CHINA_RESOURCE_HOST_SUFFIX = ".cognitiveservices.azure.cn"
}
