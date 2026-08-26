package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * iOS back dispatch.
 *
 * A Compose UIViewController draws its own content, so it gets none of
 * UIKit's navigation-controller edge-swipe — iOS had no back gesture at
 * all, and the ship-switcher drawer was the only thing consuming the
 * left edge. The root view (MainViewController) owns an edge strip that
 * calls [back]; every screen registers its intent through
 * [PlatformBackHandler] exactly as it does on Android.
 *
 * Handlers are a stack: the most recently registered enabled one wins,
 * matching androidx's BackHandler semantics, so a sheet opened over a
 * chat pops before the chat does.
 */
object IosBackDispatcher {
    private val handlers = mutableListOf<() -> Unit>()

    /** Observable so the root can drop the edge strip entirely at the
     *  top of the stack — no handler, no reason to sit over content. */
    var depth by mutableStateOf(0)
        private set

    fun register(handler: () -> Unit) {
        handlers.add(handler)
        depth = handlers.size
    }

    fun unregister(handler: () -> Unit) {
        handlers.remove(handler)
        depth = handlers.size
    }

    /** Run the innermost handler. Returns false when nothing wanted it,
     *  so the caller can leave the gesture alone (no-op at the root). */
    fun back(): Boolean {
        val top = handlers.lastOrNull() ?: return false
        top()
        return true
    }

    val hasHandler: Boolean get() = depth > 0
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val handler: () -> Unit = { current() }
        IosBackDispatcher.register(handler)
        onDispose { IosBackDispatcher.unregister(handler) }
    }
}
