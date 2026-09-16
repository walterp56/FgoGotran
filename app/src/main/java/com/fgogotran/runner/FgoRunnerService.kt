package com.fgogotran.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import com.fgogotran.capture.MediaProjectionCapture
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.fgogotran.MainActivity
import com.fgogotran.ProjectionConsentActivity
import com.fgogotran.R
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.data.SettingsRepository
import com.fgogotran.speech.RealtimeVoiceTranslationController
import com.fgogotran.speech.RealtimeVoiceTranslationState
import com.fgogotran.terminology.GlossaryUpdateManager
import com.fgogotran.translation.SessionTranslationHistory
import com.fgogotran.util.FgoLogger
import com.fgogotran.voice.VoiceDataUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FgoRunnerService : Service() {

    @Inject lateinit var overlay: FgoRunnerOverlay
    @Inject lateinit var glossaryUpdateManager: GlossaryUpdateManager
    @Inject lateinit var voiceDataUpdateManager: VoiceDataUpdateManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var realtimeVoiceTranslationController: RealtimeVoiceTranslationController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDensityDpi = 0
    private var lastRotation = -1
    private var landscapeBaselineEstablished = false
    private var initialProjectionStarted = false
    @Volatile private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var liveVoicePreferenceWriteJob: Job? = null
    private var projectionReconsentInProgress = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            mainHandler.post {
                realtimeVoiceTranslationController.onDisplayChanged()
                evaluateProjectionRotation()
            }
        }
    }

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

        fun deliverProjectionReconsentResult(resultCode: Int, resultData: Intent?) {
            instance?.handleProjectionReconsentResult(resultCode, resultData)
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
        displayManager = getSystemService(DisplayManager::class.java)
        MediaProjectionCapture.resetForNewRun()
        maybeStartInitialProjection()
        watchLiveVoiceTranslation()
        displayManager?.registerDisplayListener(displayListener, mainHandler)
        serviceScope.launch {
            glossaryUpdateManager.updateIfNeeded()
        }
        serviceScope.launch {
            voiceDataUpdateManager.updateIfNeeded()
        }
        overlay.init(
            onCloseRequested = { stopFromOverlay() },
            onLiveVoiceTranslationToggleRequested = { enabled ->
                setLiveVoiceTranslationEnabled(enabled)
            }
        )
        overlay.show()
    }

    private fun evaluateProjectionRotation(capturedWidth: Int = 0, capturedHeight: Int = 0) {
        maybeStartInitialProjection()
        if (mediaProjection == null) return
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val width = if (capturedWidth > 0 && capturedHeight > 0) capturedWidth else bounds.width()
        val height = if (capturedWidth > 0 && capturedHeight > 0) capturedHeight else bounds.height()
        val densityDpi = resources.configuration.densityDpi
        val rotation = defaultDisplayRotation()
        val isLandscape = width > height

        if (FgoAccessibilityService.instance?.isFgoForegroundActive() != true || !isLandscape) {
            // Not FGO foreground or not landscape: never re-consent here. Re-baseline once FGO is landscape again.
            landscapeBaselineEstablished = false
            lastWidth = width
            lastHeight = height
            lastDensityDpi = densityDpi
            lastRotation = rotation
            return
        }

        if (!landscapeBaselineEstablished) {
            landscapeBaselineEstablished = true
            lastWidth = width
            lastHeight = height
            lastDensityDpi = densityDpi
            lastRotation = rotation
            FgoLogger.info(tag, "MediaProjection landscape baseline established: ${width}x${height}, rotation=$rotation")
            return
        }

        val previousRotation = lastRotation
        lastWidth = width
        lastHeight = height
        lastDensityDpi = densityDpi
        lastRotation = rotation

        val flipped180 = previousRotation != rotation &&
            isLandscapeRotation(previousRotation) &&
            isLandscapeRotation(rotation)
        if (flipped180) {
            FgoLogger.info(tag, "FGO landscape rotated 180°; requesting fresh MediaProjection consent: rotation $previousRotation -> $rotation")
            requestProjectionReconsent()
        }
    }

    private fun isLandscapeRotation(rotation: Int): Boolean {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    private fun maybeStartInitialProjection() {
        if (initialProjectionStarted) return
        val resultCode = pendingResultCode
        val resultData = pendingResultData ?: return
        if (FgoAccessibilityService.instance?.isFgoForegroundActive() != true) return
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        if (bounds.width() <= bounds.height()) return
        initialProjectionStarted = true
        pendingResultData = null
        val started = startProjectionSession(resultCode, resultData)
        if (started) {
            serviceScope.launch {
                if (settingsRepository.liveVoiceTranslationEnabled.first()) {
                    realtimeVoiceTranslationController.start(mediaProjection)
                }
            }
        }
    }

    private fun startProjectionSession(resultCode: Int, resultData: Intent): Boolean {
        return try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
            val callback = mediaProjectionCallbackFor(projection)
            mediaProjection = projection
            mediaProjectionCallback = callback
            projection.registerCallback(callback, mainHandler)
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            val densityDpi = resources.configuration.densityDpi
            val rotation = defaultDisplayRotation()
            lastWidth = bounds.width()
            lastHeight = bounds.height()
            lastDensityDpi = densityDpi
            lastRotation = rotation
            landscapeBaselineEstablished = bounds.width() > bounds.height()
            if (landscapeBaselineEstablished) {
                FgoLogger.info(tag, "MediaProjection landscape baseline established at start: ${bounds.width()}x${bounds.height()}, rotation=$rotation")
            } else {
                FgoLogger.debug(tag, "MediaProjection waiting for landscape baseline at start: ${bounds.width()}x${bounds.height()}, rotation=$rotation")
            }
            val started = MediaProjectionCapture.start(
                projection = projection,
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = densityDpi
            )
            if (!started) {
                FgoLogger.warn(tag, "MediaProjection start failed; falling back to accessibility screenshot")
                releaseMediaProjection(stopProjection = true)
                MediaProjectionCapture.fallbackToAccessibility("start failed")
            }
            started
        } catch (e: Exception) {
            FgoLogger.warn(tag, "MediaProjection start failed; falling back to accessibility screenshot", e)
            releaseMediaProjection(stopProjection = true)
            MediaProjectionCapture.fallbackToAccessibility("start failed")
            false
        }
    }

    private fun requestProjectionReconsent() {
        if (projectionReconsentInProgress) {
            FgoLogger.debug(tag, "MediaProjection re-consent already in progress; ignoring resize")
            return
        }
        if (mediaProjection == null) {
            FgoLogger.debug(tag, "No MediaProjection to re-consent; skipping")
            return
        }
        projectionReconsentInProgress = true
        FgoLogger.info(tag, "Display size changed; requesting fresh MediaProjection consent")
        releaseMediaProjection(stopProjection = true)
        val intent = Intent(this, ProjectionConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun handleProjectionReconsentResult(resultCode: Int, resultData: Intent?) {
        projectionReconsentInProgress = false
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            FgoLogger.warn(tag, "MediaProjection re-consent denied; falling back to accessibility screenshot")
            MediaProjectionCapture.fallbackToAccessibility("re-consent denied")
            return
        }
        if (startProjectionSession(resultCode, resultData)) {
            serviceScope.launch {
                if (settingsRepository.liveVoiceTranslationEnabled.first()) {
                    realtimeVoiceTranslationController.start(mediaProjection)
                }
            }
        }
    }

    private fun mediaProjectionCallbackFor(projection: MediaProjection): MediaProjection.Callback {
        return object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (mediaProjection !== projection) return@post
                    FgoLogger.info(tag, "MediaProjection session stopped by the system")
                    releaseMediaProjection(stopProjection = false)
                    serviceScope.launch {
                        if (settingsRepository.liveVoiceTranslationEnabled.first()) {
                            realtimeVoiceTranslationController.start(projection = null)
                        }
                    }
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width <= 0 || height <= 0 || mediaProjection !== projection) return
                mainHandler.post {
                    if (mediaProjection !== projection) return@post
                    realtimeVoiceTranslationController.onDisplayChanged()
                    evaluateProjectionRotation(capturedWidth = width, capturedHeight = height)
                }
            }
        }
    }

    private fun releaseMediaProjection(stopProjection: Boolean) {
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        landscapeBaselineEstablished = false
        realtimeVoiceTranslationController.stop()
        MediaProjectionCapture.stop()
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
                .onFailure { FgoLogger.warn(tag, "MediaProjection callback removal failed", it) }
        }
        if (stopProjection) {
            runCatching { projection?.stop() }
                .onFailure { FgoLogger.warn(tag, "MediaProjection stop failed", it) }
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

    private fun defaultDisplayRotation(): Int {
        return displayManager
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: Surface.ROTATION_0
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        FgoLogger.info(tag, "Service destroyed")
        FgoAccessibilityService.instance?.stopRunnerSession()
        overlay.destroy()
        SessionTranslationHistory.clear()
        serviceScope.cancel()
        displayManager?.unregisterDisplayListener(displayListener)
        releaseMediaProjection(stopProjection = true)
        realtimeVoiceTranslationController.destroyOverlay()
        instance = null
        super.onDestroy()
    }

    private fun stopFromOverlay() {
        FgoLogger.info(tag, "Stop requested from floating menu")
        stopSelf()
    }

    private fun setLiveVoiceTranslationEnabled(enabled: Boolean) {
        if (!enabled) {
            // Stop immediately; persistence and observers can complete asynchronously.
            realtimeVoiceTranslationController.stop()
        }
        liveVoicePreferenceWriteJob?.cancel()
        liveVoicePreferenceWriteJob = serviceScope.launch {
            settingsRepository.setLiveVoiceTranslationEnabled(enabled)
        }
    }

    private fun watchLiveVoiceTranslation() {
        serviceScope.launch {
            settingsRepository.liveVoiceTranslationEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        realtimeVoiceTranslationController.start(mediaProjection)
                    } else if (realtimeVoiceTranslationController.state.value !is
                        RealtimeVoiceTranslationState.Error
                    ) {
                        realtimeVoiceTranslationController.stop()
                    }
                }
        }
        serviceScope.launch {
            realtimeVoiceTranslationController.state.collect { state ->
                if (state is RealtimeVoiceTranslationState.Error) {
                    // A rejected or terminal start is not left looking enabled in either UI.
                    settingsRepository.setLiveVoiceTranslationEnabled(false)
                }
            }
        }
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
