package com.fgogotran.diagnostic

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Called under the store's IO mutex. Only expected disk-access failures are contained. */
internal fun writeDiagnosticFile(target: File, write: (BufferedWriter) -> Unit): Exception? {
    return try {
        val destination = target.absoluteFile.toPath()
        Files.createDirectories(destination.parent)
        // A fixed sibling is reused on the next write if a failed write leaves it behind.
        val temporary = destination.resolveSibling("${target.name}.tmp")
        Files.newBufferedWriter(temporary, Charsets.UTF_8).use(write)
        // Do not delete the previous report before the replacement is ready.
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        null
    } catch (error: IOException) {
        error
    } catch (error: SecurityException) {
        error
    }
}
