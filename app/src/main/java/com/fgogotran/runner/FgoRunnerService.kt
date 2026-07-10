package com.fgogotran.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

        fun startService(context: Context) {
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

        fun startVoiceSubtitle(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, FgoRunnerService::class.java)
                .setAction(ACTION_START_VOICE_SUBTITLE)
                .putExtra(EXTRA_VOICE_SUBTITLE_RESULT_CODE, resultCode)
                .putExtra(EXTRA_VOICE_SUBTITLE_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val ACTION_START_VOICE_SUBTITLE =
            "com.fgogotran.action.START_VOICE_SUBTITLE"
        private const val EXTRA_VOICE_SUBTITLE_RESULT_CODE =
            "com.fgogotran.extra.VOICE_SUBTITLE_RESULT_CODE"
        private const val EXTRA_VOICE_SUBTITLE_RESULT_DATA =
            "com.fgogotran.extra.VOICE_SUBTITLE_RESULT_DATA"
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
        startOverlayForeground()
        serviceScope.launch {
            glossaryUpdateManager.updateIfNeeded()
        }
        overlay.init(onCloseRequested = { stopFromOverlay() })
        overlay.show()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_VOICE_SUBTITLE) {
            handleStartVoiceSubtitle(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        FgoLogger.info(tag, "Service destroyed")
        FgoAccessibilityService.instance?.stopRunnerSession()
        overlay.destroy()
        SessionTranslationHistory.clear()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun stopFromOverlay() {
        FgoLogger.info(tag, "Stop requested from floating menu")
        stopSelf()
    }

    private fun handleStartVoiceSubtitle(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_VOICE_SUBTITLE_RESULT_CODE, 0)
        val resultData = voiceSubtitleResultData(intent)
        if (resultCode == 0 || resultData == null) {
            FgoLogger.warn(tag, "Voice subtitle capture grant missing")
            serviceScope.launch {
                settingsRepository.setVoiceSubtitleEnabled(false)
            }
            return
        }

        if (!promoteForMediaProjection()) {
            serviceScope.launch {
                settingsRepository.setVoiceSubtitleEnabled(false)
            }
            return
        }
        overlay.startVoiceSubtitleWithProjection(resultCode, resultData)
    }

    private fun startOverlayForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun voiceSubtitleResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_VOICE_SUBTITLE_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_VOICE_SUBTITLE_RESULT_DATA)
        }
    }

    private fun promoteForMediaProjection(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }.onSuccess {
            FgoLogger.info(tag, "Service promoted for media projection")
        }.onFailure { error ->
            FgoLogger.warn(tag, "Failed to promote service for media projection", error)
        }.isSuccess
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
