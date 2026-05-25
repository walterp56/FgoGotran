package com.fgogotran.util

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal [SavedStateRegistryOwner] + [LifecycleOwner] for hosting Compose
 * content inside a Service or WindowManager overlay (outside an Activity).
 *
 * Adapted from FGA's FakedComposeView pattern.
 * Source: https://gist.github.com/handstandsam/6ecff2f39da72c0b38c07aa80bbb5a2f
 */
private class FakeLifecycleOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private var restored = false

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: Bundle?) {
        if (restored) return
        savedStateRegistryController.performRestore(savedState)
        restored = true
    }
}

/**
 * Hosts a [Composable] inside a [ComposeView] that is not attached to an Activity.
 *
 * Provides a fake [LifecycleOwner], [ViewModelStoreOwner], and [SavedStateRegistryOwner]
 * so that Compose components (which depend on these) can render correctly from a
 * [android.app.Service] context.
 *
 * Call [close] to clean up the lifecycle and coroutine scope.
 */
class FakeComposeHost(
    private val context: Context,
    private val content: @Composable () -> Unit
) : AutoCloseable {
    private val viewModelStore = ViewModelStore()
    val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = this@FakeComposeHost.viewModelStore
    }
    private val lifecycleOwner = FakeLifecycleOwner()

    private val coroutineContext = AndroidUiDispatcher.CurrentThread
    private val runRecomposeScope = CoroutineScope(coroutineContext)
    private val recomposer = Recomposer(coroutineContext)
    private var closed = false

    /** The [ComposeView] ready to be added to a WindowManager. */
    val view: ComposeView by lazy {
        ComposeView(context).also { composeView ->
            lifecycleOwner.performRestore(null)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            composeView.setViewTreeLifecycleOwner(lifecycleOwner)
            composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            composeView.compositionContext = recomposer
            composeView.setContent { content() }

            runRecomposeScope.launch {
                recomposer.runRecomposeAndApplyChanges()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runRecomposeScope.cancel()
        viewModelStore.clear()
    }
}
