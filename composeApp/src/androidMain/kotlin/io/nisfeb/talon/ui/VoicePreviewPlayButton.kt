package io.nisfeb.talon.ui

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Compact play/pause control for the in-progress voice-recording
 * preview row. ExoPlayer-backed, restart-from-zero on replay, and
 * disabled during upload — mirrors the pre-Stage-F production button.
 *
 * Separate from [InlineAudioPlayer] (the full scrubber + duration row
 * used for received voice messages in the chat body) because the
 * preview row sits inline next to Cancel + Send buttons; a full
 * scrubber here would dominate the row.
 */
@Composable
fun VoicePreviewPlayButton(path: String, enabled: Boolean) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var isPlaying by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    IconButton(
        onClick = {
            if (player.isPlaying) {
                player.pause()
            } else {
                // Restart from 0 if the previous play finished — the
                // user expects the toggle to replay rather than do
                // nothing once the recording is over.
                if (player.currentPosition >= player.duration.coerceAtLeast(1L)) {
                    player.seekTo(0L)
                }
                player.play()
            }
        },
        enabled = enabled,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = Modifier.size(22.dp),
        )
    }
}
