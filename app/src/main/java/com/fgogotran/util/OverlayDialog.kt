package com.fgogotran.util

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog

/**
 * The correct overlay window type for the current API level.
 * TYPE_APPLICATION_OVERLAY (API 26+) is preferred; TYPE_PHONE on older versions.
 */
val overlayType: Int
    @Suppress("DEPRECATION")
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

/**
 * Creates and shows an [AlertDialog] as a system overlay window.
 *
 * This allows dialogs to appear on top of other apps (e.g., FGO) when called
 * from a Service context rather than an Activity.
 *
 * Usage:
 * ```kotlin
 * showOverlayDialog(service) {
 *     setTitle("Menu")
 *     setMessage("Choose an option")
 *     setPositiveButton("OK") { _, _ -> }
 * }
 * ```
 */
fun showOverlayDialog(context: Context, builder: AlertDialog.Builder.() -> Unit): AlertDialog {
    val alertDialog = AlertDialog.Builder(context)
        .apply(builder)
        .create()

    // Set window type to overlay so it appears on top of other apps
    alertDialog.window?.setType(overlayType)
    alertDialog.show()

    return alertDialog
}
