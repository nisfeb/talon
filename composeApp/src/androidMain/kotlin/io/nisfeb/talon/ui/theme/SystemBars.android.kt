package io.nisfeb.talon.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean, background: androidx.compose.ui.graphics.Color) {
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
        // The window's own background shows wherever Compose has not
        // painted: inset bands, keyboard and transition gaps. It comes
        // from the platform theme, which follows the system, so a
        // forced light or dark preference and a live flip both need it
        // repainted from the app's theme.
        val argb = background.toArgb()
        if ((window.decorView.background as? android.graphics.drawable.ColorDrawable)?.color != argb) {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(argb))
        }
        @Suppress("UNUSED_EXPRESSION") uiMode
    }
}
