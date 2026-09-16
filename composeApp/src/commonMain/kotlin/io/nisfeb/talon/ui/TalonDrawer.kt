package io.nisfeb.talon.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
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
    ModalNavigationDrawer(
        drawerState = state,
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
        open != null -> IconButton(onClick = open) {
            Icon(Icons.Filled.Menu, contentDescription = "Open the menu")
        }
        onBack != null -> IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
        }
    }
}
