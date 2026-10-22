package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.packs.StoryPackPolicy
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

internal data class AtlasLink(val war: Int, val quest: Int, val phase: Int, val position: Int) {
    fun key() = "$war:$quest:$phase:$position"
}
internal data class AtlasReference(val region: String, val id: String, val group: String, val links: List<AtlasLink>) {
    val url get() = "https://static.atlasacademy.io/$region/Script/$group/$id.txt"
    val path get() = "$region/scripts/$group/$id.txt"
}

/** Only the two public Atlas hosts and reviewed read-only endpoint shapes. Never follows redirects. */
internal object AtlasEndpoints {
    val regions = listOf("CN", "JP", "NA", "TW")
    fun validate(url: String) {
        require(url == "https://api.atlasacademy.io/info" ||
            url.matches(Regex("https://api\\.atlasacademy\\.io/nice/(JP|CN|TW|NA)/war/[1-9][0-9]{0,8}")) ||
            url.matches(Regex("https://static\\.atlasacademy\\.io/(JP|CN|TW|NA)/Script/[A-Za-z0-9_]{1,64}/[A-Za-z0-9_]{1,64}\\.txt"))) { "Unreviewed Atlas URL" }
    }
    fun reference(region: String, id: String, url: String, links: List<AtlasLink>): AtlasReference {
        require(region in regions); StoryPackPolicy.scriptId(id); validate(url)
        val match = requireNotNull(Regex("https://static\\.atlasacademy\\.io/$region/Script/([A-Za-z0-9_]{1,64})/$id\\.txt").matchEntire(url))
        return AtlasReference(region, id, match.groupValues[1], links.sortedBy { it.key() }.also { require(it.distinct().size == it.size) })
    }
    fun mainQuests(war: JsonObject): List<JsonObject> = war.getValue("spots").jsonArray
        .flatMap { it.jsonObject.getValue("quests").jsonArray }.map { it.jsonObject }.filter { it.string("type") == "main" }.also { quests ->
            require(quests.map { it.int("id") }.distinct().size == quests.size && quests.all { it.int("id") > 0 })
        }
    fun inventory(region: String, war: JsonObject, expectedId: Int, questFilter: Set<Int>): List<AtlasReference> {
        require(war.int("id") == expectedId)
        val quests = mainQuests(war).filter { questFilter.isEmpty() || it.int("id") in questFilter }
        val refs = quests.flatMap { quest ->
            require(quest.int("warId") == expectedId)
            quest.getValue("phaseScripts").jsonArray.flatMap { phase ->
                val value = phase.jsonObject
                val number = value.int("phase").also { require(it > 0 && it in quest.getValue("phases").jsonArray.map { it.jsonPrimitive.int }) }
                value.getValue("scripts").jsonArray.mapIndexed { index, script ->
                    val item = script.jsonObject
                    reference(region, item.string("scriptId"), item.string("script"), listOf(AtlasLink(expectedId, quest.int("id"), number, index)))
                }
            }
        }
        return refs.groupBy { it.id }.values.map { group ->
            require(group.map { it.url }.distinct().size == 1) { "Ambiguous regional script identity" }
            val links = group.flatMap { it.links }.sortedBy { it.key() }
            require(links.distinct().size == links.size) { "Duplicate quest/phase script position" }
            group.first().copy(links = links)
        }.sortedBy { it.id }
    }
}

internal fun interface AtlasFetch { fun get(url: String, limit: Int): ByteArray? }

internal class AtlasHttpFetch : AtlasFetch {
    private var previousRequest = 0L
    override fun get(url: String, limit: Int): ByteArray? {
        AtlasEndpoints.validate(url)
        // Sequential, at most four requests/second; no hidden retry or parallel crawl.
        val wait = 250L - (System.nanoTime() - previousRequest) / 1_000_000
        if (wait > 0) Thread.sleep(wait)
        previousRequest = System.nanoTime()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        val deadline = System.nanoTime() + 45_000_000_000L
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", "FgoGotran-DeveloperPreparation/2")
            val status = connection.responseCode
            if (status == 404) return null // Known missing, never a timeout/429/5xx interpreted as absence.
            require(status == 200) { "Atlas HTTP $status: $url; previous snapshots retained" }
            require(connection.contentLengthLong <= limit)
            return connection.inputStream.use { PreparationFiles.bounded(it, limit, deadline) }
        } finally { connection.disconnect() }
    }
}

