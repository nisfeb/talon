package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * iOS back: a swipe right, through Compose's own navigation events.
 * Compose Multiplatform's view controller puts a
 * UIScreenEdgePanGestureRecognizer on its view, and [IosBackSwipeHost]
 * adds a swipe from anywhere else; either goes to the innermost enabled
 * handler, as androidx's BackHandler nests on Android: a sheet opened
 * over a chat pops first. While the swipe is under way the screen
 * follows it ([IosBackSwipe]).
 *
 * Talon used to draw its own 32dp strip down the edge instead. The
 * outer ~20pt of it went to that recognizer, which had no handler to
 * deliver to, so the swipe did nothing; one that started past the strip
 * went to the content and opened the sections drawer. About half of
 * every back swipe failed one way or the other.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = state,
        isBackEnabled = enabled,
        onBackCancelled = { IosBackSwipe.settle() },
        onBackCompleted = {
            IosBackSwipe.done()
            onBack()
        },
    )
    // This handler's own swipe, so a sheet's back (Material's) does not slide the screen under it.
    val swipe = state.transitionState
    SideEffect { if (swipe is NavigationEventTransitionState.InProgress) IosBackSwipe.follow(swipe.latestEvent.progress) }
    BackDepth.Count(enabled)
}
