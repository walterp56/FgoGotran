package com.fgogotran.runner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android coordinator wiring checks, not a substitute for FGO/device acceptance. */
class RunnerSessionWiringTest {
    private fun source(path: String) = listOf(File("src/main/java/com/fgogotran/$path"),
        File("app/src/main/java/com/fgogotran/$path")).first(File::isFile).readText().replace("\r\n", "\n")
    private fun section(text: String, start: String, end: String) =
        text.substringAfter(start).substringBefore(end)

    @Test fun `foreground success is required and stop closes authority before consumers`() {
        val runner = source("runner/FgoRunnerService.kt")
        val create = section(runner, "override fun onCreate()", "// ─── MediaProjection")
        assertTrue(create.indexOf("startForegroundCompat()") < create.indexOf("sessionGate.beginSession()"))
        assertTrue(create.indexOf("sessionGate.beginSession()") < create.indexOf("startRunnerSession("))
        assertFalse(runner.contains("_serviceStarted.value = value != null"))
        val stop = section(runner, "private fun endRunnerSession()", "/** Rebuilds")
        for (consumer in listOf("stopRunnerSession(token)", "realtimeVoiceTranslationController.stop()", "overlay.destroy()")) {
            assertTrue(stop.indexOf("sessionGate.endSession(token)") < stop.indexOf(consumer), consumer)
        }
        assertTrue(section(runner, "fun stopService(context:", "/** Rebuilds").contains("instance?.endRunnerSession()"))
        assertTrue(section(runner, "private fun stopFromOverlay()", "private fun setLiveVoice").contains("endRunnerSession()"))
        assertTrue(runner.contains("if (instance === this)"))
    }

