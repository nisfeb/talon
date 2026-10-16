package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

/**
 * One voice dictation, run through the agent and acted on with NO
 * confirmation — the "dictate straight into orrery" surfaces (widget,
 * Quick Settings tile, assist-app) fire this.
 *
 * Unlike the assistant screen, which confirms each write before it runs,
 * a dictation authorises every write up front: the owner asked for the
 * least possible friction (speak, done), so the agent writes to orrery /
 * the calendar / the ship directly and the result notification is the
 * only after-the-fact signal. This is the same autonomy a scheduled Loop
 * has; a dictation is just a one-shot of it with the spoken text as the
 * turn.
 *
 * A blank transcript runs nothing, so a mis-fire or a silent recording
 * is a no-op rather than an empty agent round-trip.
 *
 * The Android surfaces assemble [tools], [systemPrompt] and [completer]
 * (the same orrery + calendar + message tools a Loop gets) and call
 * [run]. The autonomy and the blank-guard live here so they are tested
 * once, without a device. See
 * docs/superpowers/specs/2026-10-15-dictate-to-orrery-design.md.
 */
class DictationAgent(
    private val completer: AgentLoop.Completer,
    private val tools: List<Tool>,
    private val systemPrompt: String = composePrompt(AgentPrompt.urbitKnowledge, role),
    private val maxSteps: Int = 8,
) {
    /**
     * Run [transcript] and return the agent's own summary of what it
     * did (for the result notification), or "" for a blank dictation,
     * which runs nothing.
     */
    suspend fun run(transcript: String): String {
        val text = transcript.trim()
        if (text.isEmpty()) return ""
        return AgentLoop(completer, tools, systemPrompt, maxSteps)
            .run(question = text, confirm = { _, _ -> true })
    }

    companion object {
        /**
         * A dictation's own role. Not the loop's: that one says to default
         * to reading and write only when the task calls for it, and here
         * the spoken words ARE the task.
         */
        val role: String = """
            The owner just spoke this to you, and speech-to-text wrote it
            down, so expect the odd misheard word and read it for what
            they meant. No one is watching and nobody will answer a
            question: act on it now, completely, with the tools listed for
            you, and nothing else. Your answer is shown as a notification,
            so make it one short line saying what you did.

            - Something to keep (a thought, a note, a list, what someone
              said): hand it to orrery with orrery_file_text, in the
              owner's words, with a title.
            - Something orrery holds that should change, or a preference:
              orrery_instruct, with apply true.
            - An appointment or an event: create_event, and only that. A
              to-do with no time: create_task.
            - Mail or a chat message goes out only when the owner said to
              send it, to whom, and what.
            - If a tool you would need is missing, nothing was done that
              way: say so in your line rather than doing something else.
        """.trimIndent()

        /** The effective prompt: the owner's Urbit knowledge, then [role]. */
        fun prompt(cfg: AiSettings.Config): String =
            composePrompt(cfg.urbitKnowledgePrompt.ifBlank { AgentPrompt.urbitKnowledge }, role)

        /**
         * The writes a dictation keeps while the phone is locked: the
         * batches the owner lets through without asking ([ALLOW_ALL_TOOLS])
         * and handing words to orrery. Anyone holding a locked phone can
         * speak to it, so nothing that reaches another person (mail, a
         * message, a share, an invite) and nothing destructive.
         */
        val LOCKED_WRITES: Set<String> = ALLOW_ALL_TOOLS + setOf("orrery_file_text", "orrery_instruct")

        /** [all] as a dictation may use them; while [locked], only [LOCKED_WRITES] among the writes. */
        fun toolsFor(all: List<Tool>, locked: Boolean): List<Tool> =
            all.dedupToolNames().filter { !locked || !it.write || it.spec.name in LOCKED_WRITES }
    }
}

/** How one dictation ended. Every one of them is a notification. */
sealed interface DictationOutcome {
    /** Acted on: what was heard, and the agent's line. */
    data class Done(val transcript: String, val summary: String) : DictationOutcome

