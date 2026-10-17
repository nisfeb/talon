package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Brief
import io.nisfeb.talon.ui.icons.ASSISTANT_NAME
import io.nisfeb.talon.urbit.asText
import kotlinx.coroutines.CancellationException

/**
 * Archie's conversation on the dictation card: what has been said and
 * answered, and what comes after each thing the owner says. The card's
 * service only records, transcribes and speaks; every decision is here,
 * so it is tested once, without a phone. sneagan, 2026-10-17: "from the
 * conversation can branch many ways and should retain context and
 * read/write orrery as needed."
 *
 * One model and no routing: inside a conversation the model decides when
 * to read and when to hand orrery something, unlike a lone dictation
 * ([DictationFlow.route]). Nothing reaches another person without the
 * owner's word: Talon's own send tools are not offered, and orrery's
 * proposals, a message among them, are put to the owner by [tell] first.
 *
 * See docs/superpowers/specs/2026-10-17-voice-briefing-design.md.
 */
class VoiceConversation(
    private val completer: AgentLoop.Completer,
    tools: List<Tool>,
    /** The owner's words to orrery, what it proposes put to them, and the line to say ([DictationFlow.tell]). Null with Orrery off. */
    tell: (suspend (String) -> String)?,
    /** Today's brief from orrery, null for none today; throws where orrery could not be asked. */
    private val brief: suspend () -> Brief?,
    private val maxSteps: Int = 8,
) {
    /** What comes after a turn. */
    sealed interface Next {
        /**
         * Say [line] (its highlights aloud), show [shown], read [inFull] out
         * a paragraph at a time, then listen again.
         */
        data class Say(val line: String, val inFull: List<String> = emptyList(), val shown: String = line) : Next

        /** Say [line], and listen no more. */
        data class End(val line: String) : Next
    }

    private val turns = mutableListOf<DictationFlow.Earlier>()

    /** Each exchange so far, oldest first. */
    val said: List<DictationFlow.Earlier> get() = turns.toList()

    /** What was last read out in full, kept so "approve the second one" means something. */
    private var lastRead: String? = null

    /** What this turn asked to have read out, set by the brief and unread tools, and the line said before it. */
    private var reading: String? = null
    private var readingLine = READING

    private val offered: List<Tool> =
        tools.filterNot { it.spec.name in NOT_OFFERED }.map { if (it.spec.name == UNREAD_TOOL) readsOut(it) else it } +
            listOfNotNull(tell?.let(::instructTool), briefTool())

    /** The tools a turn may call, for a test and the log. */
    val toolNames: List<String> get() = offered.map { it.spec.name }

    /**
     * The rest of the opener, written from [digest] ([Briefing.openerDigest])
     * while [counts] is said. Where the model fails, the owner is asked
     * what they would like: the counts alone still open the conversation.
     */
    suspend fun opener(counts: String, digest: String): String {
        val rest = try {
            AgentLoop(completer, emptyList(), Briefing.openerPrompt, 2).run(digest, confirm = { _, _ -> false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            WHAT_NEXT
        }
        turns += DictationFlow.Earlier(OPENED, "$counts $rest".trim())
        return rest
    }

    /** What follows [words]. Throws where the model could not be asked; the owner can say it again. */
    suspend fun next(words: String): Next {
        if (Briefing.ends(words)) return Next.End(GOODBYE)
        if (turns.size >= MAX_TURNS) return Next.End(TOO_LONG)
        reading = null
        val prior = turns.takeLast(MAX_CONTEXT).flatMap { listOf(AgentMessage.User(it.words), AgentMessage.Assistant(it.line, emptyList())) }
        val system = prompt + (lastRead?.let { "\n\nWhat you read out to the owner earlier:\n$it" }.orEmpty())
        val line = AgentLoop(completer, offered, system, maxSteps).run(
            question = words,
            priorTurns = prior,
            // The conversation's writes go ahead, as a dictation's do; what
            // reaches another person waits inside [tell] for the owner.
            confirm = { _, _ -> true },
            // What is read out needs no model call to say so.
            finish = { turn -> if (reading != null && turn.all { it.first.name in READS_OUT }) readingLine else DictationAgent.plainly(turn) },
        )
        turns += DictationFlow.Earlier(words, line)
        val read = reading ?: return Next.Say(line)
        lastRead = read
        return Next.Say(line, Briefing.spokenParagraphs(read), shown = line + "\n\n" + read)
    }

    /**
     * [unread] as it is, read out where the owner asked to hear it: their
     * messages word for word, but not the ids the model needs after.
     */
    private fun readsOut(unread: Tool) = Tool(unread.spec, unread.write) { args ->
        unread.execute(args).also { out ->
            if (args["aloud"].asText() == "true" && Briefing.UNREAD_IDS in out) {
                reading = out.substringBefore("\n\n" + Briefing.UNREAD_IDS)
                readingLine = UNREAD_READING
            }
        }
    }

    private fun instructTool(tell: suspend (String) -> String) = Tool(
        spec = ToolSpec(
            "orrery_instruct",
            "Tell orrery something in the owner's words for its model to act on: a message to send someone, a fact that is wrong or new, two bodies that are one person, a standing preference, or something to do. What it proposes is put to the owner on the card and goes ahead only on their word; the result says what they decided. Not for an event or appointment: that is create_event alone.",
            toolSchema("text" to ("string" to "The owner's words, with who and what, up to 2000 bytes."), required = listOf("text")),
        ),
        write = true,
    ) { args ->
        val text = args["text"].asText()?.takeIf { it.isNotBlank() } ?: return@Tool "Error: text is required."
        try {
            tell(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Orrery did not take it: ${e.message}"
        }
    }

    private fun briefTool() = Tool(
        spec = ToolSpec(
            BRIEF_TOOL,
            "Today's brief from orrery: the day's calendar, the actions waiting for the owner under tags such as A1, when to leave, and orrery's few lines. With aloud true it is read out to the owner word for word as it is, so say only a short line after it.",
            toolSchema("aloud" to ("boolean" to "true when the owner asked to hear the brief"), required = emptyList()),
        ),
        write = false,
    ) { args ->
        val b = try {
            brief()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@Tool "Could not read the brief from orrery: ${e.message}"
        }
        if (b == null) return@Tool "There is no brief for today yet."
        if (args["aloud"].asText() == "true") {
            reading = b.text
            readingLine = READING
        }
        b.text
    }

    companion object {
        /** A conversation's length at most, in exchanges. */
        const val MAX_TURNS = 20

        /** The exchanges a turn is given, besides what was read out. */
        const val MAX_CONTEXT = 8

        const val BRIEF_TOOL = "read_brief"
        const val UNREAD_TOOL = "list_unread"

        /** The tools whose text is read out as it is. */
        private val READS_OUT = setOf(BRIEF_TOOL, UNREAD_TOOL)

        /** What the history calls the opener's question. */
        const val OPENED = "Opened the briefing"

        const val GOODBYE = "Okay. Talk soon."
        const val TOO_LONG = "That's a lot for one go. Tap me again to start fresh."
        const val READING = "Here's today's brief."
        const val UNREAD_READING = "Here are your unread messages."
        const val WHAT_NEXT = "What would you like?"

        /**
         * Talon's own sends, which reach another person with no word from
         * the owner, and the plain orrery instruct, which [tell] replaces.
         */
        val NOT_OFFERED: Set<String> = setOf("send_message", "send_mail", "orrery_instruct")

        val prompt: String = DATA_NOT_INSTRUCTIONS + "\n\n" + """
            You are $ASSISTANT_NAME, the owner's assistant, in a spoken
            conversation on their phone. Speech-to-text writes down what they
            say, so expect the odd misheard word. Keep the conversation's
            context: "that", "the second one" or "move it" mean what was just
            said. Use the tools listed for you, and nothing else.

            Your answer is read aloud. Begin with one short sentence of the
            highlights, under 25 words, with no lists, links, markdown or @p.
            Then, after a blank line, a few short lines of detail for the
            screen, when there are any.

            Orrery is the owner's own record of their life: trips, plans,
            people, places, open situations and the actions still to do.
            Read it for anything beyond what is on the calendar. To change or
            add what orrery holds, or to send someone a message, use
            orrery_instruct with the owner's words; it asks the owner before
            anything goes ahead. An event goes
            on the calendar with create_event, and a to-do with create_task.
            When they want today's brief read, call read_brief with aloud
            true; it is read to them as it is. Their unread messages are
            list_unread's, read to them the same way with aloud true.
        """.trimIndent()
    }
}
