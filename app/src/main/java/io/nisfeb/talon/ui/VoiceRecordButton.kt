package io.nisfeb.talon.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File

private const val TAG = "VoiceRecordButton"

/**
 * Tap-to-toggle microphone button. First tap starts recording (prompts
 * for permission on first use); second tap stops and returns the file
 * via `onRecorded`. Rotating the device or leaving the screen while
 * recording cancels cleanly through DisposableEffect.
 */
@Composable
fun VoiceRecordButton(
    enabled: Boolean,
    onRecorded: (File, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }

    // Cancel any in-flight recording if this button leaves composition
    // (nav away, screen rotation) — don't leak the mic.
    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Mic permission denied",
                Toast.LENGTH_SHORT,
            ).show()
        }
        // On grant, don't auto-start. User taps again.
    }

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (recording) MaterialTheme.colorScheme.errorContainer else Color.Transparent
            )
            .clickable(enabled = enabled) {
                if (recording) {
                    val result = recorder.stop()
                    recording = false
                    if (result == null) {
                        Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    val (file, durMs) = result
                    Log.i(TAG, "recorded dur=$durMs")
                    if (durMs < 300) {
                        Toast.makeText(
                            context,
                            "Too short — hold longer",
                            Toast.LENGTH_SHORT,
                        ).show()
                        file.delete()
                    } else {
                        onRecorded(file, durMs)
                    }
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@clickable
                    }
                    runCatching {
                        recorder.start()
                        recording = true
                    }.onFailure { err ->
                        Log.e(TAG, "recorder.start threw", err)
                        Toast.makeText(
                            context,
                            "Couldn't start: ${err.message ?: err::class.simpleName}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "Stop recording" else "Record voice",
            tint = if (recording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