internal class AtlasSnapshot private constructor(
    val directory: File, val index: JsonObject, val references: List<AtlasReference>, private val files: Map<String, JsonObject>
) {
    val contentSha256 get() = index.string("content_sha256")
    fun references(region: String) = references.filter { it.region == region }
    fun bytes(reference: AtlasReference): ByteArray? = files[reference.path]?.let {
        PreparationFiles.read(PreparationFiles.confined(directory, File(directory, reference.path)), PreparationFiles.MAX_SCRIPT)
            .also { bytes -> require(bytes.size == it.int("size") && PreparationFiles.hash(bytes) == it.string("sha256")) }
    }
    fun sourceMetadata(reference: AtlasReference): JsonObject = buildJsonObject {
        put("region", reference.region); put("script_id", reference.id); put("url", reference.url)
        val entry = requireNotNull(files[reference.path])
        put("sha256", entry.string("sha256")); put("size", entry.int("size"))
        put("links", JsonArray(reference.links.map { JsonPrimitive(it.key()) }))
    }

    companion object {
        private fun info(bytes: ByteArray): JsonObject {
            val raw = PreparationFiles.json(bytes)
            return buildJsonObject { AtlasEndpoints.regions.forEach { region ->
                val value = raw.getValue(region).jsonObject
                require(value.string("hash").matches(Regex("[0-9a-f]{6,64}")) && value.getValue("timestamp").jsonPrimitive.long > 0)
                put(region, buildJsonObject { put("hash", value.string("hash")); put("timestamp", value.getValue("timestamp")) })
            } }
        }
        private fun content(index: JsonObject) = JsonObject(index.filterKeys { it !in setOf("content_sha256", "started_at_utc", "finished_at_utc") })

        fun download(repository: File, warIds: Set<Int>, questFilter: Set<Int> = emptySet(), fetch: AtlasFetch = AtlasHttpFetch()): AtlasSnapshot {
            require(warIds.size in 1..8 && warIds.all { it in 1..999999999 })
            require(questFilter.size <= 128 && questFilter.all { it > 0 })
            val root = PreparationFiles.confined(repository, File(repository, "story_assets/atlas-r2"))
            Files.createDirectories(root.toPath())
            val id = UUID.randomUUID().toString()
            val pending = PreparationFiles.confined(root, File(root, ".partial-$id"))
            Files.createDirectory(pending.toPath())
            val started = Instant.now().toString()
            val files = mutableListOf<JsonObject>(); val missing = mutableListOf<JsonObject>()
            var total = 0L
            fun save(path: String, url: String, limit: Int): ByteArray? {
                AtlasEndpoints.validate(url)
                require(files.size + missing.size < PreparationFiles.MAX_FILES)
                val bytes = fetch.get(url, limit)
                if (bytes == null) {
                    missing += buildJsonObject { put("path", path); put("url", url); put("http_status", 404) }
                    return null
                }
                require(bytes.size in 1..limit && total + bytes.size <= PreparationFiles.MAX_SNAPSHOT)
                PreparationFiles.writeNew(PreparationFiles.confined(pending, File(pending, path)), bytes)
                total += bytes.size
                files += buildJsonObject { put("path", path); put("url", url); put("size", bytes.size); put("sha256", PreparationFiles.hash(bytes)) }
                return bytes
            }
            val before = info(requireNotNull(save("info-before.json", "https://api.atlasacademy.io/info", PreparationFiles.MAX_JSON)))
            val references = mutableListOf<AtlasReference>()
            val jpQuestIds = mutableSetOf<Int>()
            AtlasEndpoints.regions.forEach { region -> warIds.sorted().forEach { war ->
                val bytes = save("$region/war/$war.json", "https://api.atlasacademy.io/nice/$region/war/$war", PreparationFiles.MAX_JSON)
                if (region == "JP") requireNotNull(bytes) { "Requested JP war is missing" }
                if (bytes != null) {
                    val metadata = PreparationFiles.json(bytes)
                    if (region == "JP") jpQuestIds += AtlasEndpoints.mainQuests(metadata).map { it.int("id") }
                    references += AtlasEndpoints.inventory(region, metadata, war, questFilter)
                }
            } }
            require(jpQuestIds.containsAll(questFilter)) { "Requested JP main quest is absent from the selected wars" }
            require(references.filter { it.region == "JP" }.isNotEmpty()) { "Requested JP scope has no scripts" }
            require(references.map { it.region to it.id }.distinct().size == references.size) { "Script is shared across requested wars; split discovery scope for explicit review" }
            references.sortedBy { it.path }.forEach { reference ->
                save(reference.path, reference.url, PreparationFiles.MAX_SCRIPT)?.let { PreparationFiles.text(it) }
            }
            val after = info(requireNotNull(save("info-after.json", "https://api.atlasacademy.io/info", PreparationFiles.MAX_JSON)))
            require(before == after) { "Atlas regional data changed during fetch; partial snapshot is not verified" }
            val body = buildJsonObject {
                put("type", "fgogotran-atlas-snapshot"); put("schema_version", 2); put("status", "verified")
                put("war_ids", JsonArray(warIds.sorted().map(::JsonPrimitive)))
                put("quest_filter", JsonArray(questFilter.sorted().map(::JsonPrimitive)))
                put("upstream", before)
                put("files", JsonArray(files.sortedBy { it.string("path") })); put("missing", JsonArray(missing.sortedBy { it.string("path") }))
            }
            val index = JsonObject(body + mapOf("content_sha256" to JsonPrimitive(PreparationFiles.hash(PreparationFiles.canonical(body))),
                "started_at_utc" to JsonPrimitive(started), "finished_at_utc" to JsonPrimitive(Instant.now().toString())))
            PreparationFiles.writeNew(File(pending, "source-index.json"), PreparationFiles.canonical(index))
            load(pending) // Full disk read-back before a verified snapshot becomes discoverable.
            val destination = PreparationFiles.confined(root, File(root, id))
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return load(destination)
        }

        fun load(directory: File): AtlasSnapshot {
            val root = directory.absoluteFile.normalize()
            PreparationFiles.confined(requireNotNull(root.parentFile), root)
            val index = PreparationFiles.json(PreparationFiles.read(File(root, "source-index.json"), PreparationFiles.MAX_JSON))
                .exact("type", "schema_version", "status", "war_ids", "quest_filter", "upstream", "files", "missing", "content_sha256", "started_at_utc", "finished_at_utc")
            require(index.string("type") == "fgogotran-atlas-snapshot" && index.int("schema_version") == 2 && index.string("status") == "verified")
            require(PreparationFiles.hash(PreparationFiles.canonical(content(index))) == index.string("content_sha256"))
            require(!Instant.parse(index.string("finished_at_utc")).isBefore(Instant.parse(index.string("started_at_utc"))))
            val wars = index.getValue("war_ids").jsonArray.map { it.jsonPrimitive.int }.also { require(it.size in 1..8 && it.distinct().size == it.size && it.all { id -> id in 1..999999999 }) }
            val filter = index.getValue("quest_filter").jsonArray.map { it.jsonPrimitive.int }.also { require(it.size <= 128 && it.distinct().size == it.size && it.all { id -> id > 0 }) }.toSet()
            val files = index.getValue("files").jsonArray.map { it.jsonObject.exact("path", "url", "size", "sha256") }
            val missing = index.getValue("missing").jsonArray.map { it.jsonObject.exact("path", "url", "http_status").also { row -> require(row.int("http_status") == 404) } }
            require(files.size + missing.size in 1..PreparationFiles.MAX_FILES)
            val records = files + missing
            require(records.map { it.string("path") }.distinct().size == records.size)
            val byPath = files.associateBy { it.string("path") }
            val verifiedMetadata = mutableMapOf<String, ByteArray>()
            var total = 0L
            files.forEach { entry ->
                AtlasEndpoints.validate(entry.string("url")); StoryPackPolicy.digest(entry.string("sha256"))
                val limit = if (entry.string("path").endsWith(".txt")) PreparationFiles.MAX_SCRIPT else PreparationFiles.MAX_JSON
                val bytes = PreparationFiles.read(PreparationFiles.confined(root, File(root, entry.string("path"))), limit)
                require(bytes.size == entry.int("size") && PreparationFiles.hash(bytes) == entry.string("sha256"))
                if (entry.string("path").endsWith(".json")) verifiedMetadata[entry.string("path")] = bytes
                total += bytes.size; require(total <= PreparationFiles.MAX_SNAPSHOT)
            }
            // Derive quest/phase identities from the exact metadata bytes just hashed,
            // not a second, potentially changed read after provenance verification.
            fun bytes(path: String): ByteArray = verifiedMetadata.getValue(path)
            require(info(bytes("info-before.json")) == index.getValue("upstream") && info(bytes("info-after.json")) == index.getValue("upstream"))
            val expected = linkedMapOf("info-before.json" to "https://api.atlasacademy.io/info", "info-after.json" to "https://api.atlasacademy.io/info")
            val jpQuestIds = mutableSetOf<Int>()
            val references = AtlasEndpoints.regions.flatMap { region -> wars.flatMap { war ->
                val path = "$region/war/$war.json"
                expected[path] = "https://api.atlasacademy.io/nice/$region/war/$war"
                if (region == "JP") require(path in byPath)
                if (path in byPath) {
                    val metadata = PreparationFiles.json(bytes(path))
                    if (region == "JP") jpQuestIds += AtlasEndpoints.mainQuests(metadata).map { it.int("id") }
                    AtlasEndpoints.inventory(region, metadata, war, filter)
                } else emptyList()
            } }
            require(jpQuestIds.containsAll(filter)) { "Snapshot omitted a requested JP main quest" }
            require(references.map { it.region to it.id }.distinct().size == references.size && references.any { it.region == "JP" })
            references.forEach { expected[it.path] = it.url }
            require(records.associate { it.string("path") to it.string("url") } == expected) { "Snapshot records differ from regional discovery" }
            require("info-before.json" in byPath && "info-after.json" in byPath)
            return AtlasSnapshot(root, index, references, byPath)
        }
    }
}
