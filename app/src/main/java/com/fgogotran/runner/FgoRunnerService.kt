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
import com.fgogotran.diagnostic.DiagnosticEventStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    /** Features that need a live MediaProjection session. OCR never needs one by default. */
    private enum class ProjectionConsumer {
        VOICE,
        EXPERIMENTAL_SCREENSHOT
    }

    private val projectionConsumerLock = Any()
    private val projectionConsumers = linkedSetOf<ProjectionConsumer>()
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastDensityDpi = 0
    private var lastRotation = -1
    private var landscapeBaselineEstablished = false
    @Volatile private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var liveVoicePreferenceWriteJob: Job? = null
    @Volatile private var projectionConsentInProgress = false
    private var projectionConsentStartedAt = 0L
    private var projectionStopRequested = false
    private var projectionRotationWatchJob: Job? = null

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

        /**
         * Starts the runner without asking for MediaProjection consent. OCR reads the screen
         * through the accessibility service; MediaProjection consent is requested later, by the
         * feature that actually needs it (live voice or the experimental screenshot source).
         */
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

        /** Delivers the consent result collected by [ProjectionConsentActivity]. */
        fun deliverProjectionConsentResult(resultCode: Int, resultData: Intent?) {
            instance?.handleProjectionConsentResult(resultCode, resultData)
        }

        private const val CHANNEL_ID = "fgogotran_runner"
        private const val NOTIFICATION_ID = 1001

        /**
         * DisplayManager callbacks are not guaranteed on every device/emulator, and a 180° flip
         * keeps the same width/height so onCapturedContentResize() does not fire either. A slow
         * poll keeps the landscape-flip detection (experimental screenshot source) reliable.
         */
        private const val PROJECTION_ROTATION_POLL_INTERVAL_MS = 2_000L

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
        MediaProjectionCapture.resetForNewRun()
        watchProjectionRotation()
        watchLiveVoiceTranslation()
        watchExperimentalScreenshotSetting()
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

    // ─── MediaProjection session: live voice + experimental OCR screenshots ────────────

    /**
     * Rotation only affects the experimental screenshot source. Its video path must be rebuilt
     * after a 180° landscape flip because Android 14 forbids a second createVirtualDisplay() on
     * the same session, while audio-only voice capture does not care about orientation at all.
     * Nothing here runs unless that experimental source is enabled.
     */
    private fun evaluateProjectionRotation(capturedWidth: Int = 0, capturedHeight: Int = 0) {
        if (!isProjectionConsumerActive(ProjectionConsumer.EXPERIMENTAL_SCREENSHOT)) return
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
            FgoLogger.info(
                tag,
                "FGO landscape rotated 180°; requesting fresh MediaProjection consent: " +
                    "$previousRotation -> $rotation"
            )
            requestProjectionReconsent()
        }
    }

    /**
     * Safety net for the 180° detection above: even when the emulator never reports a display
     * change, the landscape flip is still noticed within a couple of seconds.
     */
    private fun watchProjectionRotation() {
        projectionRotationWatchJob?.cancel()
        projectionRotationWatchJob = serviceScope.launch {
            while (isActive) {
                delay(PROJECTION_ROTATION_POLL_INTERVAL_MS)
                mainHandler.post { evaluateProjectionRotation() }
            }
        }
    }

    private fun isLandscapeRotation(rotation: Int): Boolean {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    private fun isProjectionConsumerActive(consumer: ProjectionConsumer): Boolean {
        return synchronized(projectionConsumerLock) { projectionConsumers.contains(consumer) }
    }

    /**
     * Adds a consumer and makes sure a session exists for it. Consent is only ever requested
     * here — never at service start — so plain OCR needs no permission at all and Android 14
     * gets one fresh consent per session.
     */
    private fun acquireProjection(consumer: ProjectionConsumer) {
        if (consumer == ProjectionConsumer.EXPERIMENTAL_SCREENSHOT) {
            // The user explicitly opted in (again), so this source gets a fresh failure budget
            // instead of staying permanently disabled from an earlier failed session.
            MediaProjectionCapture.resetForNewRun()
        }
        synchronized(projectionConsumerLock) { projectionConsumers.add(consumer) }
        val projection = mediaProjection
        if (projection != null) {
            onProjectionReady(consumer, projection)
            return
        }
        requestProjectionConsent(consumer)
    }

    private fun releaseProjection(consumer: ProjectionConsumer) {
        val noConsumersLeft = synchronized(projectionConsumerLock) {
            projectionConsumers.remove(consumer)
            projectionConsumers.isEmpty()
        }
        when (consumer) {
            ProjectionConsumer.VOICE -> realtimeVoiceTranslationController.stop()
            ProjectionConsumer.EXPERIMENTAL_SCREENSHOT -> MediaProjectionCapture.stop()
        }
        // The session itself only goes away when nobody needs it any more.
        if (noConsumersLeft) stopProjectionSession()
    }

    private fun requestProjectionConsent(consumer: ProjectionConsumer) {
        val now = SystemClock.elapsedRealtime()
        var staleRequest = false
        val alreadyRequesting = synchronized(projectionConsumerLock) {
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
            FgoLogger.debug(tag, "MediaProjection consent already in progress; ignoring ${consumer.name}")
            return
        }
        FgoLogger.info(tag, "Requesting MediaProjection consent (${consumer.name})")
        mainHandler.post {
            startActivity(
                Intent(this, ProjectionConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(
                        ProjectionConsentActivity.EXTRA_REQUEST_AUDIO_PERMISSION,
                        consumer == ProjectionConsumer.VOICE
                    )
            )
        }
    }

    private fun handleProjectionConsentResult(resultCode: Int, resultData: Intent?) {
        projectionConsentInProgress = false
        projectionConsentStartedAt = 0L
        projectionStopRequested = false
        val consumers = synchronized(projectionConsumerLock) { projectionConsumers.toList() }
        if (consumers.isEmpty()) {
            // The user switched the feature off while the dialog was open; nothing needs a session.
            FgoLogger.info(tag, "MediaProjection consent result ignored; no consumer is waiting")
            return
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            FgoLogger.warn(tag, "MediaProjection consent denied for ${consumerLabels(consumers)}")
            reportProjectionConsentDenied(consumers)
            disableProjectionConsumers(consumers)
            return
        }
        val projection = createProjectionSession(resultCode, resultData)
        if (projection == null) {
            disableProjectionConsumers(consumers)
            return
        }
        consumers.forEach { consumer -> onProjectionReady(consumer, projection) }
    }

    /**
     * Both MediaProjection features are opt-in, so a rejected or impossible session turns them
     * off instead of leaving the UI claiming that something is running.
     */
    private fun disableProjectionConsumers(consumers: List<ProjectionConsumer>) {
        if (consumers.contains(ProjectionConsumer.VOICE)) {
            serviceScope.launch { settingsRepository.setLiveVoiceTranslationEnabled(false) }
        }
        if (consumers.contains(ProjectionConsumer.EXPERIMENTAL_SCREENSHOT)) {
            serviceScope.launch {
                settingsRepository.setExperimentalMediaProjectionScreenshotEnabled(false)
            }
        }
    }

    private fun createProjectionSession(resultCode: Int, resultData: Intent): MediaProjection? {
        return try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
            val callback = mediaProjectionCallbackFor(projection)
            mediaProjection = projection
            mediaProjectionCallback = callback
            projection.registerCallback(callback, mainHandler)
            projection
        } catch (e: Exception) {
            FgoLogger.warn(tag, "MediaProjection session creation failed", e)
            reportProjectionStartFailure()
            null
        }
    }

    private fun onProjectionReady(consumer: ProjectionConsumer, projection: MediaProjection) {
        when (consumer) {
            ProjectionConsumer.VOICE -> serviceScope.launch {
                if (settingsRepository.liveVoiceTranslationEnabled.first()) {
                    realtimeVoiceTranslationController.start(projection)
                }
            }
            ProjectionConsumer.EXPERIMENTAL_SCREENSHOT -> {
                // The screenshot source touches the rotation baseline state, so keep it on the
                // main thread no matter which thread asked for the session.
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    startExperimentalScreenshotCapture(projection)
                } else {
                    mainHandler.post { startExperimentalScreenshotCapture(projection) }
                }
            }
        }
    }

    /**
     * Starts the VirtualDisplay/ImageReader path used by the experimental OCR source. A failure
     * here leaves the projection itself usable for voice, so only that one source is turned off.
     */
    private fun startExperimentalScreenshotCapture(projection: MediaProjection) {
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val densityDpi = resources.configuration.densityDpi
        val rotation = defaultDisplayRotation()
        lastWidth = bounds.width()
        lastHeight = bounds.height()
        lastDensityDpi = densityDpi
        lastRotation = rotation
        landscapeBaselineEstablished = bounds.width() > bounds.height()
        val started = MediaProjectionCapture.start(
            projection = projection,
            width = bounds.width(),
            height = bounds.height(),
            densityDpi = densityDpi
        )
        if (started) {
            FgoLogger.info(
                tag,
                "MediaProjection screenshot source started: ${bounds.width()}x${bounds.height()}, rotation=$rotation"
            )
            reportProjectionStarted(bounds.width(), bounds.height())
        } else {
            FgoLogger.warn(tag, "MediaProjection screenshot source failed; using accessibility screenshots")
            reportProjectionStartFailure()
            serviceScope.launch {
                settingsRepository.setExperimentalMediaProjectionScreenshotEnabled(false)
            }
        }
    }

    private fun stopProjectionSession() {
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        landscapeBaselineEstablished = false
        MediaProjectionCapture.stop()
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
                .onFailure { FgoLogger.warn(tag, "MediaProjection callback removal failed", it) }
        }
        if (projection != null) {
            projectionStopRequested = true
            runCatching { projection.stop() }
                .onFailure { FgoLogger.warn(tag, "MediaProjection stop failed", it) }
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
                    landscapeBaselineEstablished = false
                    MediaProjectionCapture.stop()
                    if (releasedByUs) {
                        FgoLogger.debug(tag, "MediaProjection session released on request")
                        return@post
                    }
                    FgoLogger.warn(tag, "MediaProjection session stopped by the system")
                    reportProjectionSystemStopped()
                    val consumers = synchronized(projectionConsumerLock) {
                        projectionConsumers.toList()
                    }
                    realtimeVoiceTranslationController.stop()
                    disableProjectionConsumers(consumers)
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

    private fun requestProjectionReconsent() {
        if (projectionConsentInProgress) {
            FgoLogger.debug(tag, "MediaProjection consent already in progress; ignoring rotation")
            return
        }
        projectionConsentInProgress = true
        FgoLogger.info(tag, "Requesting fresh MediaProjection consent (180° landscape flip)")
        // Android 14 forbids reusing one session for a second VirtualDisplay, so the experimental
        // screenshot source needs a brand new session. Voice is restarted from the consent result.
        stopProjectionSession()
        mainHandler.post {
            startActivity(
                Intent(this, ProjectionConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * MediaProjection failures used to be invisible: OCR silently ran on stale or blank frames.
     * Both outcomes are now recorded so a support report shows whether the projection ever
     * started and, if not, that accessibility screenshots took over.
     */
    private fun reportProjectionStartFailure() {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_start_failed",
            title = "MediaProjection 启动失败",
            message = "屏幕捕获未能启动，OCR 继续使用无障碍截图",
            detail = "实时字幕会不可用；请确认 FGO 处于横屏后重新开启对应功能"
        )
    }

    private fun reportProjectionStarted(width: Int, height: Int) {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_INFO,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_started",
            title = "MediaProjection 已启动",
            message = "屏幕捕获 ${width}x${height}"
        )
    }

    private fun consumerLabels(consumers: List<ProjectionConsumer>): String {
        return consumers.joinToString("、") { consumer ->
            when (consumer) {
                ProjectionConsumer.VOICE -> "实时字幕"
                ProjectionConsumer.EXPERIMENTAL_SCREENSHOT -> "实验截图"
            }
        }
    }

    private fun reportProjectionConsentDenied(consumers: List<ProjectionConsumer>) {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_consent_denied",
            title = "未授予屏幕捕获授权",
            message = "已关闭需要屏幕捕获的功能：${consumerLabels(consumers)}",
            detail = "实时字幕与实验截图需要屏幕捕获授权；OCR 翻译不受影响"
        )
    }

    private fun reportProjectionSystemStopped() {
        diagnosticEventStore.record(
            level = DiagnosticEventStore.LEVEL_WARNING,
            category = DiagnosticEventStore.CATEGORY_SETUP,
            eventId = "media_projection_session_stopped",
            title = "屏幕捕获已被系统停止",
            message = "依赖屏幕捕获的功能已关闭，OCR 翻译继续使用无障碍截图",
            detail = "重新开启实时字幕或实验截图时会重新申请授权"
        )
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
        projectionRotationWatchJob?.cancel()
        serviceScope.cancel()
        displayManager?.unregisterDisplayListener(displayListener)
        realtimeVoiceTranslationController.stop()
        synchronized(projectionConsumerLock) { projectionConsumers.clear() }
        stopProjectionSession()
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
                        acquireProjection(ProjectionConsumer.VOICE)
                    } else {
                        releaseProjection(ProjectionConsumer.VOICE)
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

    /**
     * The experimental screenshot source is opt-in and off by default. Switching it on creates a
     * session with a fresh consent prompt; switching it off releases that source immediately and
     * OCR falls back to accessibility screenshots.
     */
    private fun watchExperimentalScreenshotSetting() {
        serviceScope.launch {
            settingsRepository.experimentalMediaProjectionScreenshotEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    if (enabled) {
                        acquireProjection(ProjectionConsumer.EXPERIMENTAL_SCREENSHOT)
                    } else {
                        releaseProjection(ProjectionConsumer.EXPERIMENTAL_SCREENSHOT)
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
