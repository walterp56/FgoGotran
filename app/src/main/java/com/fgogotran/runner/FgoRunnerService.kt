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
import android.os.SystemClock
import android.view.Display
import androidx.core.app.ServiceCompat
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.fgogotran.MainActivity
import com.fgogotran.ProjectionConsentActivity
import com.fgogotran.R
import com.fgogotran.accessibility.AccessibilityConnectionState
import com.fgogotran.accessibility.FgoAccessibilityService
import com.fgogotran.data.SettingsRepository
import com.fgogotran.diagnostic.DiagnosticEventStore
import com.fgogotran.localization.AppLanguageManager
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
    @Inject lateinit var diagnosticEventStore: DiagnosticEventStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null
    @Volatile private var voiceProjectionRequested = false
    private val projectionConsentLock = Any()
    @Volatile private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var liveVoicePreferenceWriteJob: Job? = null
    @Volatile private var projectionConsentInProgress = false
    private var projectionConsentStartedAt = 0L
    private var projectionStopRequested = false
    private var foregroundStarted = false
    private var projectionForegroundActive = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            mainHandler.post {
                realtimeVoiceTranslationController.onDisplayChanged()
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

        /**
         * Starts the runner without asking for MediaProjection consent. OCR reads the screen
         * through the accessibility service; MediaProjection consent is requested only when the
         * live voice feature is enabled.
         */
        fun startService(context: Context) {
            if (FgoAccessibilityService.refreshConnectionState(context, "runner_start") !=
                AccessibilityConnectionState.CONNECTED
            ) {
                FgoLogger.warn("FgoRunner", "Runner start skipped: accessibility service not connected")
                return
            }
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

        /** Rebuilds the floating overlay so it picks up a changed UI language. */
        fun refreshOverlayLanguage() {
            instance?.refreshOverlayLanguage()
        }

        /** Delivers the consent result collected by [ProjectionConsentActivity]. */
        fun deliverProjectionConsentResult(resultCode: Int, resultData: Intent?) {
            instance?.handleProjectionConsentResult(resultCode, resultData)
        }

        private const val CHANNEL_ID = "fgogotran_runner"
        private const val NOTIFICATION_ID = 1001

        /**
         * A consent activity can be dismissed without ever reporting a result (for example when
         * the emulator kills it). After this long the request counts as stale so a later toggle
         * can ask again instead of being ignored forever.
         */
        private const val PROJECTION_CONSENT_STALE_MS = 60_000L
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

    // ─── MediaProjection session: live voice only ──────────────────────────────────────

    /**
     * Live voice is the only MediaProjection consumer. Consent is requested only when voice is
     * enabled, never at service start.
     */
    private fun acquireVoiceProjection() {
        voiceProjectionRequested = true
        val projection = mediaProjection
        if (projection != null) {
            startVoiceProjection(projection)
            return
        }
        requestProjectionConsent()
    }

    private fun releaseVoiceProjection() {
        voiceProjectionRequested = false
        realtimeVoiceTranslationController.stop()
        if (mediaProjection != null) {
            stopProjectionSession()
        }
    }

    private fun requestProjectionConsent() {
        val now = SystemClock.elapsedRealtime()
        var staleRequest = false
        val alreadyRequesting = synchronized(projectionConsentLock) {
            val stale = projectionConsentInProgress &&
                now - projectionConsentStartedAt >= PROJECTION_CONSENT_STALE_MS
            if (projectionConsentInProgress && !stale) {
                true
            } else {
                staleRequest = stale
                projectionConsentInProgress = true
                projectionConsentStartedAt = now
                false
            }
        }
        if (staleRequest) {
            FgoLogger.warn(tag, "Previous MediaProjection consent request looked stale; asking again")
        }
        if (alreadyRequesting) {
            FgoLogger.debug(tag, "MediaProjection consent already in progress; ignoring live voice request")
            return
        }
        FgoLogger.info(tag, "Requesting MediaProjection consent (live voice)")
        mainHandler.post {
            startActivity(
                Intent(this, ProjectionConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun handleProjectionConsentResult(resultCode: Int, resultData: Intent?) {
        projectionConsentInProgress = false
        projectionConsentStartedAt = 0L
        projectionStopRequested = false
        if (!voiceProjectionRequested) {
            FgoLogger.info(tag, "MediaProjection consent result ignored; live voice is no longer requested")
            return
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            FgoLogger.warn(tag, "MediaProjection consent denied for live voice")
            reportProjectionConsentDenied()
            disableLiveVoiceTranslation()
            return
        }
        val projection = createProjectionSession(resultCode, resultData)
        if (projection == null) {
            disableLiveVoiceTranslation()
            return
        }
        startVoiceProjection(projection)
    }

    /**
     * A rejected or impossible projection session turns live voice off instead of leaving the UI
     * claiming that voice is running.
     */
    private fun disableLiveVoiceTranslation() {
        voiceProjectionRequested = false
        serviceScope.launch { settingsRepository.setLiveVoiceTranslationEnabled(false) }
    }

    private fun createProjectionSession(resultCode: Int, resultData: Intent): MediaProjection? {
        return try {
            // Android 14+ requires consent before the mediaProjection foreground type is used.
            // The runner itself only needs specialUse for its floating controls and accessibility OCR.
            setProjectionForegroundActive(true)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
            val callback = mediaProjectionCallbackFor(projection)
            mediaProjection = projection
            mediaProjectionCallback = callback
            projection.registerCallback(callback, mainHandler)
            reportProjectionStarted()
            projection
        } catch (e: Exception) {
            runCatching { setProjectionForegroundActive(false) }
                .onFailure { FgoLogger.warn(tag, "Could not restore runner foreground type", it) }
            FgoLogger.warn(tag, "MediaProjection session creation failed", e)
            reportProjectionStartFailure()
            null
        }
    }

    private fun startVoiceProjection(projection: MediaProjection) {
        serviceScope.launch {
            if (voiceProjectionRequested && settingsRepository.liveVoiceTranslationEnabled.first()) {
                realtimeVoiceTranslationController.start(projection)
            }
        }
    }

    private fun stopProjectionSession() {
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
                .onFailure { FgoLogger.warn(tag, "MediaProjection callback removal failed", it) }
        }
        if (projection != null) {
            projectionStopRequested = true
            runCatching { projection.stop() }
                .onFailure { FgoLogger.warn(tag, "MediaProjection stop failed", it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { setProjectionForegroundActive(false) }
                .onFailure { FgoLogger.warn(tag, "Could not restore runner foreground type", it) }
        }
    }

    private fun mediaProjectionCallbackFor(projection: MediaProjection): MediaProjection.Callback {
        return object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    if (mediaProjection !== projection) return@post
                    val releasedByUs = projectionStopRequested
                    projectionStopRequested = false
                    mediaProjection = null
                    mediaProjectionCallback = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        runCatching { setProjectionForegroundActive(false) }
                            .onFailure { FgoLogger.warn(tag, "Could not restore runner foreground type", it) }
                    }
                    if (releasedByUs) {
                        FgoLogger.debug(tag, "MediaProjection session released on request")
                        return@post
                    }
                    FgoLogger.warn(tag, "MediaProjection session stopped by the system")
                    reportProjectionSystemStopped()
                    realtimeVoiceTranslationController.stop()
                    disableLiveVoiceTranslation()
                }
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width <= 0 || height <= 0 || mediaProjection !== projection) return
                mainHandler.post {
                    if (mediaProjection !== projection) return@post
                    realtimeVoiceTranslationController.onDisplayChanged()
                }
            }
        }
    }

    /**
     * Records a MediaProjection startup failure so support logs show that live voice could not
     * start without implying an OCR screenshot-source fallback.
     */
    private fun reportProjectionStartFailure() {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_start_failed",
            title = "MediaProjection 启动失败",
            message = "实时字幕屏幕捕获未能启动",
            detail = "OCR 翻译不受影响；请重新开启实时语音并允许屏幕捕获授权"
        )
    }

    private fun reportProjectionStarted() {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_INFO,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_started",
            title = "MediaProjection 已启动",
            message = "实时字幕屏幕捕获已启动"
        )
    }

    private fun reportProjectionConsentDenied() {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_consent_denied",
            title = "未授予屏幕捕获授权",
            message = "已关闭实时语音屏幕捕获",
            detail = "实时语音需要屏幕捕获授权；OCR 翻译不受影响"
        )
    }

    private fun reportProjectionSystemStopped() {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_session_stopped",
            title = "屏幕捕获已被系统停止",
            message = "实时语音已停止，OCR 翻译继续使用无障碍截图",
            detail = "重新开启实时语音时会重新申请屏幕捕获授权"
        )
    }

    private fun startForegroundCompat() {
        setProjectionForegroundActive(false)
    }

    @Synchronized
    private fun setProjectionForegroundActive(active: Boolean) {
        if (foregroundStarted &&
            (projectionForegroundActive == active || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        ) {
            projectionForegroundActive = active
            return
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        projectionForegroundActive = active
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        FgoLogger.info(tag, "Service destroyed")
        FgoAccessibilityService.instance?.stopRunnerSession()
        overlay.destroy()
        SessionTranslationHistory.clear()
        serviceScope.cancel()
        displayManager?.unregisterDisplayListener(displayListener)
        realtimeVoiceTranslationController.stop()
        stopProjectionSession()
        realtimeVoiceTranslationController.destroyOverlay()
        instance = null
        super.onDestroy()
    }

    /** Rebuilds the floating overlay views so they pick up a changed UI language. */
    fun refreshOverlayLanguage() {
        overlay.refreshUiLanguage()
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

    /**
     * Live voice owns a MediaProjection session while it is switched on. The consent prompt is
     * raised from here, on the user's toggle, instead of at service start.
     */
    private fun watchLiveVoiceTranslation() {
        serviceScope.launch {
            settingsRepository.liveVoiceTranslationEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        acquireVoiceProjection()
                    } else {
                        releaseVoiceProjection()
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
            .setContentText(AppLanguageManager.wrap(this).getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
