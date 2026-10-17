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
import io.nisfeb.talon.ai.Briefing
import io.nisfeb.talon.ai.DictationFlow
import io.nisfeb.talon.ai.DictationOutcome
import io.nisfeb.talon.ai.DictationRoute
import io.nisfeb.talon.ai.DictationRun
import io.nisfeb.talon.ai.DictationSpeech
import io.nisfeb.talon.ai.EndOfSpeech
import io.nisfeb.talon.ai.acquireTalonWakeLock
import io.nisfeb.talon.ai.loopsCanRun
import io.nisfeb.talon.ai.orreryOn
import io.nisfeb.talon.call.WavFile
import io.nisfeb.talon.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.toLocalDateTime
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Quick dictation: speak, and Talon acts on it. The home-screen widget,
 * the Quick Settings tile and the assist gesture (Talon as the digital
 * assistant) all open [QuickDictationActivity], which starts
 * [DictationService] while it is on screen, since only a visible app may
 * open the microphone. The service records until the owner stops talking
 * ([EndOfSpeech]) and has the recording transcribed with the transcription
 * key from AI settings. With Orrery on, a question is answered and
 * everything else is orrery's ([DictationFlow]): what orrery's policy
 * leaves waiting is put to the owner on the same card, Send or Don't send,
 * by tap or by saying it, with the fingerprint first on a locked phone
 * for anything that reaches another person. With Orrery off the agent
 * acts on the words itself ([io.nisfeb.talon.ai.Loops.DictationPrep.dictate]).
 * A failure keeps what it had, the audio or the words, behind a Retry.
 *
 * Android-only: no desktop analog. The widget, the tile and the assistant
 * role are Android system surfaces, and the work rides a microphone
 * foreground service. Gated in shared UI by isQuickDictationSupported.
 * See docs/superpowers/specs/2026-10-15-dictate-to-orrery-design.md.
 */
object QuickDictation {
    /** What the card shows. */
    sealed interface Stage {
        data object Idle : Stage

        /** [level] is how loud the voice is, 0 to 1. */
        data class Listening(val level: Float) : Stage

        /** What it is doing, in words: "Asking orrery…". */
        data class Working(val label: String = "Working on it…") : Stage

        /**
         * Orrery's proposals waiting for the owner: its [reply], what each
         * does ([proposals]), whether one is a message ([sending]), whether the
         * card is listening for "send" or "cancel" ([hearing]), whether a
         * locked phone needs the fingerprint first ([unlock]), and whether
         * the owner already said send and only the fingerprint is left ([unlockNow]).
         */
        data class Approve(
            val reply: String,
            val proposals: List<io.nisfeb.talon.ai.CardProposal>,
            /** The same, one line each, for the shade. */
            val lines: List<String>,
            val sending: Boolean,
            val hearing: Boolean,
            val unlock: Boolean,
            val unlockNow: Boolean = false,
        ) : Stage

        /**
         * How it ended: the [line], the events and tasks the answer read
         * ([card]), and whether a follow-up can go on from it ([followable]).
         */
        data class Said(
            val line: String,
            val card: io.nisfeb.talon.ai.DictationCard? = null,
            val followable: Boolean = false,
            /** One of Archie's turns: the card stays up and listens again by itself. */
            val talking: Boolean = false,
        ) : Stage
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    internal fun show(s: Stage) { _stage.value = s }

    /** Whether the card is in front, so the shade is asked only when it is not. */
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()
    internal fun shown(v: Boolean) { _shown.value = v }

    internal enum class Ending { Done, Cancel }

    /** Set by the owner (Done, Cancel or back), read by the capture loop. */
    @Volatile internal var ending: Ending? = null

    /** The exchange a follow-up goes on from, while its card is up. */
    internal data class Thread(val earlier: List<DictationFlow.Earlier>, val asked: Boolean)

    @Volatile internal var thread: Thread? = null

    /** The owner's word on what waits, for the run that asked. */
    @Volatile private var word: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    internal fun asking(): kotlinx.coroutines.CompletableDeferred<Boolean> =
        kotlinx.coroutines.CompletableDeferred<Boolean>().also { word = it }

    /** Whether a run is waiting for the owner's word. */
    internal fun waiting(): Boolean = word?.isActive == true

    /** Start listening. Only from something on screen: a microphone service cannot start from the background. */
    fun listen(ctx: Context) {
        thread = null
        history = null
        hear(ctx)
    }

    /** Whether the card is Archie's conversation, for its header. */
    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking.asStateFlow()
    internal fun talking(v: Boolean) { _talking.value = v }

    /** Set when the owner ends Archie's conversation from the card. */
    @Volatile internal var hangUp = false

