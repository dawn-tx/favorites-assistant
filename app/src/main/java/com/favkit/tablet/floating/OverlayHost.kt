package com.favkit.tablet.floating

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.favkit.tablet.ui.theme.FavoritesTheme

/**
 * 悬浮窗里的 ComposeView 必须自己提供 LifecycleOwner / ViewModelStoreOwner /
 * SavedStateRegistryOwner，否则 Compose 在窗口里无法组合。
 */
class OverlayHost(private val context: Context) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var restored = false

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry get() = savedStateController.savedStateRegistry

    fun createView(content: @Composable () -> Unit): ComposeView {
        if (!restored) {
            savedStateController.performRestore(null)
            restored = true
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeViewModelStoreOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setContent { FavoritesTheme { content() } }
        }
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