    @Test fun `accessibility connection and foreground override do not activate in-game work`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val connect = section(service, "override fun onServiceConnected()", "private fun watchPlayerName()")
        assertFalse(connect.contains("restoreLastTranslationMode()"))
        assertFalse(connect.contains("warmUpManualPipeline()"))
        assertTrue(connect.contains("sessionGate.captureActiveToken()?.let(::startRunnerSession)"))
        val start = section(service, "fun startRunnerSession(token:", "private val storyOcrVisualGate")
        assertTrue(start.contains("sessionGate.isCurrent(token)"))
        assertTrue(start.contains("if (activeRunnerContext()?.token == token) return"))
        assertTrue(start.contains("SupervisorJob(serviceScope.coroutineContext[Job]) + stamp"))
        val restore = section(service, "private fun restoreLastTranslationMode()", "private fun warmUpManualPipeline()")
        assertTrue(restore.indexOf("ensureRunnerWorkCurrent()") > restore.indexOf("getLastTranslationMode()"))
        assertTrue(restore.indexOf("ensureRunnerWorkCurrent()") < restore.indexOf("applyTranslationMode(restoredMode)"))
        assertTrue(section(service, "private fun startDetectionLoop()", "private fun resetDialogueFallbackState()")
            .contains("if (detectionJob?.isActive == true) return"))
        assertTrue(section(service, "override fun onUnbind(", "override fun onInterrupt()").contains("stopRunnerSession()"))
        assertFalse(service.contains("sessionGate.beginSession()"))
    }

    @Test fun `all requests screenshots OCR APIs and gestures have runner gates`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        for (entry in listOf("fun requestManualTranslation(", "fun requestCropTranslation(")) {
            assertTrue(section(service, entry, "private fun ").contains("activeRunnerContext()"), entry)
        }
        assertTrue(section(service, "private fun canStartScreenTranslationNow()", "private fun onDialogueAdvanceObserved()")
            .contains("activeRunnerContext() != null"))
        assertTrue(section(service, "private fun isProcessingModeEnabled(", "private fun lastRenderedSourceTextFor(")
            .contains("if (!isRunnerWorkCurrent()) return false"))
        // Every processing dispatcher boundary revalidates the original stamp before and after work.
        assertEquals(1, Regex("withContext\\(").findAll(service).count()) // Only inside the guarded wrapper.
        val wrapper = section(service, "private suspend fun <T> withRunnerContext(", "private fun launchRunnerWork(")
        assertTrue(wrapper.contains("block().also { produced = it; ensureRunnerWorkCurrent() }"))
        assertTrue(wrapper.contains("(produced as? Bitmap)"))
        val screenshot = section(service, "private suspend fun captureAccessibilityScreenshot():", "private fun refreshAccessibilityConnectionState")
        assertTrue(screenshot.contains("cont.isActive && admitted === activeRunnerContext()"))
        assertTrue(screenshot.contains("value?.recycle()"))
        assertTrue(section(service, "private suspend fun dispatchTapToFgo(", "private fun initScreenSize()")
            .contains("ensureRunnerWorkCurrent()"))
        assertTrue(section(service, "private fun showCropStatus(", "private fun ").contains("isRunnerWorkCurrent()"))
    }

    @Test fun `all game voice is child work and settings tests stay independent`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        assertEquals(4, Regex("runnerToken = RunnerSessionContext.current\\(\\)!!\\.token").findAll(service).count())
        for ((start, end) in listOf("private fun requestVoiceOnlyScene(" to "private suspend fun requestVoiceOnlyVoiceHint(",
            "private fun maybeSpeakRenderedDialogue(" to "private fun voiceSpeakerForDialogue(")) {
            val voiceWork = section(service, start, end)
            assertTrue(voiceWork.contains("launchRunnerWork {"))
            assertFalse(voiceWork.contains("serviceScope.launch"))
        }
        val voice = source("voice/AiVoiceService.kt")
        val production = section(voice, "suspend fun speakDialogue(", "suspend fun prepareAzureVoiceTest(")
        assertTrue(production.indexOf("voiceRequestGate.reserve(") < production.indexOf("prepareVoiceLines("))
        val settings = section(voice, "suspend fun prepareAzureVoiceTest(", "private suspend fun prepareVoiceLines(")
        assertFalse(settings.contains("ensureProductionCurrent("))
        assertFalse(settings.contains("voiceRequestGate"))
        assertTrue(settings.contains("playbackRunnerToken = null"))
        assertTrue(section(voice, "fun stopRunnerSession(", "private suspend fun ensureProductionCurrent(")
            .contains("if (playbackRunnerToken == token)"))
        assertFalse(source("voice/VoicePlaybackEngine.kt").contains("FgoRunnerService"))
        assertFalse(source("translation/Translator.kt").contains("FgoRunnerService"))
    }

    @Test fun `cancellation cannot become a voice failure or profile cooldown`() {
        val voice = source("voice/AiVoiceService.kt")
        assertEquals(2, Regex("if \\(e is CancellationException\\) throw e").findAll(voice).count())
        val failure = voice.substringBefore("tempProfileFailureRetryAt[tempKey] = System.currentTimeMillis()")
            .substringAfterLast("}.onFailure { e ->")
        assertTrue(failure.contains("if (e is CancellationException) throw e"))
        assertTrue(failure.contains("ensureVoiceWorkCurrent()"))
    }

    @Test fun `late overlays history and success analytics cannot escape ended work`() {
        val service = source("accessibility/FgoAccessibilityService.kt")
        val stop = section(service, "fun stopRunnerSession(", "private fun cancelCurrentTranslation()")
        assertTrue(stop.contains("runnerScope?.cancel()"))
        assertTrue(stop.contains("aiVoiceService.stopRunnerSession(ended.token)"))
        assertTrue(stop.contains("stopBattleMonitoring()"))
        assertTrue(section(service, "private fun reportRenderedSceneUsage(", "private fun maybeSpeakRenderedDialogue(")
            .contains("if (!isRunnerWorkCurrent()) return"))
        assertTrue(section(service, "private fun addHistoryEntry(", "private fun ").contains("isRunnerWorkCurrent()"))
        val overlay = source("runner/FgoRunnerOverlay.kt")
        assertFalse(overlay.contains("restoreLastTranslationMode"))
        assertTrue(overlay.contains("requestVersion != showRequestVersion || !FgoRunnerService.sessionGate.isCurrent(runnerToken)"))
        assertTrue(section(overlay, "private fun restoreModeAfterCropSelection(", "private fun isJapaneseServer()")
            .contains("!isRunnerActive()"))
    }

    @Test fun `consent and native queued subtitle callbacks remain attached to their original session`() {
        val runner = source("runner/FgoRunnerService.kt")
        assertTrue(runner.contains("if (isProjectionConsentCurrent(token, requestId))"))
        assertTrue(runner.contains("if (!isProjectionConsentCurrent(token, requestId)) return@post"))
        val consent = source("ProjectionConsentActivity.kt")
        assertTrue(consent.contains("deliverProjectionConsentResult(runnerToken, requestId, resultCode, data)"))
        assertTrue(consent.contains("outState.putLong(EXTRA_RUNNER_TOKEN, runnerToken)"))
        assertTrue(consent.contains("outState.putLong(EXTRA_REQUEST_ID, requestId)"))
        val live = source("speech/RealtimeVoiceTranslationController.kt")
        assertTrue(live.contains("runnerToken?.let(FgoRunnerService.sessionGate::isCurrent) == true"))
        assertTrue(live.contains("if (isCurrent(sessionId)) pcmFrames.trySend(frame)"))
        assertTrue(live.contains("if (!isCurrent(sessionId)) break"))
        assertTrue(live.contains("restartMutex.withLock {"))
        assertTrue(live.contains("writerJob = CoroutineScope(currentCoroutineContext()).launch"))
        val subtitle = source("speech/VoiceSubtitleOverlay.kt")
        assertTrue(subtitle.contains("if (epoch != displayGeneration.get() || !active.get() || !ownerCurrent()) return@post"))
    }
}
