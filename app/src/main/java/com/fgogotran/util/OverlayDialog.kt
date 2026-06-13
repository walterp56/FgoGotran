package com.fgogotran.util

import android.os.Build
import android.view.WindowManager

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
