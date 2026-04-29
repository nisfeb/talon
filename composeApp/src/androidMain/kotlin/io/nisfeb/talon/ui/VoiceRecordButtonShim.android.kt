// Android actual for [VoiceRecordButton]. Inlines the production
// VoiceRecorder + VoiceRecordButton (app/src/main/java/.../ui/) so
// composeApp doesn't need to depend on app/. Diverges from production
// by returning a file path String rather than a java.io.File so the
// commonMain expect can stay platform-agnostic.
package io.nisfeb.talon.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
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

private class AndroidVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L

    fun start() {
        stopInternal(discard = true)
        val file = File(
            context.cacheDir,
            "voice-${System.currentTimeMillis()}.m4a",
        )
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioEncodingBitRate(96_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            runCatching { rec.release() }
            file.delete()
            throw t
        }
        recorder = rec
        outputFile = file
        startedAt = System.currentTimeMillis()
    }

    fun stop(): Pair<File, Long>? {
        val rec = recorder ?: return null
        val file = outputFile ?: return null
        val elapsed = System.currentTimeMillis() - startedAt
        return try {
            rec.stop()
            rec.release()
            val ok = file.exists() && file.length() > 0
            if (ok) file to elapsed else null
        } catch (t: Throwable) {
            Log.w(TAG, "stop failed", t)
            runCatching { rec.release() }
            file.delete()
            null
        } finally {
            recorder = null
            outputFile = null
            startedAt = 0L
        }
    }

    fun cancel() { stopInternal(discard = true) }

    private fun stopInternal(discard: Boolean) {
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        if (discard) outputFile?.delete()
        outputFile = null
        startedAt = 0L
    }
}

@Composable
fun VoiceRecordButton(
    enabled: Boolean,
    onRecorded: (path: String, durationMs: Long) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val recorder = remember { AndroidVoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Mic permission denied", Toast.LENGTH_SHORT).show()
        }
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
                    if (durMs < 300) {
                        Toast.makeText(
                            context, "Too short — hold longer", Toast.LENGTH_SHORT,
                        ).show()
                        file.delete()
                    } else {
                        onRecorded(file.absolutePath, durMs)
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
