package com.fgogotran.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoicePlaybackEngine @Inject constructor(
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "VoicePlayback"
    private var mediaPlayer: MediaPlayer? = null

    fun play(file: File, volumePercent: Int) {
        stop()
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val volume = volumePercent.coerceIn(MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT) / 100f
            player.setVolume(volume, volume)
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
            }
            player.setOnErrorListener { mp, what, extra ->
                FgoLogger.warn(tag, "Voice playback failed: what=$what extra=$extra")
                diagnosticEventStore.record(
                    level = DiagnosticEventStore.LEVEL_ERROR,
                    category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                    eventId = "voice_playback_failed",
                    title = "音讯播放失败",
                    message = "MediaPlayer error",
                    detail = "what=$what extra=$extra file=${file.name}",
                    errorCode = "$what/$extra"
                )
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
                true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            player.release()
            if (mediaPlayer === player) mediaPlayer = null
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "voice_playback_start_failed",
                title = "音讯播放无法开始",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                detail = file.name
            )
            FgoLogger.warn(tag, "Voice playback could not start", e)
        }
    }

    fun stop() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
            }
            player.release()
        }
        mediaPlayer = null
    }

    private companion object {
        const val MIN_VOLUME_PERCENT = 0
        const val MAX_VOLUME_PERCENT = 100
    }
}
