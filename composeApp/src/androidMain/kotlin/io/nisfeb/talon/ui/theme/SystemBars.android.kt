package io.nisfeb.talon.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    // Read the configuration so a system light/dark flip recomposes
    // this too: MainActivity.onConfigurationChanged re-runs
    // enableEdgeToEdge with the system-derived contrast, and this has
    // to land after it.
    val uiMode = LocalConfiguration.current.uiMode
    if (view.isInEditMode) return
    SideEffect {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        @Suppress("UNUSED_EXPRESSION") uiMode
    }
}