    /** Silence, or words too faint to make out. Nothing to keep. */
    data object NothingHeard : DictationOutcome

    /** Talon cannot run one yet; [what] says what to set up. What was said is kept. */
    data class NeedsSetup(val what: String) : DictationOutcome

    /** The audio did not become text: [why] in words, the error whole in [details]. The audio is kept to try again. */
    data class TranscribeFailed(val why: String, val details: String) : DictationOutcome

    /** Heard, but the run failed. The text is kept to try again; the run may have done part of it. */
    data class RunFailed(val transcript: String, val why: String, val details: String) : DictationOutcome
}

/**
 * The steps of one dictation, apart from any device: audio to text,
 * text to the agent. [transcribe] is null with no speech-to-text set up,
 * [act] null with no model or ship to act with.
 */
class DictationRun(
    private val transcribe: (suspend (wav: ByteArray) -> String)?,
    private val act: (suspend (transcript: String) -> String)?,
) {
    /** From the recording. Nothing is spent on a transcription a run could not use. */
    suspend fun fromAudio(wav: ByteArray): DictationOutcome {
        val heard = transcribe ?: return DictationOutcome.NeedsSetup(NO_SPEECH_TO_TEXT)
        if (act == null) return DictationOutcome.NeedsSetup(NO_MODEL)
        val text = try {
            heard(wav).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return DictationOutcome.TranscribeFailed(why(e), io.nisfeb.talon.util.errorDetailsOf(e))
        }
        return if (text.isEmpty()) DictationOutcome.NothingHeard else fromText(text)
    }

    /** From words already heard: a retry after a failed run. */
    suspend fun fromText(transcript: String): DictationOutcome {
        val text = transcript.trim()
        if (text.isEmpty()) return DictationOutcome.NothingHeard
        val run = act ?: return DictationOutcome.NeedsSetup(NO_MODEL)
        val summary = try {
            run(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return DictationOutcome.RunFailed(text, why(e), io.nisfeb.talon.util.errorDetailsOf(e))
        }
        return DictationOutcome.Done(text, summary.ifBlank { "Done." })
    }

    companion object {
        /** What a dictation is recorded at: Whisper's own rate, so nothing is resampled or uploaded for nothing. */
        const val RATE = 16_000

        const val NO_SHIP = "Log in to your ship in Talon first."
        const val NO_SPEECH_TO_TEXT = "Set an OpenAI-compatible transcription key in AI settings."
        const val NO_MODEL = "Turn on the assistant and give it a model in AI settings."

        /** What stops a dictation before it records, in words for the owner; null when nothing does. */
        fun blocker(cfg: AiSettings.Config, hasShip: Boolean): String? = when {
            !hasShip -> NO_SHIP
            CallRecordingPublisher.sttFrom(cfg) == null -> NO_SPEECH_TO_TEXT
            !cfg.loopsCanRun() -> NO_MODEL
            else -> null
        }

        /** Speech to text with the transcription key from AI settings ([CallRecordingPublisher.sttFrom]). */
        fun whisper(http: HttpClient, stt: CallRecordingPublisher.Stt): suspend (ByteArray) -> String = { wav ->
            text(CallTranscribe.transcribe(http, stt.endpoint, stt.key, stt.model, wav))
        }

        /**
         * A failure as the owner is told it, in words: no network, a
         * service that said no, or what it said where that reads as a
         * sentence. Never an exception's name or a host; those are in
         * the details.
         */
        fun why(e: Throwable): String {
            val refused = Regex("""^transcription failed: (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)
            return when {
                io.nisfeb.talon.util.isTransientNetworkError(e) -> "No network, or the service did not answer"
                refused != null -> "The transcription service said no ($refused)"
                else -> io.nisfeb.talon.util.readableReason(e.message)?.trimEnd('.') ?: "Something went wrong"
            }
        }

        /** Whisper's segments as one line of text. */
        fun text(segments: List<CallTranscribe.Segment>): String =
            segments.joinToString(" ") { it.text.trim() }.trim()
    }
}
