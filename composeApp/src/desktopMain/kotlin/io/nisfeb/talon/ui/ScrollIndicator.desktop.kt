package io.nisfeb.talon.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun HorizontalScrollIndicator(state: ScrollState, modifier: Modifier) {
    HorizontalScrollbar(rememberScrollbarAdapter(state), modifier)
}
