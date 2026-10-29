package io.nisfeb.talon.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

/**
 * How a screen opens the sections drawer, or null where there isn't
 * one.
 *
 * A composition local rather than a parameter because every screen in
 * the app needs it and almost none of them need anything else from the
 * navigation — threading a drawer handle through thirty-odd signatures
 * would be a lot of plumbing for one button.
 *
 * Null on desktop, which has the rail.
 */
val LocalDrawerOpener = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Whether a swipe moves the sections drawer: when it is open, or at the top of the back stack ([BackDepth]). */
internal fun drawerSwipes(open: Boolean, backDepth: Int): Boolean = open || backDepth == 0

/**
 * Wraps the app in its sections drawer, where the platform has one.
 *
 * A pass-through on desktop: the rail is already there and a drawer
 * beside it would be a second way to reach one set of sections.
 */
@Composable
fun TalonDrawer(
    /** Handed the way to shut itself, since picking a section is the
     *  one thing anybody opens it to do. */
    drawer: @Composable (close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!isDrawerNavigation) {
        content()
        return
    }
    val state = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Back over an open drawer shuts it. Registered when it opens, so it
    // is the innermost handler and the screen under it stays put.
    if (state.isOpen) PlatformBackHandler { scope.launch { state.close() } }
    // A text selection's handles and its copy menu are not drawn with
    // the app: the handles sit in a layer of their own above it, and
    // on iOS the menu is UIKit's. The drawer is drawn with the app, so
    // it slid in underneath them and they floated over the sections.
    // Opening it lets go of focus, and a selection lets go of itself
    // when its container loses focus, which is also what leaving some
    // text to go elsewhere ought to do. On the target, not the settled
    // state, so they are gone as the drawer starts to move, whether a
    // tap or a swipe opened it.
    val focus = LocalFocusManager.current
    LaunchedEffect(state.targetValue) {
        if (state.targetValue == DrawerValue.Open) focus.clearFocus(force = true)
    }
    ModalNavigationDrawer(
        drawerState = state,
        // The menu button opens it anywhere; a swipe only at the top of
        // the back stack, where there is nowhere to go back to. Below it
        // the swipe is the way back, and the drawer opening instead was
        // what iOS users met (2026-10-28). Material gates the scrim's tap
        // on this flag too, so an open drawer keeps it on.
        gesturesEnabled = drawerSwipes(state.isOpen, BackDepth.depth),
        drawerContent = {
            ModalDrawerSheet { drawer { scope.launch { state.close() } } }
        },
    ) {
        CompositionLocalProvider(
            LocalDrawerOpener provides { scope.launch { state.open() } },
        ) {
            content()
        }
    }
}

/**
 * A screen's leading navigation button.
 *
 * The hamburger where there is a drawer, and the back arrow where
 * there is not. One place, so that adding a platform or changing the
 * rule does not mean visiting every screen in the app again.
 *
 * Where the hamburger takes the back button's place, going back is the
 * platform's own gesture: Android's system back, iOS's edge swipe.
 * Desktop keeps its arrow, having neither.
 */
@Composable
fun NavIcon(
    onBack: (() -> Unit)?,
    /** What the arrow says when it is an arrow. */
    backLabel: String = "Back",
) {
    val open = LocalDrawerOpener.current
    when {
        open != null -> io.nisfeb.talon.ui.IconButton(tip = "Open the menu", onClick = open) {
            Icon(Icons.Filled.Menu, contentDescription = "Open the menu")
        }
        onBack != null -> io.nisfeb.talon.ui.IconButton(tip = backLabel, onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
        }
    }
}
