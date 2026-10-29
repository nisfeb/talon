package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * iOS back: the swipe in from the left edge, through Compose's own
 * navigation events. Compose Multiplatform's view controller puts a
 * UIScreenEdgePanGestureRecognizer on its view and hands a start-edge
 * pan to the innermost enabled handler, as androidx's BackHandler
 * nests on Android: a sheet opened over a chat pops first.
 *
 * Talon used to draw its own 32dp strip down the edge instead. The
 * outer ~20pt of it went to that recognizer, which had no handler to
 * deliver to, so the swipe did nothing; one that started past the strip
 * went to the content and opened the sections drawer. About half of
 * every back swipe failed one way or the other.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
    BackDepth.Count(enabled)
}
