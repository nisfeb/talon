package io.nisfeb.talon

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.nisfeb.talon.ai.CallRecordingPublisher
import io.nisfeb.talon.ai.DictationOutcome
import io.nisfeb.talon.ai.DictationRun
import io.nisfeb.talon.ai.EndOfSpeech
import io.nisfeb.talon.ai.acquireTalonWakeLock
import io.nisfeb.talon.ai.loopsCanRun
import io.nisfeb.talon.call.WavFile
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Quick dictation: speak, and Talon acts on it with nothing else
 * pressed. The home-screen widget, the Quick Settings tile and the assist
 * gesture (Talon as the digital assistant) all open
 * [QuickDictationActivity], which starts [DictationService] while it is
 * on screen, since only a visible app may open the microphone. The
 * service records until the owner stops talking ([EndOfSpeech]), has the
 * recording transcribed with the transcription key from AI settings, runs
 * the agent on the words with no confirmation ([io.nisfeb.talon.ai.Loops.dictate])
 * and posts what it did. A failure keeps what it had, the audio or the
 * words, behind a Retry.
 *
 * Android-only: no desktop analog. The widget, the tile and the assistant
 * role are Android system surfaces, and the work rides a microphone
 * foreground service. Gated in shared UI by isQuickDictationSupported.
 * See docs/superpowers/specs/2026-10-15-dictate-to-orrery-design.md.
 */
object QuickDictation {
    /** How loud the voice is while listening, 0 to 1; null when not listening. */
    private val _level = MutableStateFlow<Float?>(null)
    val level: StateFlow<Float?> = _level.asStateFlow()

    internal enum class Ending { Done, Cancel }

    /** Set by the owner (Done, Cancel or back), read by the capture loop. */
    @Volatile internal var ending: Ending? = null

    internal fun setLevel(v: Float?) { _level.value = v }

    /** Start listening. Only from something on screen: a microphone service cannot start from the background. */
    fun listen(ctx: Context) {
        ending = null
        ContextCompat.startForegroundService(ctx, Intent(ctx, DictationService::class.java).setAction(DictationService.ACTION_LISTEN))
    }

    /** The owner is done talking: act on what was said. */
    fun done() { ending = Ending.Done }

    /** Throw the recording away. */
    fun cancel() { ending = Ending.Cancel }
}

