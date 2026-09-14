package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nisfeb.talon.login.TalonLoginUri
import kotlinx.coroutines.flow.Flow

// Desktop records no voice messages and scans no QR (the flags are
// false), so these are never composed; the media row falls back to a
// link that opens in the browser.

@Composable
actual fun VoiceRecordButton(enabled: Boolean, onRecorded: (path: String, durationMs: Long) -> Unit, modifier: Modifier, externalTrigger: Flow<Unit>?) = Unit

@Composable
actual fun VoicePreviewPlayButton(path: String, enabled: Boolean) = Unit

actual fun platformInlineMediaPlayer(): (@Composable (url: String, kind: MediaKind) -> Unit)? = null

@Composable
actual fun rememberQrLoginScanLauncher(onResult: (TalonLoginUri.Payload?) -> Unit): (() -> Unit)? = null
