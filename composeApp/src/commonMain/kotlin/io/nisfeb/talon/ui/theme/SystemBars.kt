package io.nisfeb.talon.ui.theme

import androidx.compose.runtime.Composable

/**
 * Keeps the OS status/navigation bar icons legible against the app's
 * own theme. Android derives icon contrast from the *system* theme at
 * activity start, so a Light choice inside a dark-mode phone left white
 * icons on a white bar. Desktop and iOS: no-op (desktop has no system
 * bar; iOS follows the view controller and is unaffected today).
 */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean)