/** The microphone, the transcription and the run, in the foreground for as long as they take. */
class DictationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Changed on the main thread only. [ongoing] also reads listening
    // from the work's thread.
    private var running = 0
    @Volatile private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> {
                val already = listening
                listening = true
                // In the foreground first, even when this start does
                // nothing: a service started with startForegroundService
                // that never calls startForeground takes the app down.
                if (!foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "Listening…")) {
                    listening = already
                    return stopIfIdle()
                }
                if (!already) work { listenAndAct() }
            }
            ACTION_STOP -> QuickDictation.done()
            ACTION_RETRY -> {
                val kept = intent.getStringExtra(EXTRA_KEPT)?.let { File(keptDir(), it) }
                // A dataSync start, not a microphone one: a notification's
                // Retry comes from the background, where the microphone type
                // is refused. While listening the microphone type stands.
                val type = if (listening) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (!foreground(type, "Working on your dictation…")) return stopIfIdle()
                if (kept != null) {
                    NotificationManagerCompat.from(this).cancel(kept.name, RESULT_ID)
                    work { retry(kept) }
                }
            }
        }
        return stopIfIdle()
    }

    private fun foreground(type: Int, text: String): Boolean = try {
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing(text), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type else 0)
        true
    } catch (e: RuntimeException) {
        // The microphone taken away, or a start Android does not allow from here.
        Log.w(TAG, "dictation could not start: ${e.message}")
        false
    }

    private fun work(block: suspend () -> Unit) {
        running++
        scope.launch {
            val wake = acquireTalonWakeLock("dictation", WAKE_LOCK_MS)
            try {
                block()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "dictation failed", t)
                tell("Dictation failed", t.message ?: "Something went wrong.")
            } finally {
                runCatching { wake.release() }
                withContext(Dispatchers.Main) {
                    running--
                    stopIfIdle()
                }
            }
        }
    }

    private fun stopIfIdle(): Int {
        if (running == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        QuickDictation.setLevel(null)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun listenAndAct() {
        // Read now, not when the run starts: the owner may unlock while it works.
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        val heard = try {
            record()
        } finally {
            QuickDictation.setLevel(null)
            withContext(Dispatchers.Main) { listening = false }
        }
        when {
            heard == null -> Unit // cancelled
            !heard.second -> tell("Nothing heard", "Talon did not hear anything to act on.")
            else -> {
                update("Working on your dictation…")
                val wav = WavFile.encode(heard.first, DictationRun.RATE)
                // Kept before it is sent, so nothing said is lost to a crash or a dead network.
                val kept = File(keptDir(), "${System.currentTimeMillis()}.wav").apply { writeBytes(wav) }
                report(kept, pipeline(locked).fromAudio(wav))
            }
        }
    }

    private suspend fun retry(kept: File) {
        if (!kept.exists()) return tell("Dictation gone", "That dictation is no longer kept on this phone.")
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        val run = pipeline(locked)
        report(kept, if (kept.extension == "txt") run.fromText(kept.readText()) else run.fromAudio(kept.readBytes()))
    }

    /** The recording and whether there was speech in it, or null when cancelled. */
    private fun record(): Pair<ByteArray, Boolean>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Talon needs the microphone to hear a dictation.")
        }
        val frame = ByteArray(DictationRun.RATE / 10 * 2) // 100 ms
        val min = AudioRecord.getMinBufferSize(DictationRun.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, DictationRun.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame.size * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("The microphone did not open. Another app may be using it.")
        }
        val gate = EndOfSpeech(DictationRun.RATE)
        val out = ByteArrayOutputStream()
        try {
            rec.startRecording()
            while (true) {
                when (QuickDictation.ending) {
                    QuickDictation.Ending.Cancel -> return null
                    // Pressed Done: sent even if the gate heard nothing, since the owner says they spoke.
                    QuickDictation.Ending.Done -> return out.toByteArray() to (out.size() > 0)
                    null -> Unit
                }
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) error("The microphone stopped ($n).")
                out.write(frame, 0, n)
                val more = gate.offer(frame, n)
                QuickDictation.setLevel(gate.level)
                if (!more) return out.toByteArray() to gate.heard
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun pipeline(locked: Boolean): DictationRun {
        val app = application as TalonApplication
        val cfg = app.aiSettings.state.value
        val stt = CallRecordingPublisher.sttFrom(cfg)
        val act: suspend (String) -> String = { words ->
            // A timeout here and not a cancellation: one would unwind
            // past the outcome and the words would go unkept.
            withTimeoutOrNull(RUN_BUDGET_MS) { app.loops.dictate(words, locked) }
                ?: error("It took longer than ${RUN_BUDGET_MS / 60_000} minutes")
        }
        return DictationRun(
            transcribe = stt?.let { DictationRun.whisper(app.ktorHttp, it) },
            act = act.takeIf { app.sessionStore.activeShip() != null && cfg.loopsCanRun() },
        )
    }

    /** Say how it went, and keep [kept] (the audio, or the words) only where it can be tried again. */
    private fun report(kept: File, outcome: DictationOutcome) {
        when (outcome) {
            is DictationOutcome.Done -> {
                kept.delete()
                tell("Dictation", outcome.summary, "${outcome.summary}\n\nYou said: ${outcome.transcript}")
            }
            DictationOutcome.NothingHeard -> {
                kept.delete()
                tell("Nothing heard", "Talon could not make out any words.")
            }
            is DictationOutcome.NeedsSetup -> tell("Dictation not sent", outcome.what + " It is kept to try again.", retry = kept)
            is DictationOutcome.TranscribeFailed -> tell(
                "Dictation not sent",
                "It could not be turned into text: ${outcome.reason}. It is kept to try again.",
                retry = kept,
            )
            is DictationOutcome.RunFailed -> {
                // The words are what is left to try again, not the audio: no second transcription.
                val words = File(keptDir(), kept.nameWithoutExtension + ".txt").apply { writeText(outcome.transcript) }
                if (words != kept) kept.delete()
                tell(
                    "Dictation not done",
                    "${outcome.reason}. It may have done part of it before it stopped.",
                    "You said: ${outcome.transcript}\n\n${outcome.reason}. It may have done part of it before it stopped, so check before trying again.",
                    retry = words,
                )
            }
        }
    }

    private fun tell(title: String, text: String, long: String = text, retry: File? = null) {
        Notifications.ensureChannel(this)
        val tag = retry?.name ?: "dictation-${System.currentTimeMillis()}"
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_stat_talon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(long))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (retry != null) {
            n.addAction(
                0, "Try again",
                PendingIntent.getForegroundService(
                    this, retry.name.hashCode(),
                    Intent(this, DictationService::class.java).setAction(ACTION_RETRY).putExtra(EXTRA_KEPT, retry.name),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        runCatching { NotificationManagerCompat.from(this).notify(tag, RESULT_ID, n.build()) }
            .onFailure { Log.w(TAG, "no permission to post the dictation's result") }
    }

    private fun ongoing(text: String): Notification {
        Notifications.ensureChannel(this)
        val stop = PendingIntent.getService(
            this, ONGOING_ID, Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_DICTATING)
            .setSmallIcon(R.drawable.ic_stat_talon)
            .setContentTitle("Dictation")
            .setContentText(text)
            .setContentIntent(openApp())
            .apply { if (listening) addAction(0, "Done", stop) }
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun update(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(ONGOING_ID, ongoing(text)) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, ONGOING_ID,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ponytail: kept dictations live until tried again; a phone that never
    // gets them through keeps a few small files. Sweep old ones if they pile up.
    private fun keptDir(): File = File(filesDir, "dictation").apply { mkdirs() }

    companion object {
        const val ACTION_LISTEN = "io.nisfeb.talon.action.DICTATION_LISTEN"
        const val ACTION_STOP = "io.nisfeb.talon.action.DICTATION_STOP"
        const val ACTION_RETRY = "io.nisfeb.talon.action.DICTATION_RETRY"
        private const val EXTRA_KEPT = "kept"
        private const val ONGOING_ID = 7705
        private const val RESULT_ID = 7706
        private const val TAG = "Dictation"
        // A dictation's agent run: several model calls, each allowed 120 s by AgentClient.
        private const val RUN_BUDGET_MS = 4L * 60 * 1000
        // The run, the transcription (up to 180 s) and two minutes of talking.
        private const val WAKE_LOCK_MS = 10L * 60 * 1000
    }
}

/**
 * The Quick Settings tile: one swipe and a tap, over the lock screen too.
 * SystemUI opens [QuickDictationActivity] there without asking to unlock
 * because the manifest declares it showWhenLocked.
 */
class DictationTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val open = Intent(this, QuickDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }
}

/** The home-screen button: a tap starts a dictation. */
class DictationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_dictate).apply {
            setOnClickPendingIntent(
                R.id.widget_dictate,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, QuickDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        manager.updateAppWidget(ids, views)
    }
}
