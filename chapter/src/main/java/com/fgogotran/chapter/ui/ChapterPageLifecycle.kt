package com.fgogotran.chapter.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Route disposal also stops noncritical work; changing routes is not an Activity stop. */
@Composable
internal fun ChapterPageLifecycle(foreground: () -> Unit, background: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val start by rememberUpdatedState(foreground)
    val stop by rememberUpdatedState(background)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}
