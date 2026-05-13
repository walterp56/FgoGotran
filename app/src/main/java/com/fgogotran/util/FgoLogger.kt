package com.fgogotran.util

import android.util.Log

// Lightweight logging wrapper around android.util.Log.
// All messages are tagged with "FGO/ComponentName" for easy filtering with logcat.
// Set isEnabled = false to suppress all log output in release builds.

object FgoLogger {

    // Toggle to suppress all log output without removing log calls.
    var isEnabled: Boolean = true

    // Flow traces, data sizes, timing info.
    fun debug(tag: String, message: String) {
        if (isEnabled) Log.d("FGO/$tag", message)
    }

    // State transitions, lifecycle events, user-visible changes.
    fun info(tag: String, message: String) {
        if (isEnabled) Log.i("FGO/$tag", message)
    }

    // Recoverable issues that don't break the pipeline but may degrade output.
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (isEnabled) Log.w("FGO/$tag", message, throwable)
    }

    // Caught exceptions - something definitely went wrong at this point.
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (isEnabled) Log.e("FGO/$tag", message, throwable)
    }
}
