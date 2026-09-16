package io.nisfeb.talon.ui

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

actual fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = this


/**
 * The real thing on Android: gesture navigation reserves a strip at
 * each side for the back swipe, and a grip inside it is a grip that
 * leaves the app instead of being dragged.
 */
actual fun Modifier.keepEdgeGesture(): Modifier =
    systemGestureExclusion()
