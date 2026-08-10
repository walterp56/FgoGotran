package com.fgogotran.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.util.FgoLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class VoicePlaybackEngine @Inject constructor(
    private val diagnosticEventStore: DiagnosticEventStore
) {
    private val tag = "VoicePlayback"
    private val mediaPlayers = mutableListOf<MediaPlayer>()

    fun play(file: File, volumePercent: Int) {
        playTogether(listOf(file), volumePercent)
    }

    fun playTogether(files: List<File>, volumePercent: Int) {
        stop()
        val playableFiles = files.filter { it.exists() && it.length() > 0L }
        if (playableFiles.isEmpty()) return

        val preparedPlayers = mutableListOf<MediaPlayer>()
        try {
            val playerVolumePercent = mixedVolumePercent(volumePercent, playableFiles.size)
            playableFiles.forEach { file ->
                preparedPlayers += preparePlayer(file, playerVolumePercent)
            }
            synchronized(mediaPlayers) {
                mediaPlayers.addAll(preparedPlayers)
            }
            preparedPlayers.forEach { it.start() }
        } catch (e: Exception) {
            preparedPlayers.forEach { runCatching { it.release() } }
            synchronized(mediaPlayers) {
                mediaPlayers.removeAll(preparedPlayers.toSet())
            }
            diagnosticEventStore.record(
                level = DiagnosticEventStore.LEVEL_ERROR,
                category = DiagnosticEventStore.CATEGORY_APP_ERROR,
                eventId = "voice_playback_start_failed",
                title = "音讯播放无法开始",
                message = e.message.orEmpty().ifBlank { e::class.java.simpleName },
                detail = playableFiles.joinToString(",") { it.name }
            )
            FgoLogger.warn(tag, "Voice playback could not start", e)
        }
    }

    private fun preparePlayer(file: File, volumePercent: Int): MediaPlayer {
        val player = MediaPlayer()
        return try {
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
                removePlayer(it)
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
                removePlayer(mp)
                true
            }
            player.prepare()
            player
        } catch (e: Exception) {
            player.release()
            throw e
        }
    }

    fun stop() {
        val players = synchronized(mediaPlayers) {
            mediaPlayers.toList().also { mediaPlayers.clear() }
        }
        players.forEach { player ->
            runCatching {
                if (player.isPlaying) player.stop()
            }
            player.release()
        }
    }

    private fun removePlayer(player: MediaPlayer) {
        synchronized(mediaPlayers) {
            mediaPlayers.remove(player)
        }
    }

    private fun mixedVolumePercent(volumePercent: Int, voiceCount: Int): Int {
        val factor = when (voiceCount) {
            0, 1 -> 1.0f
            2 -> 0.80f
            3 -> 0.65f
            else -> 0.55f
        }
        return (volumePercent * factor)
            .roundToInt()
            .coerceIn(MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT)
    }

    private companion object {
        const val MIN_VOLUME_PERCENT = 0
        const val MAX_VOLUME_PERCENT = 100
    }
}
