package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Cross-platform back-button handler. Android delegates to
 * androidx.activity.compose.BackHandler so the system Back button
 * navigates up through the in-app state machine instead of killing
 * the activity; iOS to Compose's navigation events, which its own
 * left-edge swipe drives. Desktop is a no-op — Compose Desktop has no
 * equivalent affordance (window-close is wired separately).
 *
 * Register one per non-root state with `enabled` gating. Every one is
 * counted in [BackDepth].
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * How many back handlers are on across the app. None is the top of the
 * back stack, the one place a swipe may open the sections drawer
 * ([TalonDrawer]): anywhere below it a swipe is the way back, and the
 * drawer opening instead was half of what iOS users met.
 */
object BackDepth {
    var depth by mutableStateOf(0)
        private set

    /** For [PlatformBackHandler]'s actuals: count this handler while [enabled]. */
    @Composable
    fun Count(enabled: Boolean) {
        DisposableEffect(enabled) {
            if (enabled) depth++
            onDispose { if (enabled) depth-- }
        }
    }
}
