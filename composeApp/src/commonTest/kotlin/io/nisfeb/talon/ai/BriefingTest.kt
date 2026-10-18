package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Brief
import io.nisfeb.talon.orrery.Decider
import io.nisfeb.talon.orrery.Decision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefingTest {
    @Test
    fun `the counts are said as sneagan put them, and only what was read`() {
        assertEquals("You have 2 new emails and 32 chat messages, 2 of them questions for you.", Briefing.countsLine(2, 32, 2))
        assertEquals("You have 1 new email and 1 chat message, 1 of them a question for you.", Briefing.countsLine(1, 1, 1))
        assertEquals("You have 3 chat messages.", Briefing.countsLine(null, 3, 0), "an inbox not listed is not \"no new mail\"")
        assertEquals("You have 3 chat messages, and no new mail.", Briefing.countsLine(0, 3, null))
        assertEquals("You have 2 new emails, and no new messages.", Briefing.countsLine(2, 0, 0))
        assertEquals("You have 4 chat messages, 4 of them questions for you.", Briefing.countsLine(null, 4, 9), "never more questions than messages")
        assertEquals("No new mail or messages.", Briefing.countsLine(0, 0, null))
        assertEquals("No new messages.", Briefing.countsLine(null, 0, null))
        assertEquals("No new mail.", Briefing.countsLine(0, null, null))
        assertEquals("", Briefing.countsLine(null, null, null), "nothing known, nothing said: the model's part opens it")
        // Mail that did not answer in time is said, not left out.
        assertEquals("You have 3 chat messages. Your mail has not answered yet.", Briefing.countsLine(null, 3, 0, mailLate = true))
        assertEquals("Your mail has not answered yet.", Briefing.countsLine(null, null, null, mailLate = true))
        assertEquals("You have 2 new emails, and no new messages.", Briefing.countsLine(2, 0, 0, mailLate = true), "a count is never late")
    }

    @Test
    fun `the title, and how soon`() {
        assertEquals("Archie, Saturday 17 October", Briefing.title(LocalDate(2026, 10, 17)))
        assertEquals("in a minute", Briefing.soon(40_000))
        assertEquals("in about 2 minutes", Briefing.soon(2 * 60_000L))
        assertEquals("in about 25 minutes", Briefing.soon(25 * 60_000L))
        assertEquals("in about 89 minutes", Briefing.soon(89 * 60_000L))
        assertEquals("in about 2 hours", Briefing.soon(90 * 60_000L))
        assertEquals("in about 2 hours", Briefing.soon(125 * 60_000L))
    }

    @Test
    fun `only the whole of what was said ends it`() {
        listOf("That's all.", "Thanks, Archie!", "No thanks", "OK, thanks.", "Thank you", "Goodbye", "I'm done", "no", "Stop.").forEach {
            assertTrue(Briefing.ends(it), it)
        }
        listOf("Thanks, what's my first meeting?", "Read me the brief", "No, read me the brief", "Stop the drop-off reminder", "").forEach {
            assertFalse(Briefing.ends(it), it)
        }
    }

    @Test
    fun `the brief is read a paragraph at a time, tags said plainly, and only today's`() {
        val text = "Friday 16 October\n\n[A1] Call the plumber\n[A2] Book the vet\n\n\n**Notes**: Andrea is away."
        assertEquals(listOf("Friday 16 October", "A1 Call the plumber A2 Book the vet", "Notes: Andrea is away."), Briefing.spokenParagraphs(text))
        val brief = Brief("2026-10-16", "2026-10-16T11:00:00Z", true, mapOf("A1" to "a-1"), text)
        assertEquals(brief, Briefing.todays(brief, LocalDate(2026, 10, 16)))
        assertNull(Briefing.todays(brief, LocalDate(2026, 10, 17)), "yesterday's is not today's")
        assertNull(Briefing.todays(null, LocalDate(2026, 10, 16)))
        val digest = Briefing.openerDigest("It is 7:40 AM.", "Core Sync at 9:00 AM, in about 1 hours", brief)
        assertTrue("First event today: Core Sync" in digest && "Andrea is away" in digest, digest)
        assertTrue("No more events today." in Briefing.openerDigest("now", null, null) && "No brief today." in Briefing.openerDigest("now", null, null))
    }

    /** Answers each question with [p]; keeps what it was asked. */
    private class Judge(val p: List<Double>, val fails: Throwable? = null) : Decider {
        val asked = mutableListOf<Pair<JsonObject, JsonObject>>()
        override suspend fun ask(state: JsonObject, questions: JsonObject): Decision {
            asked += state to questions
            fails?.let { throw it }
            return Decision(buildJsonObject { p.forEachIndexed { i, v -> putJsonObject("q$i") { put("noul", v) } } })
        }
    }

    private val said = listOf(
        Briefing.Said("~bus", "~bus", "can you grab milk on the way?"),
        Briefing.Said("~nec", "chat/~nec/general", "~zod deploy went fine"),
        Briefing.Said("~bus", "~bus", "what time is pickup?"),
    )

    @Test
    fun `the decision model counts the questions, one named question per message`() = runBlocking {
        val judge = Judge(listOf(0.9, 0.2, 0.7))
        assertEquals(2, Briefing.questions(judge, "zod", said))
        val (state, questions) = judge.asked.single()
        assertEquals(
            buildJsonObject {
                put("owner", "~zod")
                putJsonArray("messages") {
                    add(buildJsonObject { put("n", 0); put("from", "~bus"); put("in", "~bus"); put("text", "can you grab milk on the way?") })
                    add(buildJsonObject { put("n", 1); put("from", "~nec"); put("in", "chat/~nec/general"); put("text", "~zod deploy went fine") })
                    add(buildJsonObject { put("n", 2); put("from", "~bus"); put("in", "~bus"); put("text", "what time is pickup?") })
                }
            },
            state,
        )
        assertEquals(listOf("q0", "q1", "q2"), questions.keys.toList())
        assertTrue(questions.values.all { (it as JsonObject)["type"] == JsonPrimitive("noul") })
        assertTrue("n=2" in ((questions["q2"] as JsonObject)["instructions"] as JsonPrimitive).content, "each names its own message")
    }

    @Test
    fun `no messages asks nothing, and no answer is no count`() = runBlocking {
        val judge = Judge(emptyList())
        assertEquals(0, Briefing.questions(judge, "~zod", emptyList()))
        assertTrue(judge.asked.isEmpty())
        assertNull(Briefing.questions(Judge(emptyList(), fails = IllegalStateException("decision model answered 402")), "~zod", said))
        assertEquals(1, Briefing.questions(Judge(listOf(0.5)), "~zod", said.take(1)), "an even chance counts")
        assertFailsWith<CancellationException> { Briefing.questions(Judge(emptyList(), fails = CancellationException("left")), "~zod", said) }
        Unit
    }
}
