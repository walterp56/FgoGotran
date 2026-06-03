package com.fgogotran.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.fgogotran.MainActivity
import com.fgogotran.R
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val _serviceStarted = mutableStateOf(false)
        val serviceStarted: State<Boolean> = _serviceStarted

        private var instance: FgoRunnerService? = null
            set(value) {
                field = value
                _serviceStarted.value = value != null
            }

        var mediaProjectionToken: Intent? = null
            set(value) {
                field = value
                if (value != null) {
                    instance?.overlay?.onMediaProjectionReady()
                }
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

        private const val CHANNEL_ID = "fgogotran_runner"
        private const val NOTIFICATION_ID = 1001
    }

    private val tag = "FgoRunner"

    override fun onCreate() {
        super.onCreate()
        FgoLogger.info(tag, "Service created")
        instance = this
        SessionTranslationHistory.clear()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch {
            glossaryUpdateManager.updateIfNeeded()
        }
        overlay.init(onCloseRequested = { stopFromOverlay() })
        overlay.show()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        FgoLogger.info(tag, "Service destroyed")
        overlay.destroy()
        SessionTranslationHistory.clear()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun stopFromOverlay() {
        FgoLogger.info(tag, "Stop requested from floating menu")
        mediaProjectionToken = null
        stopSelf()
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
