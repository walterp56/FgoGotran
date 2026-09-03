package com.fgogotran.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import androidx.core.content.ContextCompat
import com.fgogotran.game.FgoPackages
import com.fgogotran.util.FgoLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Captures only other apps' opted-in playback; it never selects a microphone source. */
@Singleton
class FgoPlaybackAudioCapture @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val stateLock = Any()
    private var sessionId = 0L
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    fun start(
        projection: MediaProjection,
        onPcmFrame: (ByteArray) -> Unit,
        onFailure: (Throwable) -> Unit
    ): Result<Unit> {
        stop()

        val record = runCatching { createAudioRecord(projection) }
            .getOrElse { return Result.failure(it) }
        val running = AtomicBoolean(true)
        val nextSessionId = synchronized(stateLock) {
            sessionId += 1
            audioRecord = record
            sessionId
        }

        return try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android 无法开始捕获游戏播放声音"
            }
            val thread = Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    readLoop(nextSessionId, record, running, onPcmFrame, onFailure)
                },
                "FgoPlaybackCapture"
            ).apply { isDaemon = true }
            synchronized(stateLock) {
                if (sessionId == nextSessionId && audioRecord === record) {
                    captureThread = thread
                }
            }
            thread.start()
            FgoLogger.info(tag, "Playback audio capture started: 16 kHz mono PCM16")
            Result.success(Unit)
        } catch (t: Throwable) {
            running.set(false)
            releaseRecord(nextSessionId, record, null)
            Result.failure(t)
        }
    }

    fun stop() {
        val detached = synchronized(stateLock) {
            sessionId += 1
            val result = audioRecord to captureThread
            audioRecord = null
            captureThread = null
            result
        }
        val record = detached.first ?: return
        runCatching { record.stop() }
        detached.second?.interrupt()
        if (detached.second !== Thread.currentThread()) {
            runCatching { detached.second?.join(STOP_JOIN_TIMEOUT_MS) }
        }
        runCatching { record.release() }
        FgoLogger.info(tag, "Playback audio capture stopped")
    }

    private fun readLoop(
        expectedSessionId: Long,
        record: AudioRecord,
        running: AtomicBoolean,
        onPcmFrame: (ByteArray) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val buffer = ByteArray(PCM_FRAME_BYTES)
        try {
            while (running.get() && isCurrent(expectedSessionId, record)) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> onPcmFrame(buffer.copyOf(read))
                    read == 0 -> Unit
                    else -> throw IllegalStateException("播放声音捕获失败：AudioRecord error $read")
                }
            }
        } catch (t: Throwable) {
            if (running.get() && isCurrent(expectedSessionId, record)) {
                FgoLogger.warn(tag, "Playback audio capture failed", t)
                onFailure(t)
            }
        } finally {
            running.set(false)
        }
    }

    private fun createAudioRecord(projection: MediaProjection): AudioRecord {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("未授予播放声音捕获权限")
        }
        val fgoUids = installedFgoUids()
        check(fgoUids.isNotEmpty()) { "未找到受支持的 FGO 应用，无法限定播放声音来源" }
        val captureConfigBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        fgoUids.forEach(captureConfigBuilder::addMatchingUid)
        val captureConfig = captureConfigBuilder.build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimumBuffer > 0) { "设备不支持 16 kHz 单声道播放声音捕获" }

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimumBuffer, PCM_FRAME_BYTES * BUFFERED_FRAMES))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("播放声音捕获初始化失败")
        }
        return record
    }

    @Suppress("DEPRECATION")
    private fun installedFgoUids(): Set<Int> {
        val packageManager = context.packageManager
        return FgoPackages.exactNames.mapNotNullTo(linkedSetOf()) { packageName ->
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0L)
                    ).uid
                } else {
                    packageManager.getApplicationInfo(packageName, 0).uid
                }
            }.getOrNull()
        }
    }

    private fun isCurrent(expectedSessionId: Long, record: AudioRecord): Boolean {
        return synchronized(stateLock) {
            sessionId == expectedSessionId && audioRecord === record
        }
    }

    private fun releaseRecord(expectedSessionId: Long, record: AudioRecord, thread: Thread?) {
        synchronized(stateLock) {
            if (sessionId == expectedSessionId && audioRecord === record) {
                sessionId += 1
                audioRecord = null
                if (captureThread === thread) captureThread = null
            }
        }
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    private companion object {
        const val tag = "PlaybackAudio"
        const val SAMPLE_RATE_HZ = 16_000
        const val PCM_FRAME_BYTES = 640 // 20 ms at 16 kHz, mono, signed PCM16.
        const val BUFFERED_FRAMES = 10
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
