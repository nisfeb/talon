package io.nisfeb.talon.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Touch scrolls by dragging; nothing to draw. */
@Composable
actual fun HorizontalScrollIndicator(state: ScrollState, modifier: Modifier) = Unit
