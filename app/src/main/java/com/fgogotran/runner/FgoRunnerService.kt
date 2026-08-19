package com.fgogotran.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import com.fgogotran.capture.MediaProjectionCapture
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.fgogotran.MainActivity
import com.fgogotran.R
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.data.SettingsRepository
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.translation.SessionTranslationHistory
import com.fgogotran.util.FgoLogger
import com.fgogotran.voice.VoiceDataUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FgoRunnerService : Service() {

    @Inject lateinit var overlay: FgoRunnerOverlay
    @Inject lateinit var glossaryUpdateManager: GlossaryUpdateManager
    @Inject lateinit var voiceDataUpdateManager: VoiceDataUpdateManager
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val _serviceStarted = mutableStateOf(false)
        val serviceStarted: State<Boolean> = _serviceStarted

        private var instance: FgoRunnerService? = null
            set(value) {
                field = value
                _serviceStarted.value = value != null
            }

        private var pendingResultCode = android.app.Activity.RESULT_OK
        private var pendingResultData: Intent? = null

        fun startService(
            context: Context,
            resultCode: Int = android.app.Activity.RESULT_OK,
            resultData: Intent? = null
        ) {
            pendingResultCode = resultCode
            pendingResultData = resultData
            val intent = Intent(context, FgoRunnerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context): Boolean {
            val intent = Intent(context, FgoRunnerService::class.java)
            return context.stopService(intent)
        }

        private const val CHANNEL_ID = "fgogotran_runner"
        private const val NOTIFICATION_ID = 1001
    }

    private val tag = "FgoRunner"

    override fun onCreate() {
        super.onCreate()
        watchDebugLogging()
        FgoLogger.info(tag, "Service created")
        instance = this
        SessionTranslationHistory.clear()
        createNotificationChannel()
        startForegroundCompat()
        createMediaProjection()
        serviceScope.launch {
            glossaryUpdateManager.updateIfNeeded()
        }
        serviceScope.launch {
            voiceDataUpdateManager.updateIfNeeded()
        }
        overlay.init(onCloseRequested = { stopFromOverlay() })
        overlay.show()
    }

    private fun createMediaProjection() {
        val resultCode = pendingResultCode
        val resultData = pendingResultData ?: return
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            MediaProjectionCapture.start(
                projection = projection,
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = resources.displayMetrics.densityDpi
            )
            FgoLogger.info(tag, "MediaProjection capture started: ${bounds.width()}x${bounds.height()}")
        } catch (e: Exception) {
            FgoLogger.warn(tag, "MediaProjection start failed; falling back to accessibility screenshot", e)
            MediaProjectionCapture.stop()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        FgoLogger.info(tag, "Service destroyed")
        FgoAccessibilityService.instance?.stopRunnerSession()
        overlay.destroy()
        SessionTranslationHistory.clear()
        serviceScope.cancel()
        MediaProjectionCapture.stop()
        instance = null
        super.onDestroy()
    }

    private fun stopFromOverlay() {
        FgoLogger.info(tag, "Stop requested from floating menu")
        stopSelf()
    }

    private fun watchDebugLogging() {
        serviceScope.launch {
            settingsRepository.debugLoggingEnabled.collect { enabled ->
                FgoLogger.setEnabled(enabled)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FgoGotran Translation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FgoGotran translation service is running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FgoGotran")
            .setContentText("翻译悬浮窗正在运行")
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