    /** Start a conversation with Archie: Home's button. Only from something on screen, as [listen]. */
    fun converse(ctx: Context) {
        thread = null
        history = null
        hush()
        ending = null
        hangUp = false
        _talking.value = true
        _stage.value = Stage.Working("Getting your day…")
        ContextCompat.startForegroundService(ctx, Intent(ctx, DictationService::class.java).setAction(DictationService.ACTION_CONVERSE))
    }

    /** End Archie's conversation: stop talking and listening now. */
    fun hangUp() {
        hangUp = true
        ending = Ending.Cancel
        hush()
    }

    /** The Assistant history conversation this card's exchanges go into. */
    @Volatile internal var history: io.nisfeb.talon.data.AssistantConversationEntity? = null

    /** Dictate again in the same context: the card's Follow up. */
    fun followUp(ctx: Context) = hear(ctx)

    private fun hear(ctx: Context) {
        hush()
        ending = null
        _stage.value = Stage.Listening(0f)
        ContextCompat.startForegroundService(ctx, Intent(ctx, DictationService::class.java).setAction(DictationService.ACTION_LISTEN))
    }

    /** The owner is done talking: act on what was said. */
    fun done() { ending = Ending.Done }

    /** Throw the recording away. */
    fun cancel() { ending = Ending.Cancel }

    /** The owner's word on what waits: send it, or drop it. On a locked phone, only after the fingerprint. */
    fun decide(approve: Boolean) {
        hush()
        word?.complete(approve)
    }

    /** The card went away having shown how it ended; a follow-up goes with it. */
    fun close() {
        if (_talking.value) hangUp()
        hush()
        if (_stage.value is Stage.Said) _stage.value = Stage.Idle
        thread = null
    }

    /** Stops the voice mid-sentence: any tap on the card means the owner has moved on. */
    @Volatile internal var onHush: (() -> Unit)? = null
    fun hush() { onHush?.invoke() }

    private const val PREFS = "talon_dictation"
    private const val SPEAK = "speak"

    /** Whether the card speaks its answers (Settings, on by default). */
    fun speaks(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SPEAK, true)
    fun setSpeaks(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SPEAK, on).apply()
}

/** The microphone, the transcription and the run, in the foreground for as long as they take. */
private val ARCHIE get() = io.nisfeb.talon.ui.icons.ASSISTANT_NAME

class DictationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Changed on the main thread only. [ongoing] also reads listening
    // from the work's thread.
    private var running = 0
    @Volatile private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** The card's voice, made when a dictation starts so it is ready by the answer. */
    private var voice: Voice? = null

    /** A tap stopped the voice: a reading in full stops too, before its next paragraph. */
    @Volatile private var hushed = false

    override fun onCreate() {
        super.onCreate()
        QuickDictation.onHush = {
            hushed = true
            voice?.hush()
        }
    }

