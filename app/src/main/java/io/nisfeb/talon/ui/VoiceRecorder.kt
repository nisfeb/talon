package io.nisfeb.talon.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

private const val TAG = "VoiceRecorder"

/**
 * Thin wrapper around MediaRecorder that writes AAC-in-M4A audio into
 * the app cache. Stop returns the finished file or null if nothing was
 * captured (zero-length press, early cancel, recorder error).
 */
class VoiceRecorder(private val context: Context) {

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
        Log.i(TAG, "start ok → ${file.absolutePath}")
        recorder = rec
        outputFile = file
        startedAt = System.currentTimeMillis()
    }

    /** Stop recording; returns (file, durationMs) on success. */
    fun stop(): Pair<File, Long>? {
        val rec = recorder ?: return null
        val file = outputFile ?: return null
        val elapsed = System.currentTimeMillis() - startedAt
        return try {
            rec.stop()
            rec.release()
            val ok = file.exists() && file.length() > 0
            Log.i(TAG, "stop ok=$ok bytes=${file.length()} dur=${elapsed}ms")
            if (ok) file to elapsed else null
        } catch (t: Throwable) {
            // stop() throws RuntimeException if we never got a valid
            // recording — user tapped too fast, mic busy, etc.
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
