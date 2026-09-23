package io.nisfeb.talon.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A visible scrollbar for something that scrolls sideways. With a mouse a
 * sideways scroll is shift and the wheel, which nobody finds, so a pane
 * that overflowed read as cut off. Desktop draws one; a touch screen
 * scrolls by dragging and draws nothing.
 */
@Composable
expect fun HorizontalScrollIndicator(state: ScrollState, modifier: Modifier = Modifier)
