package com.fgogotran

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.fgogotran.runner.FgoRunnerService
import com.fgogotran.util.FgoLogger

/**
 * Transparent host used to request a fresh MediaProjection consent from a
 * background service after the captured display size/orientation changed.
 *
 * Android 14 forbids a second createVirtualDisplay() on the same MediaProjection
 * instance, so a size/orientation change requires a new consent token instead of
 * reusing the existing projection.
 */
class ProjectionConsentActivity : Activity() {

    private var consentRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || consentRequested) {
            return
        }
        consentRequested = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            FgoRunnerService.deliverProjectionReconsentResult(resultCode, data)
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 9001
    }
}
