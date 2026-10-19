package io.nisfeb.talon.ai

import io.nisfeb.talon.orrery.OrreryError
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The assistant said it could not get the owner's flight status while
 * orrery was watching the flight. What it now reads is orrery's last
 * look, as GET /api/watches answers it (orrery 109's serve-watches).
 */
class OrreryFlightsTest {

    /** As orrery 109 answers: the key masked, a flight looked at, one failing, one landed, a page. */
    private val doc = Json.parseToJsonElement(
        """{"flight_api":{"url":"https://prod.api.market/api/v1/aedbx/aerodatabox","host":"aerodatabox.p.rapidapi.com","key_set":true},
        "watches":[
          {"id":"dl2627","kind":"flight","label":"JAX to ATL","flight":"dl2627","date":"2026-10-18","from":"JAX","cadence_min":180,
           "next":"2026-10-18T22:40:00Z","note":"",
           "last":{"status":"Delayed","dep_sched":"2026-10-18 23:00Z","dep_revised":"2026-10-18 23:25Z","dep_gate":"B4","dep_term":"",
                   "arr_sched":"2026-10-19 00:30Z","arr_revised":"","arr_gate":"A12"}},
          {"id":"etias","kind":"page","label":"ETIAS","url":"https://travel-europe.europa.eu","look":"now open","found":false},
          {"id":"dl195","kind":"flight","flight":"dl195","date":"2026-10-23","from":"BCN","note":"flight API answered 429",
           "last":{"status":"Expected","dep_sched":"2026-10-23 09:00Z","dep_term":"1"}},
          {"id":"dl2189","kind":"flight","flight":"dl2189","date":"2026-10-23","note":"no flight API key set"},
          {"id":"dl194","kind":"flight","flight":"dl194","date":"2026-10-19","done":true,"next":"2026-10-19T05:00:00Z",
           "last":{"status":"Arrived","dep_sched":"bad time"}}
        ]}""",
    ).jsonObject

    private val ny = TimeZone.of("America/New_York")

    @Test
    fun `each flight says what orrery last found, in the owner's time`() {
        val lines = flightLines(doc, ny).lines()
        assertEquals("Times are in America/New_York.", lines[0])
        assertEquals(
            "DL2627 on 2026-10-18 from JAX (JAX to ATL): status Delayed; departs 2026-10-18 19:00, revised 2026-10-18 19:25, gate B4; " +
                "arrives 2026-10-18 20:30, arrival gate A12; orrery looks again at 2026-10-18 18:40.",
            lines[1],
        )
        assertEquals(5, lines.size, "the page is not a flight: $lines")
    }

    @Test
    fun `a failed look says the status is the one before, and no look says so`() {
        val lines = flightLines(doc, TimeZone.UTC).lines()
        assertEquals(
            "DL195 on 2026-10-23 from BCN: orrery's last look failed (flight API answered 429), so this is the look before; " +
                "status Expected; departs 2026-10-23 09:00, terminal 1.",
            lines[2],
        )
        assertEquals("DL2189 on 2026-10-23: not looked at yet: no flight API key set.", lines[3])
    }

    @Test
    fun `a landed flight is not looked at again, and a time that does not parse is shown as it came`() {
        assertEquals(
            "DL194 on 2026-10-19: status Arrived; departs bad time; landed or a day past due: orrery no longer looks.",
            flightLines(doc, TimeZone.UTC).lines()[4],
        )
    }

    @Test
    fun `no flights is said, not an empty answer`() {
        assertEquals("Orrery watches no flights.", flightLines(buildJsonObject { }, TimeZone.UTC))
        val pagesOnly = Json.parseToJsonElement("""{"watches":[{"id":"etias","kind":"page"}]}""").jsonObject
        assertEquals("Orrery watches no flights.", flightLines(pagesOnly, TimeZone.UTC))
    }

    private suspend fun ask(t: OrreryToolsTest.Tap) = orreryTools(t).single { it.spec.name == "orrery_flights" }.execute(buildJsonObject { })

    @Test
    fun `the tool reads orrery's watches and writes nothing`() = runTest {
        val t = OrreryToolsTest.Tap().apply { watchesDoc = Result.success(doc) }
        val said = ask(t)
        assertTrue("DL2627 on 2026-10-18 from JAX" in said && "status Delayed" in said, said)
        assertEquals(false, orreryTools(t).single { it.spec.name == "orrery_flights" }.write)
        assertTrue("api.market" !in said && "key_set" !in said, "the flight API's config is not the model's: $said")
    }

    @Test
    fun `an orrery without watches, and a ship that does not answer, are said apart`() = runTest {
        val old = OrreryToolsTest.Tap().apply { watchesDoc = Result.failure(OrreryError.Refused(404, "not found")) }
        assertEquals("This orrery has no flight watches: it is older than version 105.", ask(old))
        val down = OrreryToolsTest.Tap().apply { watchesDoc = Result.failure(OrreryError.Unreachable(RuntimeException("reset"))) }
        assertEquals("Could not read orrery's flights: no answer from the ship", ask(down))
    }

    @Test
    fun `the voice assistant may ask it too`() {
        assertTrue("orrery_flights" in DictationAgent.TOOLS)
        assertTrue(DictationAgent.readsFor(emptyList(), OrreryToolsTest.Tap()).any { it.spec.name == "orrery_flights" })
    }
}
