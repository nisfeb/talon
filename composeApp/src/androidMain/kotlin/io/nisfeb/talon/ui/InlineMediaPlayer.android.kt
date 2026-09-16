package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/** ExoPlayer-backed, the same players TalonApp binds. */
actual fun platformInlineMediaPlayer(): (@Composable (url: String, kind: MediaKind) -> Unit)? = { url, kind ->
    when (kind) {
        MediaKind.AUDIO -> InlineAudioPlayer(url = url)
        MediaKind.VIDEO -> InlineVideoPlayer(url = url)
    }
}
