package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Tap-to-toggle microphone button. First tap starts recording (prompts
 * for permission on first use); second tap stops and returns the
 * captured audio's local file path + duration via [onRecorded].
 *
 * Android actual mirrors production VoiceRecorder + VoiceRecordButton
 * (MediaRecorder → AAC-in-M4A in cacheDir, RECORD_AUDIO permission
 * launcher). Desktop actual is a no-op composable for now —
 * isVoiceMessagesSupported is false there, so DmChatScreen never
 * attempts to render this button on desktop.
 */
@Composable
expect fun VoiceRecordButton(
    enabled: Boolean,
    onRecorded: (path: String, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier,
)
