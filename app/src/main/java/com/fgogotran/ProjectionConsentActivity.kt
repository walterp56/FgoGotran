package com.fgogotran

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fgogotran.runner.FgoRunnerService

/**
 * Transparent host used to request MediaProjection consent for live voice playback capture.
 *
 * OCR never triggers this activity.
 */
class ProjectionConsentActivity : Activity() {

    private var consentRequested = false
    private var runnerToken = 0L
    private var requestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runnerToken = savedInstanceState?.getLong(EXTRA_RUNNER_TOKEN)
            ?: intent.getLongExtra(EXTRA_RUNNER_TOKEN, 0L)
        requestId = savedInstanceState?.getLong(EXTRA_REQUEST_ID)
            ?: intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        consentRequested = savedInstanceState?.getBoolean("consentRequested") ?: false
        if (!FgoRunnerService.isProjectionConsentCurrent(runnerToken, requestId)) {
            finish()
            return
        }
        if (savedInstanceState != null || consentRequested) {
            return
        }
        // Live voice capture also needs the playback audio permission; ask for it here so the
        // floating-menu toggle works even when the app was never started with voice enabled.
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
            return
        }
        requestProjectionConsent()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            // Continue either way: a denied microphone shows up in the voice feature's own error.
            requestProjectionConsent()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestProjectionConsent() {
        if (!FgoRunnerService.isProjectionConsentCurrent(runnerToken, requestId)) {
            finish()
            return
        }
        if (consentRequested) return
        consentRequested = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            FgoRunnerService.deliverProjectionConsentResult(runnerToken, requestId, resultCode, data)
        }
        finish()
    }

    companion object {
        const val EXTRA_RUNNER_TOKEN = "runner_session_token"
        const val EXTRA_REQUEST_ID = "projection_consent_request_id"
        private const val REQUEST_CODE = 9001
        private const val REQUEST_AUDIO_PERMISSION = 9002
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(EXTRA_RUNNER_TOKEN, runnerToken)
        outState.putLong(EXTRA_REQUEST_ID, requestId)
        outState.putBoolean("consentRequested", consentRequested)
        super.onSaveInstanceState(outState)
    }
}
