package com.fgogotran.chapter.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Metadata for one hosted language pack (no payload downloaded yet). */
data class NativePackInfo(
    val language: String,
    val scripts: List<String>,
    val bundleUrl: String,
    val bundleSha256: String,
    val bundleSize: Long,
    val manifestUrl: String,
)

/** A downloaded, hash-verified pack ready for the install transaction. */
data class NativeStoryPackage(
    val language: String,
    val scripts: List<String>,
    val bundle: ByteArray,
    val bundleSha256: String,
    val manifest: ByteArray,
    val assetFilename: String,
    val logicalBundle: String,
    val originalSha256: String,
) {
    val scriptsCount: Int get() = scripts.size
}

/**
 * Minimal HTTP client for the hosted release catalogue. It only downloads and
 * verifies bytes; installation stays in the existing transaction/backup path.
 */
class NativeReleaseClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    suspend fun lookupAll(language: String, gameVersion: String): List<NativePackInfo> = withContext(Dispatchers.IO) {
        require(isConfigured) { "Release catalogue is not configured" }
        val body = fetch("$baseUrl/v1/native/catalog?lang=$language&game=$gameVersion", authorized = true).decodeToString()
        json.decodeFromString<CatalogResponse>(body).packs
            .filter { it.language == language }
            .mapNotNull { pack ->
                val bundle = pack.files.bundle ?: return@mapNotNull null
                val manifest = pack.files.manifest ?: return@mapNotNull null
                NativePackInfo(
                    language = pack.language,
                    scripts = pack.scripts,
                    bundleUrl = bundle.url,
                    bundleSha256 = bundle.sha256,
                    bundleSize = bundle.size,
                    manifestUrl = manifest.url,
                )
            }
    }

    suspend fun download(info: NativePackInfo, onProgress: (String) -> Unit = {}): NativeStoryPackage =
        withContext(Dispatchers.IO) {
            onProgress("bundle")
            val bundle = fetch(info.bundleUrl, authorized = false)
            require(bundle.size.toLong() == info.bundleSize) { "Bundle size mismatch" }
            require(sha256(bundle) == info.bundleSha256) { "Bundle hash mismatch" }

            onProgress("manifest")
            val manifest = fetch(info.manifestUrl, authorized = false)

            NativeStoryPackage(
                language = info.language,
                scripts = info.scripts,
                bundle = bundle,
                bundleSha256 = info.bundleSha256,
                manifest = manifest,
                assetFilename = Json.parseToJsonElement(manifest.decodeToString()).jsonObject["asset_filename"]?.jsonPrimitive?.content.orEmpty(),
                logicalBundle = Json.parseToJsonElement(manifest.decodeToString()).jsonObject["logical_bundle"]?.jsonPrimitive?.content.orEmpty(),
                originalSha256 = Json.parseToJsonElement(manifest.decodeToString()).jsonObject["original"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content.orEmpty(),
            )
        }

    private fun fetch(url: String, authorized: Boolean): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("accept", "application/json")
            if (authorized) setRequestProperty("x-api-key", apiKey)
        }
        try {
            if (connection.responseCode !in 200..299) {
                val body = connection.errorStream?.use { it.readBytes().decodeToString() }?.take(200).orEmpty()
                error("HTTP ${connection.responseCode}: $body")
            }
            val limit = 16 * 1024 * 1024
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    require(out.size() <= limit) { "Response too large" }
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

@Serializable
private data class CatalogResponse(val packs: List<PackDto> = emptyList())

@Serializable
private data class PackDto(
    val language: String = "",
    @SerialName("gameVersion") val gameVersion: String = "",
    val scripts: List<String> = emptyList(),
    val files: FilesDto = FilesDto(),
)

@Serializable
private data class FilesDto(
    val bundle: FileDto? = null,
    val index: FileDto? = null,
    val manifest: FileDto? = null,
)

@Serializable
private data class FileDto(
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val crc32: Long? = null,
)
