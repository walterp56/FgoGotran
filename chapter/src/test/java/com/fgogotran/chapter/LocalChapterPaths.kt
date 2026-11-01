package com.fgogotran.chapter

import java.io.File
import java.nio.file.Files

/** Desktop fixtures only. Never accepts Atlas text sources as JP original captures. */
internal object LocalChapterPaths {
    fun capture(module: File, directory: String): File {
        val roots = listOf(File(module, "local-fixtures"), File(module.parentFile, "story_assets/jp-originals"))
        val lexical = File(directory).absoluteFile.toPath().normalize()
        val resolved = File(directory).canonicalFile.toPath()
        require(roots.any { root ->
            val base = root.absoluteFile.toPath().normalize()
            lexical != base && lexical.startsWith(base) && resolved.startsWith(root.canonicalFile.toPath())
        }) { "Capture must be below chapter/local-fixtures or story_assets/jp-originals" }
        var current = lexical
        while (current != null) {
            require(!Files.isSymbolicLink(current) && current.toFile().canonicalFile.toPath() == current) {
                "Linked fixture paths are not supported"
            }
            current = current.parent
        }
        return resolved.toFile()
    }

    fun proofOutput(module: File, capture: File): File {
        val originals = File(module.parentFile, "story_assets/jp-originals").canonicalFile
        val root = if (capture.toPath().startsWith(originals.toPath())) {
            File(module.parentFile, "story_assets/builds")
        } else File(module, "local-output")
        // A unique run preserves previous proof evidence instead of overwriting it.
        val target = File(root, "${capture.name}-${java.util.UUID.randomUUID()}/proof").absoluteFile
        var current = target.toPath()
        while (current != null) {
            require(!Files.isSymbolicLink(current) && current.toFile().canonicalFile.toPath() == current)
            current = current.parent
        }
        check(target.mkdirs()) { "Cannot create proof output: $target" }
        return target
    }
}
