package com.fgogotran.terminology

import com.fgogotran.util.FgoLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort online glossary refresh.
 *
 * Runs once per process when the foreground service starts. Failures are logged
 * and never block translation, so the bundled DB remains the fallback.
 */
@Singleton
class GlossaryUpdateManager @Inject constructor(
    private val termDao: TermDao
) {
    private val httpClient = HttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "GlossaryUpdate"

    companion object {
        private const val ATLAS_BASE = "https://api.atlasacademy.io"
        private val hasAttemptedUpdate = AtomicBoolean(false)
    }

    suspend fun updateIfNeeded() {
        if (!hasAttemptedUpdate.compareAndSet(false, true)) return

        try {
            val terms = fetchAtlasTerms()
            if (terms.isEmpty()) {
                FgoLogger.warn(tag, "Online glossary returned no terms; keeping bundled DB")
                return
            }
            termDao.upsertTerms(terms)
            FgoLogger.info(tag, "Online glossary updated: ${terms.size} terms")
        } catch (e: Exception) {
            FgoLogger.warn(tag, "Online glossary update failed; using bundled DB", e)
        }
    }

    private suspend fun fetchAtlasTerms(): List<TermEntity> {
        val cnServants = fetchArray("/export/CN/servant/all.json")
            .associateBy({ it.idKey() }, { it.string("name") })
        val jpServants = fetchArray("/export/JP/servant/all.json")

        val terms = mutableListOf<TermEntity>()
        for (servant in jpServants) {
            val id = servant.idKey()
            val jpName = servant.string("name")
            val cnName = cnServants[id].orEmpty()
            if (jpName.isNotBlank() && cnName.isNotBlank() && jpName != cnName) {
                val aliases = listOf(servant.string("ruby"))
                    .filter { it.isNotBlank() && it != jpName }
                    .joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                terms.add(
                    TermEntity(
                        jpTerm = jpName,
                        cnTerm = cnName,
                        category = "servant",
                        aliases = aliases.ifBlank { "[]" }
                    )
                )
            }
        }

        terms += pairedTerms(
            jpEndpoint = "/export/JP/nice_item.json",
            cnEndpoint = "/export/CN/nice_item.json",
            category = "item"
        )
        terms += pairedTerms(
            jpEndpoint = "/export/JP/nice_CE.json",
            cnEndpoint = "/export/CN/nice_CE.json",
            category = "craft_essence"
        )

        return terms
            .filter { it.jpTerm.isNotBlank() && it.cnTerm.isNotBlank() && it.jpTerm != it.cnTerm }
            .distinctBy { it.jpTerm }
    }

    private suspend fun pairedTerms(
        jpEndpoint: String,
        cnEndpoint: String,
        category: String
    ): List<TermEntity> {
        val cnById = fetchArray(cnEndpoint).associateBy({ it.idKey() }, { it.string("name") })
        return fetchArray(jpEndpoint).mapNotNull { jp ->
            val jpName = jp.string("originalName").ifBlank { jp.string("name") }
            val cnName = cnById[jp.idKey()].orEmpty()
            if (jpName.isBlank() || cnName.isBlank() || jpName == cnName) {
                null
            } else {
                TermEntity(
                    jpTerm = jpName,
                    cnTerm = cnName,
                    category = category,
                    aliases = "[]"
                )
            }
        }
    }

    private suspend fun fetchArray(endpoint: String): List<JsonObject> {
        val body = httpClient.get("$ATLAS_BASE$endpoint").body<String>()
        val element = json.parseToJsonElement(body)
        return element.jsonArray.mapNotNull { it as? JsonObject }
    }

    private fun JsonObject.string(name: String): String {
        return ((this[name] as? JsonPrimitive)?.content).orEmpty()
    }

    private fun JsonObject.idKey(): String {
        return string("collectionNo").ifBlank { string("id") }
    }
}
