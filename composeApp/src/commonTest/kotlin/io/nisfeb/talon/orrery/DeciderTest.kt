package io.nisfeb.talon.orrery

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Answers canned decisions and remembers what it was asked; no network. */
private class FakeDecider(private val answer: (JsonObject) -> JsonObject) : Decider {
    val asked = mutableListOf<Pair<JsonObject, JsonObject>>()

    override suspend fun ask(state: JsonObject, questions: JsonObject): Decision {
        asked += state to questions
        return Decision(answer(questions), inputTokens = 640, costUsd = 0.0000269)
    }
}

private class DownDecider : Decider {
    var asked = 0

    override suspend fun ask(state: JsonObject, questions: JsonObject): Decision {
        asked++
        error("decision model unreachable: timed out after 30000 ms")
    }
}

private fun noul(p: Double) = FakeDecider { buildJsonObject { putJsonObject("worth_reading") { put("type", "noul"); put("noul", p) } } }

/** Answers each status_<n> with the choice given for n. */
private fun choices(vararg byN: Pair<String, Double>) = FakeDecider { q ->
    buildJsonObject {
        q.keys.forEach { key ->
            val (choice, p) = byN[key.removePrefix("status_").toInt()]
            putJsonObject(key) {
                put("type", "choice")
                put("choice", choice)
                putJsonObject("probabilities") {
                    listOf("circumstance", "feeling", "neither").forEach { put(it, if (it == choice) p else (1 - p) / 2) }
                }
            }
        }
    }
}

class DeciderTest {
    private val bodies = listOf(
        KnownBody("person/rose", "Rose", listOf("daughter"), null),
        KnownBody("thing/subaru", "Subaru", emptyList(), null),
    )

    private suspend fun gated(decider: Decider, log: MutableList<String>): Int {
        var runs = 0
        Gate.around(decider, 0.3, "lol ok", "person/sam", listOf("see you at 8", "yes"), bodies, { log += it }) {
            runs++
            listOf("a fact")
        }
        return runs
    }

    @Test
    fun `below the threshold the analyst is never called`() = runTest {
        val log = mutableListOf<String>()
        assertEquals(0, gated(noul(0.05), log))
        assertTrue(log.single().startsWith("gate: 0.05 that this carries a fact, below 0.3: not read"), log.single())
        assertTrue(log.single().endsWith("(640 tokens in, $0.000027)"), "the call's usage is logged")
    }

    @Test
    fun `at or above it the analyst is called once`() = runTest {
        val log = mutableListOf<String>()
        assertEquals(1, gated(noul(0.8), log))
        assertTrue(log.single().startsWith("gate: 0.8, read"), log.single())
    }

    @Test
    fun `a gate that cannot answer lets the message through, and says why`() = runTest {
        val log = mutableListOf<String>()
        assertEquals(1, gated(DownDecider(), log))
        assertTrue(log.single().startsWith("gate unavailable, analyst asked: decision model unreachable"), log.single())
    }

