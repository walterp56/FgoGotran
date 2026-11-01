package com.fgogotran.chapter.prepare

import com.fgogotran.chapter.*
import com.fgogotran.chapter.codec.FgoJpFormatProfile
import com.fgogotran.chapter.packs.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Explicit developer entry points. No APK integration, ADB, installer, background worker or API translator. */
object NativePreparationMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 2)
        val repository = File(args[1]).absoluteFile.normalize()
        require(File(repository, "chapter/build.gradle.kts").isFile)
        when (args[0]) {
            "discover" -> {
                require(args.size == 4)
                fun ids(raw: String): Set<Int> = if (raw == "-") emptySet() else raw.split(',').map { it.toInt() }.also { require(it.distinct().size == it.size) }.toSet()
                val snapshot = AtlasSnapshot.download(repository, ids(args[2]), ids(args[3]))
                println("ATLAS SOURCES ONLY. No device, native installation or translation API.")
                println("Snapshot: ${snapshot.directory}")
                println("Content SHA256: ${snapshot.contentSha256}")
                println("Regional references: ${snapshot.references.size}")
            }
            "build" -> {
                require(args.size == 6)
                val snapshotDirectory = PreparationFiles.confined(File(repository, "story_assets/atlas-r2"), File(args[2]))
                require(!snapshotDirectory.name.startsWith('.')) { "Partial snapshots cannot be built" }
                val snapshot = AtlasSnapshot.load(snapshotDirectory)
                val capture = PreparationFiles.confined(File(repository, "story_assets/jp-originals"), File(args[3]))
                val record = PreparationFiles.json(PreparationFiles.read(File(capture, "capture.json"), PreparationFiles.MAX_JSON))
                require(record.int("schema_version") == 1 && record.string("package") == StoryPackPolicy.GAME_PACKAGE)
                val version = StoryGameVersion(record.string("version_name"), record.string("version_code").toLong())
                val declarations = record.getValue("files").jsonArray.map { it.jsonObject }
                require(declarations.size in 2..17 && declarations.map { it.string("path") }.distinct().size == declarations.size)
                val originals = declarations.map { entry ->
                    val path = entry.string("path")
                    require(path == FgoJpFormatProfile.MANIFEST_NAME || path.matches(Regex("[0-9a-f]{40}\\.bin")))
                    val bytes = PreparationFiles.read(PreparationFiles.confined(capture, File(capture, path)), StoryPackPolicy.MAX_ASSET)
                    require(entry.getValue("size_bytes").jsonPrimitive.long == bytes.size.toLong() && entry.string("sha256") == PreparationFiles.hash(bytes))
                    OriginalAsset.capture(GameAssetPath.parse(path), bytes)
                }
                val manifest = originals.single { it.fingerprint.path.value == FgoJpFormatProfile.MANIFEST_NAME }
                require(manifest.fingerprint.sha256.hex == record.string("manifest_sha256"))
                val assets = originals.filter { it != manifest }
                val languages = if (args[4] == "all") StoryLanguage.entries else listOf(StoryLanguage.parse(args[4]))
                val reviews = if (args[5] == "-") null else PreparationFiles.confined(File(repository, "story_assets/mappings"), File(args[5]))
                val proofRoot = PreparationFiles.confined(repository, File(repository, "story_assets/builds/r2-${UUID.randomUUID()}"))
                Files.createDirectories(proofRoot.toPath())
                languages.forEach { language ->
                    val directory = reviews?.let { PreparationFiles.confined(it, File(it, language.region)) }
                    val mappings = if (directory == null || !directory.exists()) emptyMap() else {
                        val files = requireNotNull(directory.listFiles()).also { require(it.size <= 256) }
                        files.map { file ->
                            require(file.name.matches(Regex("[A-Za-z0-9_]{1,64}\\.json")))
                            val mapping = ReviewedAlignment.read(PreparationFiles.read(PreparationFiles.confined(directory, file), PreparationFiles.MAX_JSON))
                            require(mapping.scriptId == file.nameWithoutExtension)
                            mapping
                        }.associateBy { it.scriptId }
                    }
                    val candidate = NativeCandidateBuilder().build(language, snapshot, version, manifest, assets, mappings)
                    val destination = NativeCandidateStore.write(repository, candidate)
                    val pairs = candidate.proofs.map { proof ->
                        val before = "${language.region}/${proof.asset}.original.unity"
                        val after = "${language.region}/${proof.asset}.rebuilt.unity"
                        PreparationFiles.writeNew(PreparationFiles.confined(proofRoot, File(proofRoot, before)), proof.originalUnity)
                        PreparationFiles.writeNew(PreparationFiles.confined(proofRoot, File(proofRoot, after)), proof.rebuiltUnity)
                        buildJsonObject {
                            put("asset_filename", proof.asset); put("original", before); put("rebuilt", after)
                            put("original_sha256", PreparationFiles.hash(proof.originalUnity)); put("rebuilt_sha256", PreparationFiles.hash(proof.rebuiltUnity))
                            put("changed_scripts", JsonArray(proof.changedScripts.map(::JsonPrimitive)))
                        }
                    }
                    val metadata = PreparationFiles.json(candidate.metadata)
                    PreparationFiles.writeNew(File(proofRoot, "${language.region}-verification-plan.json"), PreparationFiles.canonical(buildJsonObject {
                        put("type", "fgogotran-native-offline-verification"); put("schema_version", 1)
                        put("candidate_sha256", PreparationFiles.hash(candidate.metadata)); put("pairs", JsonArray(pairs))
                    }))
                    println("${language.tag}: ${metadata.int("translated_scripts")} translated scripts; ${candidate.payloads.size} cumulative rebuilt bundles")
                    println("Candidate: $destination")
                }
                println("Proofs: $proofRoot")
                println("DEVELOPMENT ONLY. Native playback/installation unverified. No whole-chapter/full-game claim. R1 gate unchanged.")
            }
            else -> error("Use discover or build")
        }
    }
}
