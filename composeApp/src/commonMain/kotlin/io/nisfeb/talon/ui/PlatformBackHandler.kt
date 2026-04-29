package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * Cross-platform back-button handler. Android delegates to
 * androidx.activity.compose.BackHandler so the system Back button
 * navigates up through the in-app state machine instead of killing
 * the activity. Desktop is a no-op — Compose Desktop has no
 * equivalent affordance (window-close is wired separately).
 *
 * Pattern matches production app/.../ui/TalonApp.kt's BackHandler
 * usage: register one per non-root state with `enabled` gating.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
