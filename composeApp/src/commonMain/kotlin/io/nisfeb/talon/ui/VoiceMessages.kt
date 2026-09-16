package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nisfeb.talon.login.TalonLoginUri
import kotlinx.coroutines.flow.Flow

/**
 * The platform pieces of voice messages and QR login, composed by the
 * shells where the matching capability flag is true. Each leaf keeps
 * its own recorder and players; a leaf without them (desktop) has
 * empty actuals that the flags keep off screen.
 */

/** A mic button: tap to record, tap again to stop; [onRecorded] gets
 *  the file and its length. [externalTrigger] toggles it the way a tap
 *  does, for the /mic slash command. */
@Composable
expect fun VoiceRecordButton(
    enabled: Boolean,
    onRecorded: (path: String, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    externalTrigger: Flow<Unit>? = null,
)

/** Play/pause for the recording being previewed before it is sent. */
@Composable
expect fun VoicePreviewPlayButton(path: String, enabled: Boolean)

/** The inline player for audio and video links in chat rows, or null
 *  where there is none and the row falls back to a link. */
expect fun platformInlineMediaPlayer(): (@Composable (url: String, kind: MediaKind) -> Unit)?

/** A trigger that opens the camera on a QR code, or null where there is
 *  no scanner. [onResult] gets the code's text, or null when nothing was
 *  read; [prompt] is shown over the camera. */
@Composable
expect fun rememberQrScanLauncher(prompt: String, onResult: (String?) -> Unit): (() -> Unit)?

/** The scanner on a login QR; the payload is null when nothing was read. */
@Composable
fun rememberQrLoginScanLauncher(onResult: (TalonLoginUri.Payload?) -> Unit): (() -> Unit)? =
    rememberQrScanLauncher("Point the camera at a Talon login QR") { raw -> onResult(raw?.let(TalonLoginUri::decode)) }
