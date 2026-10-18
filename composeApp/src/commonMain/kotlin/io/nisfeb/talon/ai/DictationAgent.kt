package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.nisfeb.talon.urbit.asText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * One voice dictation, run through the agent and acted on with no
 * confirmation but for its sends. The "dictate straight into orrery"
 * surfaces (widget, Quick Settings tile, assist-app) fire this.
 *
 * Unlike the assistant screen, which confirms each write before it runs,
 * a dictation authorises its writes up front: the owner asked for the
 * least possible friction (speak, done), so the agent writes to orrery /
 * the calendar directly and the result notification is the only
 * after-the-fact signal. A message or a mail is the exception: the run
 * reads other people's words, so each send waits for the owner's word
 * ([ownerSends], sneagan 2026-10-18). While [locked], a read of messages
 * or mail waits for the fingerprint ([unlock]).
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
    /** The owner's word on a send before it goes ([askingToSend]); with none, nothing is sent. */
    private val ownerSends: suspend (CardProposal) -> Boolean? = { null },
    /** A conversation's or a ship's name, for the card that asks. */
    private val name: (String) -> String = { it },
    /** Whether the phone is locked now, read at each read of messages or mail. */
    private val locked: () -> Boolean = { false },
    /** The fingerprint while [locked] ([unlockingReads]): true once the phone is unlocked. */
    private val unlock: suspend () -> Boolean = { false },
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
        val gated = tools.askingToSend(name, ownerSends).unlockingReads(locked, unlock)
        return AgentLoop(completer, gated, systemPrompt, maxSteps)
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
            you, and nothing else. Your answer is read aloud and shown as a
            notification, so make it one short plain line saying what you
            did, with no markdown or @p.

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
            you. Orrery is the owner's own record of their life: trips,
            plans, people, places, what each open situation needs, and
            the actions still to do. For anything beyond what is on given
            days (what is left to do, a trip, a person, a plan), read
            orrery first: orrery_read with no body gives every open
            situation and action, then orrery_find and orrery_read with a
            body for the one you need. The calendar says when, orrery says
            what and why.

            Begin your answer with one short sentence that is read aloud
            to the owner, who may be driving: the highlights only, under
            25 words, with no lists, links, markdown or @p. Then a blank
            line and the details, one short line each. The screen draws
            the events, tasks, messages and mail the tools found, so never
            list those one by one: say only what they do not show (a
            clash, a gap, something to bring), in at most three lines.
            Orrery's situations and actions are not on the screen, so name
            each one that answers the question, in at most eight lines.
            You can only look things up here, and open maps or the dialer
            when they ask: anything else they asked to be done, say it was
            not done, and that saying it as an instruction rather than a
            question hands it to orrery.
        """.trimIndent()

        /** A question's whole prompt. */
        val askPrompt: String = DATA_NOT_INSTRUCTIONS + "\n\n" + askRole

        /**
         * [all] and [orrery]'s tools as a question may use them: the reads
         * of [toolsFor]. Not orrery's guide, which is rules for writing.
         */
        fun readsFor(all: List<Tool>, orrery: OrreryTap? = null): List<Tool> =
            toolsFor(all + orrery?.let { orreryTools(it) }.orEmpty(), locked = false)
                .filterNot { it.write || it.spec.name == "orrery_guide" }

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
         * sharing and sync, reactions, reading whole web pages: their
         * descriptions were a third of every request, and nobody dictates
         * them. What is asked in the car is, though: places near
         * somewhere, the way there, a quick fact, a free hour.
         */
        val TOOLS: Set<String> = setOf(
            "search_history", "read_conversation", "list_unread", "send_message", "find_person", "find_conversation",
            "send_mail", "list_mail", "search_mail", "read_mail",
            "list_calendars", "list_events", "find_free_time", "create_event", "update_event", "delete_event",
            "list_tasks", "create_task", "complete_task",
            "find_places", "open_directions", "call_place", "web_search",
            "orrery_guide", "orrery_find", "orrery_read", "orrery_observe", "orrery_file_text", "orrery_instruct", "orrery_correct",
        )

        /**
         * The writes a dictation keeps while the phone is locked: the
         * batches the owner lets through without asking ([ALLOW_ALL_TOOLS])
         * and handing words to orrery. Anyone holding a locked phone can
         * speak to it, so nothing that reaches another person (mail, a
         * message, a share, an invite) and nothing destructive.
         * update_event refuses, while locked, an event with guests
         * ([AssistantActions.locked]): a change to it reaches them.
         */
        val LOCKED_WRITES: Set<String> = ALLOW_ALL_TOOLS + setOf("orrery_file_text", "orrery_instruct")

        /**
         * The tools that read the calendar as it was read in: they wait
         * for that read, which runs while the owner talks ([waitingFor]).
         * send_mail too, for an event it sends as an invite.
         */
        val CALENDAR_TOOLS: Set<String> = setOf(
            "list_calendars", "list_events", "find_free_time", "create_event", "update_event", "delete_event",
            "list_tasks", "create_task", "complete_task", "send_mail",
        )

        /** Talon's own sends: each is put to the owner before it goes ([askingToSend]). */
        val SENDS: Set<String> = setOf("send_message", "send_mail")

        /** What the model reads of a send the owner declined or did not answer. */
        const val NOT_SENT = "The owner did not send it."

        /**
         * The reads that give other people's words, their messages and
         * mail: a locked phone asks for the fingerprint first
         * ([unlockingReads]). Not the calendar's, nor orrery's own record.
         */
        val PRIVATE_READS: Set<String> = setOf(
            "search_history", "read_conversation", "list_unread", "list_mail", "search_mail", "read_mail",
        )

        /** What the model reads, and the card shows, while the phone stays locked. */
        const val UNLOCK_TO_READ = "Unlock your phone to hear messages."

        /**
         * What a send would do, as the card asks it: a message or a mail,
         * to whom by [name], and what it says. Null where the call lacks
         * what the tool needs to send at all.
         */
        fun outgoing(tool: String, args: JsonObject, name: (String) -> String): CardProposal? {
            fun arg(k: String) = args[k].asText()
            return when (tool) {
                "send_message" -> {
                    val whom = arg("whom") ?: return null
                    val text = arg("text")?.takeIf { it.isNotBlank() } ?: return null
                    CardProposal("message", "Message to ${name(whom)}", to = name(whom), text = text.trim())
                }
                "send_mail" -> {
                    val to = arg("to").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() } ?: return null
                    val body = arg("body")?.takeIf { it.isNotBlank() } ?: return null
                    val names = to.joinToString { name(it) }
                    val subject = arg("subject")?.trim()?.takeIf { it.isNotEmpty() }
                    CardProposal("message", "Mail to $names" + (subject?.let { ": $it" } ?: ""), to = names, text = body.trim())
                }
                else -> null
            }
        }

        /** [all] as a dictation may use them: [TOOLS] only, and while [locked], only [LOCKED_WRITES] among the writes. */
        fun toolsFor(all: List<Tool>, locked: Boolean): List<Tool> =
            whileLocked(all.dedupToolNames().filter { it.spec.name in TOOLS }, locked)

        /** [tools] less the writes [LOCKED_WRITES] leaves out, while [locked]. */
        fun whileLocked(tools: List<Tool>, locked: Boolean): List<Tool> =
            if (!locked) tools else tools.filter { !it.write || it.spec.name in LOCKED_WRITES }

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
                // By the name the chat list gives, or queued for a slow ship.
                "send_message" -> result.takeIf { it.startsWith("Sent to ") || it.startsWith("Queued for ") }
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

/**
 * These tools with each send ([DictationAgent.SENDS]) put to the owner by
 * [ask] first, as the card shows it ([DictationAgent.outgoing]): it goes
 * only on true, and otherwise the model reads [DictationAgent.NOT_SENT].
 * A send missing whom or what is refused here, so no send runs unasked.
 */
fun List<Tool>.askingToSend(name: (String) -> String, ask: suspend (CardProposal) -> Boolean?): List<Tool> =
    map { t ->
        if (t.spec.name !in DictationAgent.SENDS) t
        else Tool(t.spec, t.write) { args ->
            val p = DictationAgent.outgoing(t.spec.name, args, name) ?: return@Tool "Error: say whom it goes to and what it says."
            if (ask(p) == true) t.execute(args) else DictationAgent.NOT_SENT
        }
    }

/**
 * These tools with each read of other people's words
 * ([DictationAgent.PRIVATE_READS]) waiting, while [locked], for [unlock]:
 * refused with [DictationAgent.UNLOCK_TO_READ] where the phone stays
 * locked, so nothing private is said or drawn. One refusal stands for
 * these tools' run while the phone stays locked, so it is asked once.
 */
fun List<Tool>.unlockingReads(locked: () -> Boolean, unlock: suspend () -> Boolean): List<Tool> {
    var refused = false
    return map { t ->
        if (t.spec.name !in DictationAgent.PRIVATE_READS) t
        else Tool(t.spec, t.write) { args ->
            if (locked() && (refused || !unlock())) {
                refused = true
                DictationAgent.UNLOCK_TO_READ
            } else {
                t.execute(args)
            }
        }
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
            return DictationOutcome.TranscribeFailed(if (tookTooLong(e)) SPEECH_TOO_SLOW else why(e), io.nisfeb.talon.util.errorDetailsOf(e))
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
        /** No Armillary key on this device yet: credit first. Where buying is not allowed, nothing points to it. */
        val NO_ARMILLARY: String get() =
            if (io.nisfeb.talon.ui.isArmillaryPurchaseSupported) "Top up Armillary under Settings, AI, to dictate."
            else "Connect Armillary under Settings, AI, to dictate."
        const val NO_SPEECH_TO_TEXT = "Your Armillary vendor offers no speech model yet."
        const val NO_MODEL = "Turn on the assistant under Settings, AI."
        const val NO_MODEL_FOR_QUESTIONS = "Questions need the assistant. Turn it on under Settings, AI."

        /**
         * What stops a dictation before it records, in words for the owner;
         * null when nothing does. With Orrery on, a note needs no model of
         * Talon's: only a question does, and it says so itself.
         */
        fun blocker(cfg: AiSettings.Config, hasShip: Boolean): String? = when {
            !hasShip -> NO_SHIP
            cfg.profile().armillary()?.apiKey.isNullOrBlank() -> NO_ARMILLARY
            hearing(cfg) == null -> NO_SPEECH_TO_TEXT
            !cfg.loopsCanRun() && !cfg.orreryOn() -> NO_MODEL
            else -> null
        }

        /** A transcription out of time ([ArmillaryTranscribe.SPOKEN_WAIT_MS]), said calmly. */
        const val SPEECH_TOO_SLOW = "The speech service took too long"

        /**
         * Whether [e] is a request that ran out of time. Only a
         * transcription's says [SPEECH_TOO_SLOW]: [why] serves the model's
         * calls too, and one of those is no speech service.
         */
        fun tookTooLong(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(5).any {
            it is io.ktor.client.plugins.HttpRequestTimeoutException || it is io.ktor.client.network.sockets.SocketTimeoutException
        }

        /** Where a dictation is heard: the Armillary vendor's transcription model ([armillaryHearing]), else nowhere. */
        fun hearing(cfg: AiSettings.Config): Hearing? = cfg.profile().armillaryHearing()

        /**
         * A failure as the owner is told it, in words: no network, a
         * service that said no, or what it said where that reads as a
         * sentence. Never an exception's name or a host; those are in
         * the details.
         */
        fun why(e: Throwable): String {
            val refused = Regex("""^transcription failed: (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)
            val status = (e as? ModelHttpError)?.status
            return when {
                // Armillary's refusals, as its other features say them.
                status == 402 -> if (io.nisfeb.talon.ui.isArmillaryPurchaseSupported) "$OUT_OF_CREDIT Top up under Settings, AI" else OUT_OF_CREDIT.trimEnd('.')
                status == 401 || status == 403 -> "Armillary turned this phone away: its lease is off or at its limit. Check Settings, AI"
                status == 413 -> "The recording was too long to send"
                status == 400 || status == 404 -> "The speech model is not on offer right now. Check Settings, AI"
                status == 429 || (status != null && status >= 500) -> "The speech service is busy. Try again in a moment"
                io.nisfeb.talon.util.isTransientNetworkError(e) -> "No network, or the service did not answer"
                refused != null -> "The transcription service said no ($refused)"
                else -> io.nisfeb.talon.util.readableReason(e.message)?.trimEnd('.') ?: "Something went wrong"
            }
        }

    }
}
