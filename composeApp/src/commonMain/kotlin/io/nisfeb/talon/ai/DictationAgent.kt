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
    private val systemPrompt: String = prompt,
    private val maxSteps: Int = 8,
) {
    /**
     * Run [transcript] and return the agent's own summary of what it
     * did (for the result notification), or "" for a blank dictation,
     * which runs nothing.
     */
    suspend fun run(transcript: String, earlier: List<DictationFlow.Earlier> = emptyList()): String {
        val text = transcript.trim()
        if (text.isEmpty()) return ""
        // A follow-up goes on from what was asked and answered before it.
        val prior = earlier.flatMap { listOf(AgentMessage.User(it.words), AgentMessage.Assistant(it.line, emptyList())) }
        return AgentLoop(completer, tools, systemPrompt, maxSteps)
            .run(question = text, priorTurns = prior, confirm = { _, _ -> true }, finish = ::plainly)
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

        /**
         * A question's role, where Orrery takes everything else
         * ([DictationFlow]): look it up and answer, and do nothing.
         */
        val askRole: String = """
            The owner just asked you this out loud, and speech-to-text
            wrote it down, so expect the odd misheard word. No one will
            answer a question back. Look it up with the tools listed for
            you and answer in one or two short sentences, shown as a
            notification. You can only look things up here: if they asked
            for something to be done, say it was not done, and that saying
            it as an instruction rather than a question hands it to orrery.
        """.trimIndent()

        /** A question's whole prompt. */
        val askPrompt: String = DATA_NOT_INSTRUCTIONS + "\n\n" + askRole

        /** [all] as a question may use them: the reads of [toolsFor]. */
        fun readsFor(all: List<Tool>): List<Tool> = toolsFor(all, locked = false).filterNot { it.write }

        /**
         * The whole prompt: the content-is-data rule and [role]. Not the
         * Urbit knowledge the assistant gets: it is about the ship's MCP
         * tools and scrying, which a dictation has none of, and every
         * model call paid for it.
         */
        val prompt: String = DATA_NOT_INSTRUCTIONS + "\n\n" + role

        /**
         * The tools a dictation is offered: the ones a spoken note,
         * question or errand uses. Not orrery's settings, calendar
         * sharing and sync, reactions, the web: their descriptions were a
         * third of every request, and nobody dictates them.
         */
        val TOOLS: Set<String> = setOf(
            "search_history", "read_conversation", "send_message", "find_person", "find_conversation",
            "send_mail", "list_mail", "search_mail", "read_mail",
            "list_calendars", "list_events", "create_event", "update_event", "delete_event",
            "list_tasks", "create_task", "complete_task",
            "orrery_guide", "orrery_find", "orrery_read", "orrery_observe", "orrery_file_text", "orrery_instruct", "orrery_correct",
        )

        /**
         * The writes a dictation keeps while the phone is locked: the
         * batches the owner lets through without asking ([ALLOW_ALL_TOOLS])
         * and handing words to orrery. Anyone holding a locked phone can
         * speak to it, so nothing that reaches another person (mail, a
         * message, a share, an invite) and nothing destructive.
         */
        val LOCKED_WRITES: Set<String> = ALLOW_ALL_TOOLS + setOf("orrery_file_text", "orrery_instruct")

        /**
         * The tools that read the calendar as it was read in: they wait
         * for that read, which runs while the owner talks ([waitingFor]).
         * send_mail too, for an event it sends as an invite.
         */
        val CALENDAR_TOOLS: Set<String> = setOf(
            "list_calendars", "list_events", "create_event", "update_event", "delete_event",
            "list_tasks", "create_task", "complete_task", "send_mail",
        )

        /** [all] as a dictation may use them: [TOOLS] only, and while [locked], only [LOCKED_WRITES] among the writes. */
        fun toolsFor(all: List<Tool>, locked: Boolean): List<Tool> =
            all.dedupToolNames().filter { it.spec.name in TOOLS && (!locked || !it.write || it.spec.name in LOCKED_WRITES) }

        /**
         * A turn's writes said back to the owner as the tools reported
         * them, so no second model call is spent wording "done": about
         * three seconds on the phone. Null, and the model words it, unless
         * every call in the turn was a write that said it worked in the
         * words below; a read, a refusal or anything unforeseen goes back
         * to the model.
         */
        fun plainly(turn: List<Pair<ToolCall, ToolResult>>): String? {
            if (turn.isEmpty()) return null
            return turn.map { (call, r) -> said(call, r.content) ?: return null }.joinToString("\n")
        }

        private fun said(call: ToolCall, result: String): String? {
            fun arg(k: String) = (call.args[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            return when (call.name) {
                // Its note on syncing is for the model, not the owner.
                "create_event" -> result.takeIf { it.startsWith("Added \"") }?.substringBefore(" It is synced")
                "create_task" -> result.takeIf { it.startsWith("Added task \"") }
                "update_event" -> result.takeIf { it.startsWith("Updated \"") }
                "delete_event" -> result.takeIf { it.startsWith("Deleted \"") }
                "complete_task" -> result.takeIf { it.startsWith("Done: ") || it.startsWith("Reopened: ") }
                "send_mail" -> result.takeIf { it.startsWith("Mailed ") }
                "send_message" -> result.takeIf { it == "Sent." }?.let { "Sent to ${arg("whom") ?: "them"}." }
                "orrery_file_text" -> result.takeIf { it.startsWith("Handed to orrery to read") }
                    ?.let { "Filed in orrery: " + (arg("title") ?: arg("text")?.take(80) ?: "your note") + "." }
                else -> null
            }
        }
    }
}

/** These tools [names] each waiting for [ready] before they run; the rest as they are. */
fun List<Tool>.waitingFor(names: Set<String>, ready: suspend () -> Unit): List<Tool> =
    map { t -> if (t.spec.name !in names) t else Tool(t.spec, t.write) { args -> ready(); t.execute(args) } }

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
        const val NO_MODEL_FOR_QUESTIONS = "Questions need the assistant. Turn it on and give it a model in AI settings."

        /**
         * What stops a dictation before it records, in words for the owner;
         * null when nothing does. With Orrery on, a note needs no model of
         * Talon's: only a question does, and it says so itself.
         */
        fun blocker(cfg: AiSettings.Config, hasShip: Boolean): String? = when {
            !hasShip -> NO_SHIP
            CallRecordingPublisher.sttFrom(cfg) == null -> NO_SPEECH_TO_TEXT
            !cfg.loopsCanRun() && !cfg.orreryOn() -> NO_MODEL
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
