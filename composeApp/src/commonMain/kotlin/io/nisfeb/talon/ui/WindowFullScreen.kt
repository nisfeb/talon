package io.nisfeb.talon.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The host window's full-screen switch, for a meeting-style video
 * view that fills the display. Desktop binds it to the window's
 * placement; phones are already full screen and use [NoopWindowFullScreen].
 * Gate UI on [isWindowFullScreenSupported].
 */
interface WindowFullScreen {
    /** Snapshot-backed on desktop, so a composable reading it recomposes. */
    fun isFullScreen(): Boolean
    fun set(full: Boolean)
}

object NoopWindowFullScreen : WindowFullScreen {
    override fun isFullScreen() = false
    override fun set(full: Boolean) = Unit
}

val LocalWindowFullScreen = staticCompositionLocalOf<WindowFullScreen> { NoopWindowFullScreen }
