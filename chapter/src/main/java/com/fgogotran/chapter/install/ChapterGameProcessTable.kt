package com.fgogotran.chapter.install

/** A missing/unrecognised process table is not evidence that FGO stopped. */
internal fun gameStoppedFromProcessTable(text: String, gameUid: Int): Boolean {
    require(text.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    check(lines.firstOrNull()?.trim()?.split(Regex("\\s+")) == listOf("UID", "PID", "NAME")) { "Unrecognised process table" }
    val uids = lines.drop(1).map { line ->
        val columns = line.trim().split(Regex("\\s+"), limit = 3)
        check(columns.size == 3 && (columns[1].toIntOrNull() ?: 0) > 0 && columns[2].isNotBlank()) { "Incomplete process table" }
        requireNotNull(columns[0].toIntOrNull()) { "Non-numeric process owner" }
    }
    check(2000 in uids) { "Process table omitted the shell probe itself" }
    return gameUid !in uids
}
