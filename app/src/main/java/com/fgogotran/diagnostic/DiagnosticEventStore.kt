package com.fgogotran.diagnostic

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticEvent(
    val timestampMs: Long,
    val level: String,
    val category: String,
    val eventId: String,
    val title: String,
    val message: String = "",
    val server: String = "",
    val mode: String = "",
    val speaker: String = "",
    val detail: String = "",
    val voiceType: String = "",
    val voiceName: String = "",
    val apiBackend: String = "",
    val errorCode: String = "",
    val textPreview: String = ""
)

@Singleton
class DiagnosticEventStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val eventsFile = File(File(context.filesDir, "diagnostics"), "recent_events.tsv")
    private val exportDir = File(context.cacheDir, "diagnostics")
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()
    private val recentRecordAt = LinkedHashMap<String, Long>()

    init {
        scope.launch {
            mutex.withLock {
                val loaded = readEventsLocked()
                _events.value = loaded
                writeEventsLocked(loaded)
            }
        }
    }

    fun record(
        level: String,
        category: String,
        eventId: String,
        title: String,
        message: String = "",
        server: String = "",
        mode: String = "",
        speaker: String = "",
        detail: String = "",
        voiceType: String = "",
        voiceName: String = "",
        apiBackend: String = "",
        errorCode: String = "",
        textPreview: String = ""
    ) {
        val now = System.currentTimeMillis()
        val safeEvent = DiagnosticEvent(
            timestampMs = now,
            level = normalizeToken(level),
            category = normalizeToken(category),
            eventId = normalizeToken(eventId),
            title = title.cleanField(TITLE_MAX_CHARS),
            message = message.cleanField(MESSAGE_MAX_CHARS),
            server = server.cleanField(SHORT_FIELD_MAX_CHARS),
            mode = mode.cleanField(SHORT_FIELD_MAX_CHARS),
            speaker = speaker.cleanField(SPEAKER_MAX_CHARS),
            detail = detail.cleanField(DETAIL_MAX_CHARS),
            voiceType = voiceType.cleanField(SHORT_FIELD_MAX_CHARS),
            voiceName = voiceName.cleanField(SHORT_FIELD_MAX_CHARS),
            apiBackend = apiBackend.cleanField(SHORT_FIELD_MAX_CHARS),
            errorCode = errorCode.cleanField(SHORT_FIELD_MAX_CHARS),
            textPreview = textPreview.cleanField(TEXT_PREVIEW_MAX_CHARS)
        )
        val duplicateKey = listOf(
            safeEvent.eventId,
            safeEvent.server,
            safeEvent.mode,
            safeEvent.speaker,
            safeEvent.voiceName,
            safeEvent.errorCode,
            safeEvent.message
        ).joinToString("|")
        scope.launch {
            mutex.withLock {
                val previousAt = recentRecordAt[duplicateKey] ?: 0L
                if (now - previousAt < DUPLICATE_SUPPRESS_MS) return@withLock
                recentRecordAt[duplicateKey] = now
                pruneRecentRecordKeys(now)

                val nextEvents = pruneEvents((_events.value + safeEvent))
                _events.value = nextEvents
                writeEventsLocked(nextEvents)
            }
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                _events.value = emptyList()
                recentRecordAt.clear()
                writeEventsLocked(emptyList())
            }
        }
    }

    suspend fun exportTextReport(eventsToExport: List<DiagnosticEvent> = _events.value): File {
        return withContext(Dispatchers.IO) {
            exportDir.mkdirs()
            val file = File(exportDir, "fgogotran_diagnostic_${System.currentTimeMillis()}.txt")
            file.writeText(buildReport(eventsToExport), Charsets.UTF_8)
            file
        }
    }

    private fun buildReport(eventsToExport: List<DiagnosticEvent>): String {
        return buildString {
            appendLine("FgoGotran diagnostic report")
            appendLine("generated_at=${Instant.now()}")
            appendLine("package=${context.packageName}")
            appendLine("app_version=${appVersionLabel()}")
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("event_count=${eventsToExport.size}")
            appendLine()
            eventsToExport.sortedByDescending { it.timestampMs }.forEach { event ->
                append(event.formatForExport())
                appendLine()
            }
        }
    }

    private fun readEventsLocked(): List<DiagnosticEvent> {
        if (!eventsFile.exists()) return emptyList()
        return runCatching {
            eventsFile.readLines(Charsets.UTF_8)
                .asSequence()
                .filter { it.isNotBlank() }
                .filterNot { it.startsWith(HEADER_PREFIX) }
                .mapNotNull(::parseEvent)
                .toList()
                .let(::pruneEvents)
        }.getOrDefault(emptyList())
    }

    private fun writeEventsLocked(events: List<DiagnosticEvent>) {
        eventsFile.parentFile?.mkdirs()
        val tempFile = File(eventsFile.parentFile, "${eventsFile.name}.tmp")
        tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(HEADER)
            events.sortedBy { it.timestampMs }.forEach { event ->
                writer.appendLine(event.toTsvLine())
            }
        }
        if (eventsFile.exists()) {
            eventsFile.delete()
        }
        tempFile.renameTo(eventsFile)
    }

    private fun pruneEvents(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val minTimestamp = System.currentTimeMillis() - RETENTION_MS
        return events
            .filter { it.timestampMs >= minTimestamp }
            .sortedBy { it.timestampMs }
            .takeLast(MAX_EVENTS)
    }

    private fun pruneRecentRecordKeys(now: Long) {
        val iterator = recentRecordAt.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > DUPLICATE_SUPPRESS_MS) {
                iterator.remove()
            }
        }
    }

    private fun parseEvent(line: String): DiagnosticEvent? {
        val columns = line.split('\t')
        if (columns.size < COLUMN_COUNT) return null
        return DiagnosticEvent(
            timestampMs = columns[0].toLongOrNull() ?: return null,
            level = columns[1],
            category = columns[2],
            eventId = columns[3],
            title = columns[4],
            message = columns[5],
            server = columns[6],
            mode = columns[7],
            speaker = columns[8],
            detail = columns[9],
            voiceType = columns[10],
            voiceName = columns[11],
            apiBackend = columns[12],
            errorCode = columns[13],
            textPreview = columns[14]
        )
    }

    private fun DiagnosticEvent.toTsvLine(): String {
        return listOf(
            timestampMs.toString(),
            level,
            category,
            eventId,
            title,
            message,
            server,
            mode,
            speaker,
            detail,
            voiceType,
            voiceName,
            apiBackend,
            errorCode,
            textPreview
        ).joinToString("\t") { it.toTsvField() }
    }

    private fun DiagnosticEvent.formatForExport(): String {
        val parts = mutableListOf<String>()
        parts += Instant.ofEpochMilli(timestampMs).toString()
        parts += level.uppercase()
        parts += eventId
        if (server.isNotBlank()) parts += "server=$server"
        if (mode.isNotBlank()) parts += "mode=$mode"
        if (speaker.isNotBlank()) parts += "speaker=$speaker"
        if (voiceType.isNotBlank()) parts += "voice_type=$voiceType"
        if (voiceName.isNotBlank()) parts += "voice=$voiceName"
        if (apiBackend.isNotBlank()) parts += "api=$apiBackend"
        if (errorCode.isNotBlank()) parts += "code=$errorCode"
        if (message.isNotBlank()) parts += "message=$message"
        if (detail.isNotBlank()) parts += "detail=$detail"
        if (textPreview.isNotBlank()) parts += "text=$textPreview"
        return parts.joinToString(" | ")
    }

    @Suppress("DEPRECATION")
    private fun appVersionLabel(): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            "${info.versionName}($code)"
        }.getOrDefault("unknown")
    }

    private fun String.toTsvField(): String {
        return replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    }

    private fun String.cleanField(maxChars: Int): String {
        return toTsvField().take(maxChars)
    }

    private fun normalizeToken(value: String): String {
        return value.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(SHORT_FIELD_MAX_CHARS)
    }

    companion object {
        const val LEVEL_ERROR = "error"
        const val LEVEL_WARNING = "warning"
        const val LEVEL_INFO = "info"
        const val CATEGORY_APP_ERROR = "app_error"
        const val CATEGORY_SETUP = "setup"
        const val CATEGORY_DATA_UPDATE = "data_update"
        const val CATEGORY_OCR = "ocr"
        const val CATEGORY_MISSING_VOICE = "missing_voice"
        const val CATEGORY_TEMP_VOICE_API = "temp_voice_api"
        const val CATEGORY_VOICE_HINT_API = "voice_hint_api"
        const val CATEGORY_LIVE_VOICE_TRANSLATION = "live_voice_translation"
        private const val MAX_EVENTS = 300
        private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        private const val DUPLICATE_SUPPRESS_MS = 60_000L
        private const val SHORT_FIELD_MAX_CHARS = 64
        private const val TITLE_MAX_CHARS = 80
        private const val MESSAGE_MAX_CHARS = 180
        private const val SPEAKER_MAX_CHARS = 80
        private const val DETAIL_MAX_CHARS = 260
        private const val TEXT_PREVIEW_MAX_CHARS = 80
        private const val COLUMN_COUNT = 15
        private const val HEADER_PREFIX = "timestamp_ms\t"
        private const val HEADER =
            "timestamp_ms\tlevel\tcategory\tevent_id\ttitle\tmessage\tserver\tmode\tspeaker\tdetail\tvoice_type\tvoice_name\tapi_backend\terror_code\ttext_preview"
    }
}
