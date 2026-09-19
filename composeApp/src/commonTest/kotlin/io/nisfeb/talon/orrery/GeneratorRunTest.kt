package io.nisfeb.talon.orrery

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ship generator's last pass, as the top of Actions says it. */
class GeneratorRunTest {
    private val zone = TimeZone.of("America/New_York")
    private val now = Instant.parse("2026-09-19T16:00:00Z").toEpochMilliseconds()
    private fun line(json: String) = generatorLine(generatorRunOf(Json.parseToJsonElement(json).jsonObject), now, zone)

    @Test
    fun `a pass that filed says what it did and what it cost`() {
        // The shape orrery's own page test renders.
        assertEquals(
            "Generator: ran 10:06, filed 3, dropped 1, $0.023, 4 calls today",
            line("""{"at":"2026-09-19T14:06:04Z","filed":3,"dropped":1,"skipped":false,"notes":["model note: the trip is stale"],"usage":{"cost":0.0229,"prompt_tokens":6621},"seconds":17,"error":null,"calls_today":4}"""),
        )
    }

    @Test
    fun `a pass the limits held, one that failed, and the bare record`() {
        assertEquals(
            "Generator: ran 10:06, held by the limits, 24 calls today",
            line("""{"at":"2026-09-19T14:06:04Z","skipped":true,"notes":["held by the limits: the cooldown"],"calls_today":24}"""),
        )
        assertEquals(
            "Generator: ran 18 Sep 22:00, failed: model unreachable, 1 call today",
            line("""{"at":"2026-09-19T02:00:00Z","error":"model unreachable","calls_today":1}"""),
        )
        // What ricsul answered at eleven: held, and until when.
        assertEquals(
            "Generator: ran 11:07, held by the limits until 12:06, 2 calls today",
            line("""{"seconds":0,"usage":null,"filed":0,"calls_today":2,"error":null,"dropped":0,"at":"2026-09-19T15:07:13Z","called":"2026-09-19T15:06:24Z","notes":["held by the limits until 2026-09-19T16:06:24Z"],"day":"2026-09-19","skipped":true}"""),
        )
        // What ricsul answered this morning: only that it called.
        assertEquals("Generator: ran 10:06, 1 call today", line("""{"calls_today":1,"called":"2026-09-19T14:06:04Z","day":"2026-09-19"}"""))
    }
}
