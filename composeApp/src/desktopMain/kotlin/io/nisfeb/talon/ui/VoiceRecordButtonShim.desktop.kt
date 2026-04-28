package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VoiceRecordButton(
    enabled: Boolean,
    onRecorded: (path: String, durationMs: Long) -> Unit,
    modifier: Modifier,
) {
    // Capability-gated off on desktop; this stub exists purely to
    // satisfy the expect/actual contract.
}
