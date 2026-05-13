package com.fgogotran.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContract

/**
 * [ActivityResultContract] that launches the system "Share screen" dialog.
 *
 * The user can choose to share:
 * - Entire screen (all apps)
 * - A specific app window (e.g., only FGO)
 *
 * On success, returns the [Intent] (MediaProjection token) to be stored
 * and later used with [MediaProjectionManager.getMediaProjection].
 * On failure/denial, returns null.
 */
class StartMediaProjection : ActivityResultContract<Unit, Intent?>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val mediaProjectionManager =
            context.getSystemService(MediaProjectionManager::class.java)
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    override fun parseResult(resultCode: Int, intent: Intent?) =
        if (resultCode != Activity.RESULT_OK) null else intent
}