    /** Say [text], where the owner wants answers spoken, and return once it is said. */
    private suspend fun say(text: String) {
        if (text.isBlank() || !QuickDictation.speaks(this)) return
        val v = voice ?: Voice(this).also { voice = it }
        v.say(text)
    }

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
                    // The card waits on this run; there will be none.
                    if (!already) QuickDictation.show(QuickDictation.Stage.Said("Talon could not start listening."))
                    return stopIfIdle()
                }
                // Waking the voice now: its engine takes a moment to start.
                if (QuickDictation.speaks(this) && voice == null) voice = Voice(this)
                if (!already) work { listenAndAct() }
            }
            ACTION_CONVERSE -> {
                val already = listening
                listening = true
                if (!foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, "Talking with $ARCHIE…")) {
                    listening = already
                    QuickDictation.talking(false)
                    if (!already) QuickDictation.show(QuickDictation.Stage.Said("Talon could not start listening."))
                    return stopIfIdle()
                }
                if (QuickDictation.speaks(this) && voice == null) voice = Voice(this)
                if (!already) work(TALK_LOCK_MS) { converse() }
            }
            ACTION_STOP -> QuickDictation.done()
            ACTION_DECIDE -> {
                val approve = intent.getBooleanExtra(EXTRA_APPROVE, false)
                val ids = intent.getStringArrayExtra(EXTRA_IDS).orEmpty().toList()
                val type = if (listening) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (!foreground(type, "Working on your dictation…")) return stopIfIdle()
                NotificationManagerCompat.from(this).cancel(ASK_ID)
                // The run that asked is still waiting: its word. Otherwise
                // the run has gone on and the answer goes to orrery here.
                if (QuickDictation.waiting()) QuickDictation.decide(approve) else work { decideLate(ids, approve) }
            }
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

    private fun work(lockMs: Long = WAKE_LOCK_MS, block: suspend () -> Unit) {
        running++
        scope.launch {
            val wake = acquireTalonWakeLock("dictation", lockMs)
            try {
                block()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "dictation failed", t)
                tell("Dictation failed", DictationRun.why(t) + ".", details = io.nisfeb.talon.util.errorDetailsOf(t))
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
        QuickDictation.onHush = null
        voice?.shutdown()
        voice = null
        if (QuickDictation.stage.value is QuickDictation.Stage.Listening) QuickDictation.show(QuickDictation.Stage.Idle)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun listenAndAct() {
        // Read now, not when the run starts: the owner may unlock while it works.
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        // The run's setup, begun while the owner talks, not after.
        val prep = prepare()
        // What this goes on from, where it is a follow-up.
        val thread = QuickDictation.thread
        try {
            val heard = try {
                record(EndOfSpeech(DictationRun.RATE))
            } finally {
                withContext(Dispatchers.Main) { listening = false }
                // Off the listening card, so how it ended can be said there.
                if (QuickDictation.stage.value is QuickDictation.Stage.Listening) QuickDictation.show(QuickDictation.Stage.Working("Writing down what you said…"))
            }
            when {
                heard == null -> QuickDictation.show(QuickDictation.Stage.Idle) // cancelled
                !heard.second -> tell("Nothing heard", "Talon did not hear anything to act on.")
                else -> {
                    update("Working on your dictation…")
                    val wav = WavFile.encode(heard.first, DictationRun.RATE)
                    // Kept before it is sent, so nothing said is lost to a crash or a dead network.
                    val kept = File(keptDir(), "${System.currentTimeMillis()}.wav").apply { writeBytes(wav) }
                    var went: DictationRoute? = null
                    val outcome = pipeline(locked, prep, thread) { went = it }.fromAudio(wav)
                    // What a follow-up goes on from: this, after what came before it.
                    val said = outcome as? DictationOutcome.Done
                    QuickDictation.thread = said?.let {
                        QuickDictation.Thread((thread?.earlier.orEmpty() + DictationFlow.Earlier(it.transcript, it.summary)).takeLast(4), went == DictationRoute.Ask)
                    }
                    // Every exchange on the card is kept in Assistant history, a follow-up with what it follows.
                    if (said != null && prep != null) {
                        QuickDictation.history = prep.remember(QuickDictation.history, said.transcript.take(TITLE_CHARS).trim(), said.transcript, said.summary)
                    }
                    report(kept, outcome, prep?.card, followable = said != null && prep != null)
                }
            }
        } finally {
            prep?.close()
        }
    }

    private suspend fun retry(kept: File) {
        if (!kept.exists()) return tell("Dictation gone", "That dictation is no longer kept on this phone.")
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        val prep = prepare()
        try {
            val run = pipeline(locked, prep)
            report(kept, if (kept.extension == "txt") run.fromText(kept.readText()) else run.fromAudio(kept.readBytes()), prep?.card)
        } finally {
            prep?.close()
        }
    }

    /**
     * A conversation with Archie: the counts said at once while the model
     * writes the rest of the opener, then listen, answer and listen again,
     * until the owner says they are done, falls silent for eight seconds,
     * or closes the card. Every decision is [VoiceConversation]'s; this
     * records, transcribes and speaks. Each exchange goes into Assistant
     * history as it happens, so one cut short keeps what was said.
     */
    private suspend fun converse() {
        val locked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        val app = application as TalonApplication
        val cfg = app.aiSettings.state.value
        val prep = prepare()
        val transcribe = transcriber(cfg)
        try {
            if (prep == null || !cfg.loopsCanRun()) return tell(ARCHIE, DictationRun.NO_MODEL_FOR_QUESTIONS)
            if (transcribe == null) return tell(ARCHIE, DictationRun.NO_SPEECH_TO_TEXT)
            val title = Briefing.title(kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date)
            suspend fun remember(question: String, answer: String) {
                QuickDictation.history = prep.remember(QuickDictation.history, title, question, answer)
            }
            prep.orrery { orrery ->
                kotlinx.coroutines.coroutineScope {
                    val talk = prep.conversation(locked, orrery) { reply, waiting -> ownerDecides(reply, waiting, transcribe) }
                    Log.i(TAG, "talking: ${talk.toolNames.size} tools, orrery ${if (orrery == null) "off" else "on"}, locked=$locked")
                    val t0 = System.currentTimeMillis()
                    val day = prep.day(io.nisfeb.talon.ai.CalendarQuestion.decider(app.ktorHttp, cfg, Briefing.QUESTIONS_WAIT_MS))
                    Log.i(TAG, "the day in ${System.currentTimeMillis() - t0} ms: mail ${day.mail}, chats ${day.chats}, questions ${day.questions}")
                    val card = day.card.takeUnless { it.isEmpty }
                    val counts = Briefing.countsLine(day.mail, day.chats, day.questions)
                    // Written while the counts are said, and said after them:
                    // one voice, so the second would cut the first off.
                    val rest = async { talk.opener(counts, Briefing.openerDigest(prep.now(), day.firstEvent, prep.brief(orrery))) }
                    if (counts.isNotBlank()) {
                        QuickDictation.show(QuickDictation.Stage.Said(counts, card, talking = true))
                        say(counts)
                    }
                    val opener = rest.await()
                    val first = "$counts $opener".trim()
                    QuickDictation.show(QuickDictation.Stage.Said(first, card, talking = true))
                    remember(io.nisfeb.talon.ai.VoiceConversation.OPENED, first)
                    say(DictationSpeech.spoken(opener))
                    var missed = 0
                    while (!QuickDictation.hangUp) {
                        val words = hearOnce(transcribe) ?: break
                        if (words.isBlank()) {
                            if (++missed >= 2) break
                            QuickDictation.show(QuickDictation.Stage.Said(MISSED, talking = true))
                            say(MISSED)
                            continue
                        }
                        missed = 0
                        QuickDictation.show(QuickDictation.Stage.Working("Thinking…"))
                        val next = try {
                            withTimeoutOrNull(RUN_BUDGET_MS) { talk.next(words) }
                                ?: io.nisfeb.talon.ai.VoiceConversation.Next.Say("That took too long. Say it again?")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(TAG, "a turn failed", e)
                            io.nisfeb.talon.ai.VoiceConversation.Next.Say("${DictationRun.why(e)}. Say it again, or close me.")
                        }
                        // Closed while it thought: kept, and not said to a card that is gone.
                        if (QuickDictation.hangUp) {
                            remember(words, (next as? io.nisfeb.talon.ai.VoiceConversation.Next.Say)?.shown ?: (next as io.nisfeb.talon.ai.VoiceConversation.Next.End).line)
                            break
                        }
                        when (next) {
                            is io.nisfeb.talon.ai.VoiceConversation.Next.End -> {
                                remember(words, next.line)
                                QuickDictation.show(QuickDictation.Stage.Said(next.line))
                                say(next.line)
                                break
                            }
                            is io.nisfeb.talon.ai.VoiceConversation.Next.Say -> {
                                remember(words, next.shown)
                                QuickDictation.show(QuickDictation.Stage.Said(next.shown, prep.turnCard().takeUnless { it.isEmpty }, talking = true))
                                hushed = false
                                say(DictationSpeech.spoken(next.line))
                                // Read in full a paragraph at a time: a tap stops it before the next.
                                for (p in next.inFull) {
                                    if (hushed || QuickDictation.hangUp) break
                                    say(p)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            QuickDictation.talking(false)
            prep?.close()
            // The card closes itself once the conversation is over.
            (QuickDictation.stage.value as? QuickDictation.Stage.Said)?.takeIf { it.talking }?.let { QuickDictation.show(it.copy(talking = false)) }
            if (QuickDictation.stage.value is QuickDictation.Stage.Listening || QuickDictation.stage.value is QuickDictation.Stage.Working) QuickDictation.show(QuickDictation.Stage.Idle)
        }
    }

    /**
     * One thing the owner says in a conversation, as text: null when they
     * said nothing for eight seconds, cancelled, or hung up; "" when it
     * could not be made out.
     */
    private suspend fun hearOnce(transcribe: suspend (ByteArray) -> String): String? {
        // An answer to "send?" leaves the recording marked cancelled.
        QuickDictation.ending = null
        withContext(Dispatchers.Main) { listening = true }
        update("Listening…")
        QuickDictation.show(QuickDictation.Stage.Listening(0f))
        val heard = try {
            record(EndOfSpeech(DictationRun.RATE))
        } finally {
            withContext(Dispatchers.Main) { listening = false }
            update("Talking with $ARCHIE…")
        }
        if (heard == null || !heard.second || QuickDictation.hangUp) return null
        QuickDictation.show(QuickDictation.Stage.Working("Writing down what you said…"))
        return try {
            transcribe(WavFile.encode(heard.first, DictationRun.RATE)).trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "could not transcribe a turn: ${e.message}")
            ""
        }
    }

    /** The run's setup, where a run could happen at all: a ship, and orrery or a model to run on. */
    private fun prepare(): io.nisfeb.talon.ai.Loops.DictationPrep? {
        val app = application as TalonApplication
        val cfg = app.aiSettings.state.value
        if (app.sessionStore.activeShip() == null || (!cfg.loopsCanRun() && !cfg.orreryOn())) return null
        return runCatching { app.loops.prepareDictation() }.onFailure { Log.w(TAG, "could not prepare the run", it) }.getOrNull()
    }

    /**
     * The recording and whether there was speech in it, or null when
     * cancelled. [level] says how loud, for the card, while listening to
     * a dictation; the spoken answer to "send?" shows none.
     */
    private fun record(gate: EndOfSpeech, level: Boolean = true): Pair<ByteArray, Boolean>? {
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
        val out = ByteArrayOutputStream()
        // The numbers the thresholds are tuned by, in a release logcat.
        fun said(how: String) = Log.i(TAG, "listened ${out.size() * 500L / DictationRun.RATE} ms, $how: heard=${gate.heard} peak=${gate.peak.toInt()} floor=${gate.floor.toInt()}")
        try {
            rec.startRecording()
            while (true) {
                when (QuickDictation.ending) {
                    QuickDictation.Ending.Cancel -> return null.also { said("cancelled") }
                    // Pressed Done: sent even if the gate heard nothing, since the owner says they spoke.
                    QuickDictation.Ending.Done -> return (out.toByteArray() to (out.size() > 0)).also { said("done pressed") }
                    null -> Unit
                }
                val n = rec.read(frame, 0, frame.size)
                if (n < 0) error("The microphone stopped ($n).")
                out.write(frame, 0, n)
                val more = gate.offer(frame, n)
                if (level) QuickDictation.show(QuickDictation.Stage.Listening(gate.level))
                if (!more) return (out.toByteArray() to gate.heard).also { said("gate ended") }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /**
     * Speech to text: the owner's own key wherever there is one, else
     * Armillary's transcription model, else none and the card asks for a key.
     */
    private fun transcriber(cfg: io.nisfeb.talon.ai.AiSettings.Config): (suspend (ByteArray) -> String)? {
        val app = application as TalonApplication
        return when (val hearing = DictationRun.hearing(cfg)) {
            null -> null
            is io.nisfeb.talon.ai.Hearing.Own -> {
                val whisper = DictationRun.whisper(app.ktorHttp, hearing.stt)
                val own: suspend (ByteArray) -> String = { wav ->
                    val t0 = System.currentTimeMillis()
                    whisper(wav).also { Log.i(TAG, "transcribed ${wav.size} bytes in ${System.currentTimeMillis() - t0} ms") }
                }
                own
            }
            is io.nisfeb.talon.ai.Hearing.Armillary -> {
                val bought: suspend (ByteArray) -> String = { wav ->
                    val t0 = System.currentTimeMillis()
                    // Compressed: the vendor's proxy keeps every body it is
                    // sent, and caps them at 16 MB. The WAV only where the
                    // phone cannot encode.
                    val m4a = m4aOf(cacheDir, wav.copyOfRange(WAV_HEADER, wav.size), DictationRun.RATE)
                    io.nisfeb.talon.ai.ArmillaryTranscribe.transcribe(
                        app.ktorHttp, hearing, m4a ?: wav, if (m4a != null) "m4a" else "wav",
                        java.util.Locale.getDefault().language,
                    ).also {
                        Log.i(TAG, "transcribed through Armillary (${hearing.model} at ${android.net.Uri.parse(hearing.baseUrl).host}), ${if (m4a != null) "m4a ${m4a.size}" else "wav ${wav.size}"} bytes of ${wav.size} in ${System.currentTimeMillis() - t0} ms")
                    }
                }
                bought
            }
        }
    }

    private fun pipeline(
        locked: Boolean,
        prep: io.nisfeb.talon.ai.Loops.DictationPrep?,
        thread: QuickDictation.Thread? = null,
        went: (DictationRoute) -> Unit = {},
    ): DictationRun {
        val app = application as TalonApplication
        val cfg = app.aiSettings.state.value
        // Whether the calendar alone answers: the decision model's call, on a client with no ship cookie.
        val decider = io.nisfeb.talon.ai.CalendarQuestion.decider(app.ktorHttp, cfg)
        val transcribe = transcriber(cfg)
        val act: suspend (String) -> String = { words ->
            val p = prep!!
            // A timeout here and not a cancellation: one would unwind
            // past the outcome and the words would go unkept.
            withTimeoutOrNull(RUN_BUDGET_MS) {
                p.orrery { orrery ->
                    if (orrery == null) {
                        QuickDictation.show(QuickDictation.Stage.Working("Working on it…"))
                        went(DictationRoute.Ask)
                        p.calendarAnswer(words, thread?.earlier.orEmpty(), decider) ?: p.dictate(words, locked, thread?.earlier.orEmpty())
                    } else {
                        DictationFlow(
                            ask = { q, earlier ->
                                QuickDictation.show(QuickDictation.Stage.Working("Looking it up…"))
                                p.ask(q, earlier, orrery)
                            },
                            instruct = { t ->
                                QuickDictation.show(QuickDictation.Stage.Working("Asking orrery…"))
                                val t0 = System.currentTimeMillis()
                                orrery.instruct(t, apply = false).getOrThrow()
                                    .also { Log.i(TAG, "orrery answered in ${System.currentTimeMillis() - t0} ms, ${it.actions.size} actions, ${it.actions.count { a -> a.status == "proposed" }} waiting") }
                            },
                            hand = { t, title ->
                                QuickDictation.show(QuickDictation.Stage.Working("Handing it to orrery…"))
                                val said = orrery.hand(t, title).getOrThrow()
                                (said["dropped"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { error("Orrery did not take it: $it") }
                            },
                            answer = { id, ok -> orrery.setAction(id, if (ok) "approved" else "dismissed").getOrThrow() },
                            decide = { reply, waiting -> ownerDecides(reply, waiting, transcribe) },
                            quick = { t, earlier -> p.calendarAnswer(t, earlier, decider) },
                        ).handle(words, thread?.earlier.orEmpty(), thread?.asked == true)
                            .also { Log.i(TAG, "route=${it.route}${if (thread != null) ", a follow-up" else ""}"); went(it.route) }
                            .line
                    }
                }
            } ?: error("It took longer than ${RUN_BUDGET_MS / 60_000} minutes")
        }
        return DictationRun(
            transcribe = transcribe,
            act = act.takeIf { prep != null },
        )
    }

    /**
     * Put what orrery left waiting to the owner: on the card, which listens
     * a few seconds for "send" or "cancel", and in the shade whenever the
     * card is not in front. True for send, false for drop, null when no
     * word came in [APPROVE_WAIT_MS], which leaves it waiting under Actions.
     * Nothing that reaches another person is sent from a locked phone
     * until the fingerprint: the card asks for it, the shade's Send needs it.
     */
    private suspend fun ownerDecides(
        reply: String,
        waiting: List<io.nisfeb.talon.orrery.OrreryAction>,
        transcribe: (suspend (ByteArray) -> String)?,
    ): Boolean? = kotlinx.coroutines.coroutineScope {
        val word = QuickDictation.asking()
        val ask = QuickDictation.Stage.Approve(
            reply = reply,
            proposals = waiting.map {
                io.nisfeb.talon.ai.DictationCards.proposal(
                    it, kotlinx.datetime.TimeZone.currentSystemDefault(), System.currentTimeMillis(),
                    android.text.format.DateFormat.is24HourFormat(this@DictationService),
                )
            },
            lines = waiting.map(DictationFlow::describe),
            sending = waiting.any { it.kind == "message" },
            hearing = transcribe != null,
            unlock = DictationFlow.needsUnlock(waiting),
        )
        QuickDictation.show(ask)
        update(if (ask.sending) "Waiting for your OK to send…" else "Waiting for your OK…")
        // The shade, whenever the card is not in front: now, or once it goes.
        val shade = launch {
            QuickDictation.shown.first { !it }
            askInShade(ask, waiting.map { it.id })
        }
        val message = ask.proposals.firstOrNull { it.kind == "message" }
        val asked = DictationSpeech.prompt(reply, ask.sending, message?.to, message?.text)
        // Asked aloud first, and only then listened for: not to hear itself.
        val hearing = launch(Dispatchers.IO) {
            say(asked)
            if (transcribe != null && word.isActive) hearAnswer(word, ask, transcribe)
        }
        try {
            withTimeoutOrNull(APPROVE_WAIT_MS) { word.await() }
                .also { Log.i(TAG, "the owner said ${it ?: "nothing in time"} to ${waiting.size} waiting") }
        } finally {
            // Stops the answer's recording, which is a blocking loop.
            QuickDictation.ending = QuickDictation.Ending.Cancel
            hearing.cancel()
            shade.cancel()
            NotificationManagerCompat.from(this@DictationService).cancel(ASK_ID)
            QuickDictation.show(QuickDictation.Stage.Working("Telling orrery…"))
        }
    }

    /** One short listen for "send" or "cancel"; anything else leaves it to the buttons. */
    private suspend fun hearAnswer(
        word: kotlinx.coroutines.CompletableDeferred<Boolean>,
        ask: QuickDictation.Stage.Approve,
        transcribe: suspend (ByteArray) -> String,
    ) {
        QuickDictation.ending = null
        // Eight seconds: the owner reads the card first (five was too few on the phone).
        val clip = record(EndOfSpeech(DictationRun.RATE, endSilenceMs = 800, giveUpMs = 8_000, maxMs = 9_000), level = false)
        val text = if (clip != null && clip.second) runCatching { transcribe(WavFile.encode(clip.first, DictationRun.RATE)) }.getOrNull() else null
        val yes = text?.let(DictationFlow::heard)
        Log.i(TAG, "spoken answer: ${if (text == null) "none" else "\"$text\""} -> $yes")
        if (!word.isActive) return
        when {
            yes == false -> word.complete(false)
            yes == true && ask.unlock && getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true ->
                // Said send on a locked phone: the card asks for the fingerprint now.
                QuickDictation.show(ask.copy(hearing = false, unlockNow = true))
            yes == true -> word.complete(true)
            else -> QuickDictation.show(ask.copy(hearing = false))
        }
    }

    /** Send or Don't send in the shade, heads-up, for when the card is not in front. */
    private fun askInShade(ask: QuickDictation.Stage.Approve, ids: List<String>) {
        Notifications.ensureChannel(this)
        fun decide(approve: Boolean) = PendingIntent.getForegroundService(
            this, if (approve) ASK_ID else ASK_ID + 1,
            Intent(this, DictationService::class.java).setAction(ACTION_DECIDE)
                .putExtra(EXTRA_APPROVE, approve).putExtra(EXTRA_IDS, ids.toTypedArray()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val yes = NotificationCompat.Action.Builder(0, if (ask.sending) "Send" else "Do it", decide(true))
            // From a locked phone, Android asks for the fingerprint before it fires.
            .setAuthenticationRequired(ask.unlock)
            .build()
        val n = NotificationCompat.Builder(this, Notifications.CHANNEL_DICTATION_ASK)
            .setSmallIcon(R.drawable.ic_stat_talon)
            .setContentTitle(ask.reply)
            .setContentText(ask.lines.joinToString("\n"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ask.lines.joinToString("\n")))
            .setContentIntent(PendingIntent.getActivity(this, ASK_ID, Intent(this, QuickDictationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(yes)
            .addAction(0, if (ask.sending) "Don't send" else "Don't", decide(false))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOngoing(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ASK_ID, n) }
            .onFailure { Log.w(TAG, "no permission to ask in the shade") }
    }

    /** An answer from the shade after the run that asked has gone on: straight to orrery. */
    private suspend fun decideLate(ids: List<String>, approve: Boolean) {
        val prep = prepare() ?: return tell("Dictation", "Talon could not reach orrery to answer. It waits under Actions.")
        try {
            prep.orrery { orrery ->
                if (orrery == null) return@orrery tell("Dictation", "Orrery is off. It waits under Actions.")
                val failed = ids.mapNotNull { id -> orrery.setAction(id, if (approve) "approved" else "dismissed").exceptionOrNull() }
                if (failed.isEmpty()) tell("Dictation", if (approve) "Done." else "Dropped. Nothing was done.")
                else tell("Dictation", "Orrery did not take that answer. It waits under Actions.", details = io.nisfeb.talon.util.errorDetailsOf(failed.first()))
            }
        } finally {
            prep.close()
        }
    }

    /** Say how it went, and keep [kept] (the audio, or the words) only where it can be tried again. */
    private suspend fun report(kept: File, outcome: DictationOutcome, card: io.nisfeb.talon.ai.DictationCard? = null, followable: Boolean = false) {
        when (outcome) {
            is DictationOutcome.Done -> {
                kept.delete()
                tell("Dictation", outcome.summary, "${outcome.summary}\n\nYou said: ${outcome.transcript}", card = card, followable = followable)
            }
            DictationOutcome.NothingHeard -> {
                kept.delete()
                tell("Nothing heard", "Talon could not make out any words.")
            }
            is DictationOutcome.NeedsSetup -> tell("Dictation not sent", outcome.what + " It is kept to try again.", retry = kept)
            is DictationOutcome.TranscribeFailed -> tell(
                "Dictation not sent",
                "${outcome.why}. It is kept to try again.",
                retry = kept,
                details = outcome.details,
            )
            is DictationOutcome.RunFailed -> {
                // The words are what is left to try again, not the audio: no second transcription.
                val words = File(keptDir(), kept.nameWithoutExtension + ".txt").apply { writeText(outcome.transcript) }
                if (words != kept) kept.delete()
                tell(
                    "Dictation not done",
                    "${outcome.why}. It may have done part of it before it stopped.",
                    "You said: ${outcome.transcript}\n\n${outcome.why}. It may have done part of it before it stopped, so check before trying again.",
                    retry = words,
                    details = outcome.details,
                )
            }
        }
    }

    private suspend fun tell(
        title: String,
        text: String,
        long: String = text,
        retry: File? = null,
        details: String? = null,
        card: io.nisfeb.talon.ai.DictationCard? = null,
        followable: Boolean = false,
    ) {
        // The card, where it is still up, says it too; never over one
        // that is listening or asking, from an answer that came late.
        val now = QuickDictation.stage.value
        if (now !is QuickDictation.Stage.Listening && now !is QuickDictation.Stage.Approve) {
            QuickDictation.show(QuickDictation.Stage.Said(text, card?.takeUnless { it.isEmpty }, followable))
        }
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
        if (details != null) {
            n.addAction(
                0, "Copy details",
                PendingIntent.getBroadcast(
                    this, tag.hashCode(),
                    Intent(this, DictationCopyReceiver::class.java).putExtra(DictationCopyReceiver.EXTRA_DETAILS, details),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        runCatching { NotificationManagerCompat.from(this).notify(tag, RESULT_ID, n.build()) }
            .onFailure { Log.w(TAG, "no permission to post the dictation's result") }
        // The highlights aloud, the rest on the card; a late answer is not news enough to speak over another.
        if (now !is QuickDictation.Stage.Listening && now !is QuickDictation.Stage.Approve) say(DictationSpeech.spoken(text))
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
        const val ACTION_CONVERSE = "io.nisfeb.talon.action.DICTATION_CONVERSE"
        // A conversation reads the brief out and goes on for a while.
        private const val TALK_LOCK_MS = 30L * 60 * 1000
        /** A dictation's conversation in Assistant history is titled by its first words. */
        private const val TITLE_CHARS = 60
        private const val MISSED = "Sorry, I didn't catch that."
        /** WavFile.encode's header, before the PCM. */
        private const val WAV_HEADER = 44
        const val ACTION_RETRY = "io.nisfeb.talon.action.DICTATION_RETRY"
        const val ACTION_DECIDE = "io.nisfeb.talon.action.DICTATION_DECIDE"
        private const val EXTRA_APPROVE = "approve"
        private const val EXTRA_IDS = "ids"
        private const val ASK_ID = 7707
        // How long the owner has to say send: well inside RUN_BUDGET_MS,
        // so a run that waited is a line, never a failure that runs it again.
        private const val APPROVE_WAIT_MS = 2L * 60 * 1000
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
 * 16-bit mono [pcm] as AAC in an MP4 box (m4a), about a tenth the size of
 * the WAV: what goes to Armillary, whose proxy keeps every body it is
 * sent. Null where the phone's encoder fails, and the WAV goes instead.
 */
private fun m4aOf(cacheDir: File, pcm: ByteArray, sampleRate: Int): ByteArray? {
    val out = File.createTempFile("dictation", ".m4a", cacheDir)
    var codec: android.media.MediaCodec? = null
    var muxer: android.media.MediaMuxer? = null
    return try {
        val format = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val c = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
        c.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        val m = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
        val info = android.media.MediaCodec.BufferInfo()
        var track = -1
        var offset = 0
        var fed = false
        var done = false
        val until = System.currentTimeMillis() + 20_000
        while (!done) {
            if (System.currentTimeMillis() > until) error("the encoder took too long")
            if (!fed) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = c.getInputBuffer(i) ?: error("no input buffer")
                    buf.clear()
                    val n = minOf(buf.capacity() and 1.inv(), pcm.size - offset)
                    val us = offset / 2 * 1_000_000L / sampleRate
                    if (n <= 0) {
                        c.queueInputBuffer(i, 0, 0, us, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        fed = true
                    } else {
                        buf.put(pcm, offset, n)
                        c.queueInputBuffer(i, 0, n, us, 0)
                        offset += n
                    }
                }
            }
            val o = c.dequeueOutputBuffer(info, 10_000)
            when {
                o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                }
                o >= 0 -> {
                    val buf = c.getOutputBuffer(o) ?: error("no output buffer")
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        m.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        if (track < 0) error("the encoder gave no audio")
        c.stop()
        m.stop()
        out.readBytes()
    } catch (e: Exception) {
        Log.w("Dictation", "could not compress the recording; the WAV goes instead: ${e.message}")
        null
    } finally {
        runCatching { codec?.release() }
        runCatching { muxer?.release() }
        out.delete()
    }
}

/**
 * The card's voice: the phone's own text to speech, played as an
 * assistant speaks, through the car where one is connected, with other
 * audio ducked while it talks. Nothing leaves the phone to be spoken.
 * Where the phone has no engine, nothing is said and the card still shows it.
 */
private class Voice(ctx: Context) {
    private val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private val tts = android.speech.tts.TextToSpeech(ctx.applicationContext) { ready.complete(it == android.speech.tts.TextToSpeech.SUCCESS) }
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private val attributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    @Volatile private var saying: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    init {
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { saying?.complete(Unit) }
            @Deprecated("the platform's own")
            override fun onError(id: String?) { saying?.complete(Unit) }
            override fun onStop(id: String?, interrupted: Boolean) { saying?.complete(Unit) }
        })
    }

    /** Say [text] and return once it is said, stopped, or a minute has gone. */
    suspend fun say(text: String) {
        val ok = kotlinx.coroutines.withTimeoutOrNull(3_000) { ready.await() } == true
        if (!ok) return Log.w("Dictation", "no text to speech engine answered; the card shows it only")
        val done = kotlinx.coroutines.CompletableDeferred<Unit>().also { saying = it }
        audio?.requestAudioFocus(focus)
        try {
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "dictation-${System.nanoTime()}")
            kotlinx.coroutines.withTimeoutOrNull(60_000) { done.await() }
        } finally {
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    fun hush() {
        tts.stop()
        saying?.complete(Unit)
    }

    fun shutdown() = tts.shutdown()
}

/** A failed dictation's error, whole, to the clipboard: "Copy error details" for a notification. */
class DictationCopyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val details = intent.getStringExtra(EXTRA_DETAILS) ?: return
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Dictation error", details))
    }

    companion object {
        const val EXTRA_DETAILS = "details"
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
