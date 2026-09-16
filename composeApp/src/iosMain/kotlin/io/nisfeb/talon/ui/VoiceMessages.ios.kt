package io.nisfeb.talon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.notify.IosVoipBridge
import io.nisfeb.talon.util.nowMs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Voice messages on iOS: AVAudioRecorder writes the same AAC m4a
 * Android sends, AVAudioPlayer plays the preview from disk, and an
 * AVPlayer plays a received message from its link.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosVoiceRecorder {
    private var recorder: AVAudioRecorder? = null
    private var path: String? = null
    private var startedAt = 0L
    /** True when start() activated the shared session, so stop()/cancel()
     *  must hand it back. False when a call owns it (see below). */
    private var ownsSession = false

    fun start(): Boolean {
        cancel()
        val file = NSTemporaryDirectory() + "voice-${nowMs()}.m4a"
        val session = AVAudioSession.sharedInstance()
        // During a call TalonRtc owns the shared session — reconfiguring
        // it here stomps the call's audio, and deactivating it on stop
        // would drop the call outright. It is already playAndRecord then,
        // so the recorder can just use it.
        if (!IosVoipBridge.callLive.value) {
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker, null)
            session.setActive(true, null)
            ownsSession = true
        }
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
            AVSampleRateKey to 44_100.0,
            AVNumberOfChannelsKey to 1,
            AVEncoderBitRateKey to 96_000,
        )
        val rec = AVAudioRecorder(NSURL.fileURLWithPath(file), settings, null)
        if (!rec.prepareToRecord() || !rec.record()) {
            // Don't leave the empty file or the activated session behind.
            NSFileManager.defaultManager.removeItemAtPath(file, null)
            releaseSession()
            return false
        }
        recorder = rec
        path = file
        startedAt = nowMs()
        return true
    }

    /** The file and its length, or null when nothing was recording. */
    fun stop(): Pair<String, Long>? {
        val rec = recorder ?: return null
        rec.stop()
        val out = path ?: return null
        val ms = nowMs() - startedAt
        recorder = null; path = null; startedAt = 0
        releaseSession()
        return out to ms
    }

    fun cancel() {
        recorder?.stop()
        path?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        recorder = null; path = null; startedAt = 0
        releaseSession()
    }

    private fun releaseSession() {
        if (!ownsSession) return
        ownsSession = false
        // A call that came up while we were recording owns the session
        // now; deactivating it here would kill the call's audio.
        if (IosVoipBridge.callLive.value) return
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
    }
}

/** The iOS Toast: a one-message alert. Always on the main queue. */
@OptIn(ExperimentalForeignApi::class)
private fun showVoiceAlert(message: String) {
    dispatch_async(dispatch_get_main_queue()) {
        val scenes = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
        val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?: scenes.firstOrNull()
        val windows = scene?.windows?.filterIsInstance<UIWindow>().orEmpty()
        var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        val alert = UIAlertController.alertControllerWithTitle(null, message, UIAlertControllerStyleAlert)
        alert.addAction(UIAlertAction.actionWithTitle("OK", UIAlertActionStyleDefault, handler = null))
        top?.presentViewController(alert, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VoiceRecordButton(
    enabled: Boolean,
    onRecorded: (path: String, durationMs: Long) -> Unit,
    modifier: Modifier,
    externalTrigger: Flow<Unit>?,
) {
    val recorder = remember { IosVoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    val toggle: () -> Unit = {
        if (recording) {
            recording = false
            val got = recorder.stop()
            if (got != null) {
                val (file, ms) = got
                // Too short to be a message; the tap was a slip.
                if (ms < 300) NSFileManager.defaultManager.removeItemAtPath(file, null) else onRecorded(file, ms)
            }
        } else if (enabled) {
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                dispatch_async(dispatch_get_main_queue()) {
                    when {
                        // Android says this with a Toast; silence here
                        // reads as a dead button.
                        !granted -> showVoiceAlert(
                            "Talon needs microphone access to record a voice message. You can turn it on in Settings.",
                        )
                        recorder.start() -> recording = true
                        else -> showVoiceAlert("Couldn't start the recorder.")
                    }
                }
            }
        }
    }
    // The effect keys on the flow, so without this it would keep the
    // first composition's toggle — stale enabled/onRecorded and all.
    val currentToggle = rememberUpdatedState(toggle)
    if (externalTrigger != null) LaunchedEffect(externalTrigger) { externalTrigger.collect { currentToggle.value() } }
    Box(
        modifier = modifier.size(36.dp).clip(CircleShape)
            .background(if (recording) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
            .clickable(enabled = enabled) { toggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "Stop recording" else "Record voice",
            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VoicePreviewPlayButton(path: String, enabled: Boolean) {
    // init(contentsOfURL:) returns nil for a missing or unreadable
    // file, and K/N turns that nil into a null-to-non-null crash —
    // check the file is there before constructing.
    val player = remember(path) {
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            AVAudioPlayer(NSURL.fileURLWithPath(path), null)
        } else null
    }
    var playing by remember(path) { mutableStateOf(false) }
    DisposableEffect(path) { onDispose { player?.stop() } }
    // AVAudioPlayer has a delegate for "finished"; a poll is less code
    // and the preview is seconds long.
    LaunchedEffect(playing) { while (playing) { delay(250); if (player?.playing != true) playing = false } }
    IconButton(enabled = enabled && player != null, onClick = {
        val p = player ?: return@IconButton
        if (playing) { p.pause(); playing = false } else {
            runCatching { AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null) }
            if (p.currentTime >= p.duration) p.currentTime = 0.0
            p.play(); playing = true
        }
    }) {
        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
    }
}

/** Audio plays in the row; video stays a link, as on desktop. */
actual fun platformInlineMediaPlayer(): (@Composable (url: String, kind: MediaKind) -> Unit)? = { url, kind ->
    when (kind) {
        MediaKind.AUDIO -> IosInlineAudioPlayer(url)
        MediaKind.VIDEO -> FallbackInlineMediaRow(url, kind)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun IosInlineAudioPlayer(url: String) {
    val player = remember(url) { AVPlayer(uRL = NSURL.URLWithString(url) ?: NSURL(string = "about:blank")) }
    var playing by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableStateOf(0.0) }
    var length by remember(url) { mutableStateOf(0.0) }
    DisposableEffect(url) { onDispose { player.pause() } }
    LaunchedEffect(playing) {
        while (playing) {
            delay(250)
            position = CMTimeGetSeconds(player.currentTime())
            player.currentItem?.let { item -> CMTimeGetSeconds(item.duration).takeIf { it.isFinite() && it > 0 }?.let { length = it } }
            if (player.rate == 0f && length > 0 && position >= length - 0.3) { playing = false; player.seekToTime(CMTimeMake(0, 1)) ; position = 0.0 }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (playing) { player.pause(); playing = false } else {
                runCatching { AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null) }
                player.play(); playing = true
            }
        }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play") }
        fun clock(s: Double) = "${(s / 60).toInt()}:${(s % 60).toInt().toString().padStart(2, '0')}"
        Text(
            if (length > 0) "${clock(position)} / ${clock(length)}" else if (playing) clock(position) else "Voice message",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
