package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Instructed
import io.nisfeb.talon.orrery.OrreryAction

/** Where a dictation goes ([DictationFlow.route]). */
enum class DictationRoute {
    /** A question: Talon's agent looks it up and answers. */
    Ask,

    /** Anything else: orrery's model turns it into actions, which wait for the owner as orrery's policy says. */
    Tell,

    /** Too long to tell: orrery reads it as it reads a message. */
    File,
}

/**
 * A dictation with Orrery on: a question is answered, everything else is
 * orrery's. Orrery's model tells a command from a todo, so nothing it
 * proposes is approved here: what its policy leaves waiting is put to the
 * owner ([decide]) on the card they just spoke into, and goes ahead only
 * on their word. sneagan, 2026-10-16: "we also need interactive approval
 * immediately so that I can approve a dictated message in the car".
 */
class DictationFlow(
    /** Answer a question, looking things up only, after the [Earlier] exchange of a follow-up. */
    private val ask: suspend (question: String, earlier: List<Earlier>) -> String,
    /** Orrery's instruct route, without "do it". */
    private val instruct: suspend (String) -> Instructed,
    /** Orrery's read route. */
    private val hand: suspend (text: String, title: String) -> Unit,
    /** The owner's word on one action: approved, or dismissed. */
    private val answer: suspend (id: String, approve: Boolean) -> Unit,
    /** The owner's word on what waits, shown with orrery's [reply]: true, false, or null for none in time. */
    private val decide: suspend (reply: String, waiting: List<OrreryAction>) -> Boolean?,
    /** An answer made on the phone with no model ([CalendarQuestion]), or null where there is none. */
    private val quick: suspend (text: String, earlier: List<Earlier>) -> String? = { _, _ -> null },
) {
    /** What was said and answered before, which a follow-up goes on from. */
    data class Earlier(val words: String, val line: String)

    /** The line for the owner, and where the words went. */
    data class Reply(val line: String, val route: DictationRoute)

    /**
     * [text] handled, where it is a follow-up, after [earlier] and in the
     * same place: a question's follow-up is a question, and anything else
     * goes to orrery with what was said before it. Throws when orrery
     * could not take it, so the words are kept.
     */
    suspend fun handle(text: String, earlier: List<Earlier> = emptyList(), asked: Boolean = false): Reply {
        // Before the route: "what's on today" written with no question
        // mark is still a question, and no business of orrery's.
        quick(text, earlier)?.let { return Reply(it, DictationRoute.Ask) }
        val to = if (asked && earlier.isNotEmpty()) DictationRoute.Ask else route(text)
        val line = when (to) {
            DictationRoute.Ask -> ask(text, earlier)
            DictationRoute.File -> title(text).let { hand(text, it); "Handed to orrery to read: $it." }
            DictationRoute.Tell -> tell(withEarlier(text, earlier))
        }
        return Reply(line, to)
    }

    private suspend fun tell(text: String): String {
        val said = instruct(text)
        // How the client says a refusal (no model key, the day's limit,
        // no answer): nothing filed, and only a note. Not news to file.
        if (said.actions.isEmpty() && said.reply.isBlank()) error(said.note.ifBlank { "Orrery answered nothing" })
        val reply = said.reply.ifBlank { "Orrery took it." }
        val waiting = said.actions.filter { it.status == "proposed" }
        if (waiting.isEmpty()) return reply
        return when (decide(reply, waiting)) {
            true -> {
                waiting.forEach { answer(it.id, true) }
                "Done. $reply"
            }
            false -> {
                waiting.forEach { answer(it.id, false) }
                "Dropped. Nothing was done."
            }
            // Not an error: it waits where every proposal waits.
            null -> "$reply Waiting for you under Actions."
        }
    }

    companion object {
        /** What instruct takes; more is read instead ([OrreryApi.instruct] clips at it). */
        const val INSTRUCT_BYTES = 2000

        /** How a request phrased as a question starts: "can you text Andrea …?" is something to do. */
        private val REQUEST = Regex("""^(please|can you|could you|would you|will you|would you mind|can i get you to)\b""")

        /** A request that is a question after all: "could you tell me what's on tomorrow?" */
        private val LOOKUP = Regex("""^(please |can you |could you |would you |will you )?(tell me|show me|look up|check|find out|let me know)\b""")

        /**
         * Where [text] goes. A question is one whisper wrote with a
         * question mark, the punctuation being the signal it is surest
         * of; a first word alone misleads ("Have Andrea pick up Linus",
         * "When Linus is done, remind me ..."). A polite request is not
         * a question, unless it asks to be told something.
         */
        fun route(text: String): DictationRoute {
            val t = text.trim()
            val lower = t.lowercase()
            return when {
                LOOKUP.containsMatchIn(lower) -> DictationRoute.Ask
                t.endsWith("?") && !REQUEST.containsMatchIn(lower) -> DictationRoute.Ask
                t.encodeToByteArray().size > INSTRUCT_BYTES -> DictationRoute.File
                else -> DictationRoute.Tell
            }
        }

        /**
         * [text] with the exchange before it, for orrery, which keeps no
         * conversation: "Should I send you 'Locked test'?" answered "yes,
         * that" means nothing alone. The oldest turns go first when it
         * would not fit what instruct takes.
         */
        fun withEarlier(text: String, earlier: List<Earlier>): String {
            var turns = earlier
            while (true) {
                if (turns.isEmpty()) return text
                val s = buildString {
                    turns.forEach { append("Earlier I said: ").append(it.words).append("\nYou answered: ").append(it.line).append('\n') }
                    append("Now I say: ").append(text)
                }
                if (s.encodeToByteArray().size <= INSTRUCT_BYTES) return s
                turns = turns.drop(1)
            }
        }

        /** A title for a long note: its first words. */
        fun title(text: String): String {
            val words = text.trim().split(Regex("""\s+"""))
            return words.take(8).joinToString(" ").trimEnd('.', ',', ';', ':') + if (words.size > 8) "…" else ""
        }

        private val YES = setOf("send", "send it", "yes", "yeah", "yep", "yes send", "yes send it", "do it", "go", "go ahead", "approve", "approved", "ok", "okay", "sure", "confirm")
        private val NO = setOf("cancel", "no", "nope", "don't", "dont", "don't send", "dont send", "do not send", "stop", "never mind", "nevermind", "dismiss", "no thanks", "no thank you")

        /**
         * The owner's spoken answer: true for send, false for cancel, null
         * for anything else, which leaves the buttons to decide. Strict:
         * whisper hears "Thank you." or "You." in a near-silent clip, and
         * no stray word may send a message.
         */
        fun heard(text: String): Boolean? {
            val said = text.lowercase().replace('\u2019', '\'').replace(Regex("""[^a-z' ]"""), " ").replace(Regex("""\s+"""), " ").trim()
                .removeSuffix(" please").removeSuffix(" thanks").trim()
            return when (said) {
                in YES -> true
                in NO -> false
                else -> null
            }
        }

        /** What waits, as the card says it: who a message goes to and what it says. */
        fun describe(a: OrreryAction): String {
            val text = (a.payload["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            return when {
                a.kind == "message" && text.isNotEmpty() -> "${a.title}: “$text”"
                else -> a.title
            }
        }

        /** Kinds a locked phone may approve with no fingerprint: none that reach another person or undo what is known. */
        val LOCKED_OK: Set<String> = setOf("task", "calendar", "fact", "note")

        /** Whether approving [waiting] on a locked phone needs the owner's fingerprint first. */
        fun needsUnlock(waiting: List<OrreryAction>): Boolean = waiting.any { it.kind !in LOCKED_OK }
    }
}
