// Desktop actual for [EntityActionChips]. ML Kit Entity Extraction is
// Android-only, so desktop never renders entity chips.
package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun EntityActionChips(text: String, modifier: Modifier) {
    // No-op on desktop.
    @Suppress("UNUSED_PARAMETER") text
    @Suppress("UNUSED_PARAMETER") modifier
}
