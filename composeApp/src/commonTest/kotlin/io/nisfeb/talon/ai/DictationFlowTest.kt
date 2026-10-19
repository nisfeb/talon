package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.Instructed
import io.nisfeb.talon.orrery.OrreryAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationFlowTest {

    @Test
    fun `a question is what whisper wrote with a question mark`() {
        assertEquals(DictationRoute.Ask, DictationFlow.route("What's on my calendar today?"))
        assertEquals(DictationRoute.Ask, DictationFlow.route("Is Linus's game at two?"))
        assertEquals(DictationRoute.Tell, DictationFlow.route("Text Andrea I'm running late."))
        assertEquals(DictationRoute.Tell, DictationFlow.route("Remind me to buy milk"))
        // A first word alone is not a question.
        assertEquals(DictationRoute.Tell, DictationFlow.route("Have Andrea pick up Linus."))
        assertEquals(DictationRoute.Tell, DictationFlow.route("When Linus is done, remind me to call the coach."))
    }

    @Test
    fun `a polite request is something to do, unless it asks to be told`() {
        assertEquals(DictationRoute.Tell, DictationFlow.route("Can you text Andrea I'm running late?"))
        assertEquals(DictationRoute.Tell, DictationFlow.route("Please add milk to my list?"))
        assertEquals(DictationRoute.Ask, DictationFlow.route("Could you tell me what's on tomorrow?"))
        assertEquals(DictationRoute.Ask, DictationFlow.route("Tell me what's on tomorrow."))
    }

    // sneagan, 2026-10-19: "take me back to the hotel" and "navigate me to
    // sagrada familia" went to orrery, which cannot open the phone's maps.
    @Test
    fun `asking to be taken somewhere is the assistant's, which opens maps`() {
        listOf(
            "Take me back to the hotel", "take me home.", "Navigate me to Sagrada Familia", "navigate to the airport",
            "Can you take me to the hotel?", "Please get me back to the hotel", "Directions to Park Güell",
            "Give me directions home", "How do I get back to the hotel", "how can we get to the beach", "Walk me there",
        ).forEach { assertEquals(DictationRoute.Ask, DictationFlow.route(it), it) }
        listOf(
            "Take Linus to the hotel at three", "Remind me to take my passport", "Take me off the carpool list",
            "Navigate the contract with Andrea", "Have Andrea drive me to the airport",
        ).forEach { assertEquals(DictationRoute.Tell, DictationFlow.route(it), it) }
        assertEquals(DictationRoute.Ask, DictationFlow.route("Read me my unread messages"), "not a todo for orrery: something to hear")
        assertEquals(DictationRoute.Tell, DictationFlow.route("Reading list: add Dune."), "only read me")
        assertEquals(DictationRoute.Tell, DictationFlow.route("Check off the milk."), "a tick, not a lookup")
        assertEquals(DictationRoute.Tell, DictationFlow.route("Check the milk off"))
        assertEquals(DictationRoute.Tell, DictationFlow.route("Please let me know when Andrea lands."), "a reminder, not a lookup")
        assertEquals(DictationRoute.Ask, DictationFlow.route("Check my calendar for Friday"))
        assertEquals(DictationRoute.Ask, DictationFlow.route("Let me know what's on tomorrow"))
        assertEquals(DictationRoute.Ask, DictationFlow.route("Can you check if the store is open"))
    }

    @Test
    fun `more than instruct takes is read instead`() {
        val long = "Notes from the call. " + "We talked about the plan for the spring. ".repeat(60)
        assertTrue(long.encodeToByteArray().size > DictationFlow.INSTRUCT_BYTES)
        assertEquals(DictationRoute.File, DictationFlow.route(long))
        assertEquals("Notes from the call. We talked about the…", DictationFlow.title(long))
        assertEquals("Buy milk", DictationFlow.title("Buy milk."))
    }

    @Test
    fun `only a plain send or cancel answers, never a stray word`() {
        assertEquals(true, DictationFlow.heard("Send."))
        assertEquals(true, DictationFlow.heard("Yes, send it."))
        assertEquals(true, DictationFlow.heard("Do it please"))
        assertEquals(false, DictationFlow.heard("Cancel."))
        assertEquals(false, DictationFlow.heard("Don’t send"))
        assertEquals(false, DictationFlow.heard("No."))
        // What whisper hears in a near-silent clip, and anything longer.
        assertNull(DictationFlow.heard("Thank you."))
        assertNull(DictationFlow.heard("You"))
        assertNull(DictationFlow.heard(""))
        assertNull(DictationFlow.heard("Yes but change it to say I'll be ten minutes late"))
        assertNull(DictationFlow.heard("send it to Dana instead"))
    }

    private fun action(id: String, kind: String, status: String = "proposed", text: String? = null) = OrreryAction(
        id = id, kind = kind, title = "Text Andrea", about = emptyList(), due = null, status = status, by = "owner",
        payload = JsonObject(if (text != null) mapOf("text" to JsonPrimitive(text), "to" to JsonPrimitive("person/andrea")) else emptyMap()),
    )

    @Test
    fun `a message waiting says to whom and what`() {
        assertEquals("Text Andrea: “I'm running late”", DictationFlow.describe(action("1", "message", text = "I'm running late")))
        assertEquals("Text Andrea", DictationFlow.describe(action("2", "task")))
    }

    @Test
    fun `a locked phone needs the fingerprint for anything that reaches someone or undoes what is known`() {
        assertTrue(DictationFlow.needsUnlock(listOf(action("1", "message"))))
        assertTrue(DictationFlow.needsUnlock(listOf(action("1", "task"), action("2", "merge"))))
        assertFalse(DictationFlow.needsUnlock(listOf(action("1", "task"), action("2", "calendar"), action("3", "fact"))))
    }

    private class Ship(
        val instructed: Instructed = Instructed(),
        val word: Boolean? = true,
    ) {
        val answered = mutableListOf<Pair<String, Boolean>>()
        val asked = mutableListOf<List<String>>()
        val handed = mutableListOf<String>()
        val questions = mutableListOf<Pair<String, List<DictationFlow.Earlier>>>()
        val told = mutableListOf<String>()
        var quick: String? = null
        fun flow() = DictationFlow(
            ask = { q, earlier -> questions += q to earlier; "You have ballet at 4:45." },
            instruct = { told += it; instructed },
            hand = { t, _ -> handed += t },
            answer = { id, ok -> answered += id to ok },
            decide = { _, waiting -> asked += waiting.map { it.id }; word },
            quick = { _, _ -> quick },
        )
    }

    @Test
    fun `a question is answered, and orrery is not asked`() = runBlocking {
        val ship = Ship()
        assertEquals(DictationFlow.Reply("You have ballet at 4:45.", DictationRoute.Ask), ship.flow().handle("What's on today?"))
        assertEquals(listOf("What's on today?" to emptyList<DictationFlow.Earlier>()), ship.questions)
        assertTrue(ship.asked.isEmpty() && ship.answered.isEmpty())
    }

    @Test
    fun `being taken somewhere goes to the assistant, and orrery is not told`() = runBlocking {
        val ship = Ship()
        assertEquals(DictationRoute.Ask, ship.flow().handle("Take me back to the hotel").route)
        assertEquals(DictationRoute.Ask, ship.flow().handle("Navigate me to Sagrada Familia").route)
        assertEquals(listOf("Take me back to the hotel", "Navigate me to Sagrada Familia"), ship.questions.map { it.first })
        assertTrue(ship.told.isEmpty(), "${ship.told}")
    }

    @Test
    fun `a message orrery holds goes out only on the owner's word`() = runBlocking {
        val ship = Ship(Instructed(reply = "Text Andrea that you're running late.", actions = listOf(action("m1", "message", text = "Running late"))))
        assertEquals("Done. Text Andrea that you're running late.", ship.flow().handle("Text Andrea I'm running late.").line)
        assertEquals(listOf(listOf("m1")), ship.asked)
        assertEquals(listOf("m1" to true), ship.answered)
    }

    @Test
    fun `said no, it is dismissed and nothing is done`() = runBlocking {
        val ship = Ship(Instructed(reply = "Text Andrea.", actions = listOf(action("m1", "message"))), word = false)
        assertEquals("Dropped. Nothing was done.", ship.flow().handle("Text Andrea.").line)
        assertEquals(listOf("m1" to false), ship.answered)
    }

    @Test
    fun `no word in time leaves it waiting, a line and not a failure`() = runBlocking {
        val ship = Ship(Instructed(reply = "Text Andrea.", actions = listOf(action("m1", "message"))), word = null)
        assertEquals("Text Andrea. Waiting for you under Actions.", ship.flow().handle("Text Andrea.").line)
        assertTrue(ship.answered.isEmpty(), "it was answered with no word from the owner")
    }

    @Test
    fun `what the policy approved itself is not put to the owner`() = runBlocking {
        val ship = Ship(Instructed(reply = "Added a todo to buy milk.", actions = listOf(action("t1", "task", status = "approved"))))
        assertEquals("Added a todo to buy milk.", ship.flow().handle("Remind me to buy milk.").line)
        assertTrue(ship.asked.isEmpty() && ship.answered.isEmpty())
    }

    @Test
    fun `an unclear instruction is orrery's question back, with nothing to approve`() = runBlocking {
        val ship = Ship(Instructed(reply = "Which Andrea, Andrea Egan or Andrea Ruiz?"))
        assertEquals("Which Andrea, Andrea Egan or Andrea Ruiz?", ship.flow().handle("Text Andrea.").line)
        assertTrue(ship.asked.isEmpty())
    }

    @Test
    fun `orrery refusing is a failure, so the words are kept`() = runBlocking {
        val ship = Ship(Instructed(note = "Orrery has made all of today's model calls. Try again tomorrow."))
        val e = assertFailsWith<IllegalStateException> { ship.flow().handle("Text Andrea.") }
        assertEquals("Orrery has made all of today's model calls. Try again tomorrow.", e.message)
    }

    @Test
    fun `a long note is read, not told`() = runBlocking {
        val ship = Ship()
        val long = "Notes from the call. " + "We talked about the plan for the spring. ".repeat(60)
        assertEquals(DictationRoute.File, ship.flow().handle(long).route)
        assertTrue(ship.flow().handle(long).line.startsWith("Handed to orrery to read: Notes from the call."))
        assertEquals(listOf(long, long), ship.handed)
    }

    private val asked = listOf(DictationFlow.Earlier("What's on today?", "Ballet at 4:45."))
    private val orreryAsked = listOf(DictationFlow.Earlier("Text me a locked test.", "Should I send you a chat message that says \"Locked test\"?"))

    @Test
    fun `a question's follow-up is a question, with what came before`() = runBlocking {
        val ship = Ship()
        val reply = ship.flow().handle("And tomorrow.", asked, asked = true)
        assertEquals(DictationRoute.Ask, reply.route, "\"and tomorrow\" after a question went to orrery")
        assertEquals(listOf("And tomorrow." to asked), ship.questions)
    }

    @Test
    fun `answering orrery's question tells orrery what it asked`() = runBlocking {
        val ship = Ship(Instructed(reply = "Text yourself \"Locked test\".", actions = listOf(action("m1", "message"))))
        ship.flow().handle("Yes, send that.", orreryAsked, asked = false)
        val said = ship.told.single()
        assertTrue("Earlier I said: Text me a locked test." in said, said)
        assertTrue("You answered: Should I send you" in said, said)
        assertTrue(said.endsWith("Now I say: Yes, send that."), said)
    }

    @Test
    fun `a follow-up too long for instruct drops the oldest turns, never the new words`() {
        val many = List(10) { DictationFlow.Earlier("Turn $it " + "x".repeat(150), "Answer $it " + "y".repeat(150)) }
        val said = DictationFlow.withEarlier("Now this.", many)
        assertTrue(said.encodeToByteArray().size <= DictationFlow.INSTRUCT_BYTES)
        assertTrue(said.endsWith("Now I say: Now this."))
        assertTrue("Turn 9" in said && "Turn 0" !in said)
        assertEquals("Alone.", DictationFlow.withEarlier("Alone.", emptyList()))
    }

    /** A decision model that judges by [calendar]: the questions it calls calendar-only, for today. */
    private class Judge(val calendar: Set<String>) : io.nisfeb.talon.orrery.Decider {
        override suspend fun ask(state: JsonObject, questions: JsonObject): io.nisfeb.talon.orrery.Decision {
            val q = (state["question"] as JsonPrimitive).content
            return io.nisfeb.talon.orrery.Decision(kotlinx.serialization.json.buildJsonObject {
                put("calendar_only", kotlinx.serialization.json.buildJsonObject { put("noul", JsonPrimitive(if (q in calendar) 0.96 else 0.04)) })
                put("days", kotlinx.serialization.json.buildJsonObject { put("choice", JsonPrimitive(if (q in calendar) "today" else "tomorrow")) })
            })
        }
    }

    /** A flow wired as the phone wires it (Loops.calendarAnswer), judged by [judge]. */
    private fun judged(judge: Judge, questions: MutableList<Pair<String, List<DictationFlow.Earlier>>>, told: MutableList<String>) = DictationFlow(
        ask = { q, earlier -> questions += q to earlier; "Answered." },
        instruct = { told += it; Instructed(reply = "Noted.") },
        hand = { _, _ -> error("not filed") },
        answer = { _, _ -> },
        decide = { _, _ -> null },
        quick = { t, earlier -> CalendarQuestion.decide(judge, t, earlier, kotlinx.datetime.LocalDate(2026, 10, 16))?.let { "The day's events." } },
    )

    @Test
    fun `what is left before a trip goes to the model, and so do its follow-ups`() = runBlocking {
        val questions = mutableListOf<Pair<String, List<DictationFlow.Earlier>>>()
        val told = mutableListOf<String>()
        val flow = judged(Judge(setOf("What's on today")), questions, told)
        // sneagan's conversation, 2026-10-16: the first and the last were answered with the day's events.
        var earlier = emptyList<DictationFlow.Earlier>()
        for (q in listOf("What do I have left to do before Barcelona?", "When do I arrive in Barcelona?", "What do I have to get done before I go")) {
            val r = flow.handle(q, earlier, asked = earlier.isNotEmpty())
            assertEquals(DictationFlow.Reply("Answered.", DictationRoute.Ask), r, q)
            earlier = earlier + DictationFlow.Earlier(q, r.line)
        }
        assertEquals(listOf(0, 1, 2), questions.map { it.second.size }, "each goes on from what came before it")
        // What the decision model calls calendar-only is answered on the phone, question mark or not.
        assertEquals(DictationFlow.Reply("The day's events.", DictationRoute.Ask), flow.handle("What's on today"))
        assertTrue(told.isEmpty())
    }

    @Test
    fun `a reminder with a day in it goes to orrery, not the calendar answer`() = runBlocking {
        val questions = mutableListOf<Pair<String, List<DictationFlow.Earlier>>>()
        val told = mutableListOf<String>()
        val r = judged(Judge(emptySet()), questions, told).handle("Remind me about the dentist tomorrow")
        assertEquals(DictationFlow.Reply("Noted.", DictationRoute.Tell), r)
        assertEquals(listOf("Remind me about the dentist tomorrow"), told)
        assertTrue(questions.isEmpty())
    }

    @Test
    fun `what the phone answers itself goes to neither the model nor orrery`() = runBlocking {
        val ship = Ship().apply { quick = "Today you have 3 events." }
        // Written with no question mark, which alone would have gone to orrery.
        assertEquals(DictationFlow.Reply("Today you have 3 events.", DictationRoute.Ask), ship.flow().handle("What's on today"))
        assertTrue(ship.questions.isEmpty() && ship.told.isEmpty())
    }
}
