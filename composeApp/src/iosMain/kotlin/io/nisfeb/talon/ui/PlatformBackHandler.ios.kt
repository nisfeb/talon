package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
    /**
     * Enabled handlers by slot, innermost last.
     *
     * Keyed by slot rather than ordered by registration time, because
     * registration order stops matching nesting the moment an *outer*
     * handler re-registers: PlatformBackHandler binds its
     * DisposableEffect to `enabled`, and a condition like
     * "openChat != null && openThreadParent == null" flips off and on
     * again when a thread closes. The chat's handler then sat above
     * the screens opened over it, and backing out of those took extra
     * gestures while the wrong thing closed first.
     *
     * A slot is taken once per call site and kept for its lifetime, so
     * it reflects declaration order — which in App.kt runs outermost
     * to innermost.
     */
    private val handlers = mutableMapOf<Int, () -> Unit>()
    private var nextSlot = 0

    /** Claim a stable slot. Call once per handler, from remember. */
    fun claimSlot(): Int = nextSlot++

    /** Observable so the root can drop the edge strip entirely at the
     *  top of the stack — no handler, no reason to sit over content. */
    var depth by mutableStateOf(0)
        private set

    fun register(slot: Int, handler: () -> Unit) {
        handlers[slot] = handler
        depth = handlers.size
    }

    fun unregister(slot: Int) {
        handlers.remove(slot)
        depth = handlers.size
    }

    /** Run the innermost handler. Returns false when nothing wanted it,
     *  so the caller can leave the gesture alone (no-op at the root). */
    fun back(): Boolean {
        val top = handlers.maxByOrNull { it.key }?.value ?: return false
        top()
        return true
    }

    val hasHandler: Boolean get() = depth > 0
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    // Claimed once and kept, so enabling and disabling does not change
    // this handler's position relative to the others.
    val slot = remember { IosBackDispatcher.claimSlot() }
    DisposableEffect(enabled, slot) {
        if (!enabled) return@DisposableEffect onDispose { }
        IosBackDispatcher.register(slot) { current() }
        onDispose { IosBackDispatcher.unregister(slot) }
    }
}