    @Test
    fun `the gate is sent the message, the sender, the earlier texts and the known bodies`() = runTest {
        val d = noul(0.8)
        gated(d, mutableListOf())
        val (state, questions) = d.asked.single()
        assertEquals("lol ok", state["message"]?.jsonPrimitive?.content)
        assertEquals("person/sam", state["from"]?.jsonPrimitive?.content)
        assertEquals(listOf("see you at 8", "yes"), (state["earlier"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("person/rose | Rose | daughter", "thing/subaru | Subaru"), (state["known_bodies"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(Gate.RULE, state["rule"]?.jsonPrimitive?.content)
        assertEquals("noul", questions["worth_reading"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    private fun status(value: String, subject: String = "person/sam") =
        Noticed(subject, "status", JsonPrimitive(value), 0L, null, 80)

    private val where = Noticed("person/sam", "location", JsonPrimitive("the courthouse"), 0L, null, 80)

    private suspend fun check(decider: Decider, vararg rows: Noticed): Triple<List<Noticed>, StatusCheck.Tally, List<String>> {
        val log = mutableListOf<String>()
        val (kept, t) = StatusCheck.filter(decider, "jury duty makes me want to scream", "person/sam", rows.toList()) { log += it }
        return Triple(kept, t, log)
    }

    @Test
    fun `a circumstance is written`() = runTest {
        val (kept, t, _) = check(choices("circumstance" to 0.99), status("on jury duty"))
        assertEquals(listOf("on jury duty"), kept.map { it.value.jsonPrimitive.content })
        assertEquals(StatusCheck.Tally(checked = 1, kept = 1, costUsd = 0.0000269), t)
    }

    @Test
    fun `a feeling is dropped, and the batch carries no status`() = runTest {
        val (kept, t, log) = check(choices("feeling" to 0.97), status("want to scream"), where)
        assertEquals(listOf(where), kept, "the location rides on; only the status goes")
        assertEquals(1, t.feeling)
        assertTrue(log.any { it == "status \"want to scream\": 0.97 feeling, dropped" }, log.toString())
    }

    @Test
    fun `neither a circumstance nor a feeling is dropped too`() = runTest {
        val (kept, t, log) = check(choices("neither" to 0.9), status("at the dentist tomorrow"))
        assertTrue(kept.isEmpty())
        assertEquals(1, t.neither)
        assertTrue(log.any { it == "status \"at the dentist tomorrow\": 0.9 neither, dropped" }, log.toString())
    }

    @Test
    fun `an unsure answer keeps the row and says so`() = runTest {
        val (kept, t, log) = check(choices("feeling" to 0.55), status("swamped"))
        assertEquals(1, kept.size)
        assertEquals(1, t.uncertain)
        assertTrue(log.any { it.startsWith("status \"swamped\": uncertain (") && it.endsWith("), kept") }, log.toString())
    }

    @Test
    fun `a decider that cannot answer keeps every row`() = runTest {
        val (kept, _, log) = check(DownDecider(), status("on jury duty"), status("want to scream"))
        assertEquals(2, kept.size)
        assertTrue(log.single().startsWith("status check unavailable, 2 kept: decision model unreachable"), log.single())
    }

    @Test
    fun `a status on a situation is never sent`() = runTest {
        val d = choices("feeling" to 0.99)
        val (kept, _, _) = check(d, status("open", subject = "situation/breakdown"), where)
        assertEquals(2, kept.size)
        assertTrue(d.asked.isEmpty())
    }

    @Test
    fun `two statuses ride in one request, each naming its own proposal`() = runTest {
        val d = choices("circumstance" to 0.99, "feeling" to 0.97)
        val (kept, _, _) = check(d, status("on jury duty"), status("want to scream"))
        assertEquals(listOf("on jury duty"), kept.map { it.value.jsonPrimitive.content })
        val (state, questions) = d.asked.single()
        assertEquals(setOf("status_0", "status_1"), questions.keys)
        assertTrue("\"want to scream\"" in questions["status_1"]!!.jsonObject["instructions"]!!.jsonPrimitive.content)
        assertEquals(StatusCheck.RULE, state["rule"]?.jsonPrimitive?.content)
        assertEquals(
            listOf(0 to "on jury duty", 1 to "want to scream"),
            (state["proposals"] as JsonArray).map { it.jsonObject["n"]!!.jsonPrimitive.content.toInt() to it.jsonObject["value"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `the day's two lines`() {
        val day = DecideDay(read = 40, skipped = 21, gateUsd = 0.0016, analystUsd = 0.0) +
            StatusCheck.Tally(checked = 5, kept = 3, feeling = 1, neither = 1, costUsd = 0.0002)
        assertEquals(
            listOf(
                "gate 2026-09-19: 40 read, 21 skipped, the gate cost $0.001600, the analyst $0.000000",
                "status check 2026-09-19: 5 checked, 3 kept, 1 dropped as feeling, 1 dropped as neither, 0 uncertain, $0.000200",
            ),
            day.lines("2026-09-19"),
        )
    }
}
