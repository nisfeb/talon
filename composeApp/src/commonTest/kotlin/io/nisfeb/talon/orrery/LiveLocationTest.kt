package io.nisfeb.talon.orrery

import io.nisfeb.talon.ui.inboundDetail
import io.nisfeb.talon.ui.outboundDetail
import io.nisfeb.talon.ui.shareProblem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live location as Talon reads orrery's location document (version 88), and what it sends back. */
class LiveLocationTest {
    private val now = 1_790_000_000_000L
    private fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()
    private fun doc(text: String) = Json.parseToJsonElement(text).jsonObject
    private val h = 3_600_000L

    @Test
    fun `the file keys who shares with us by ship, and the route lists them`() {
        val file = doc("""{"out": [], "in": {"~bus": {"id": "person/bus", "name": "Bus", "lat": "40.7", "lon": "-73.9", "acc": "12", "at": "${iso(now - 60_000)}", "until": "${iso(now + h)}", "home": false}}}""")
        val route = doc("""{"out": [], "peers": [], "in": [{"ship": "~bus", "id": "person/bus", "name": "Bus", "lat": "40.7", "lon": "-73.9", "acc": "12", "at": "${iso(now - 60_000)}", "until": "${iso(now + h)}", "home": false, "km_from_home": 3}]}""")
        val want = InShare("~bus", "Bus", 40.7, -73.9, 12.0, now - 60_000, now + h, false)
        assertEquals(listOf(want), locationShares(file, now).inbound)
        assertEquals(listOf(want), locationShares(route, now).inbound)
        assertEquals(want, locationShares(file, now).from("~bus"))
        assertNull(locationShares(file, now).from("~nec"))
    }

    @Test
    fun `a share coming in ends at its time, or a day after its last fix when it has none`() {
        fun one(fields: String) = locationShares(doc("""{"in": {"~bus": {"lat": "1", "lon": "2", $fields}}}"""), now).inbound
        assertEquals(1, one(""""at": "${iso(now)}", "until": "${iso(now + 1)}"""").size)
        assertTrue(one(""""at": "${iso(now)}", "until": "${iso(now)}"""").isEmpty(), "its time has come")
        assertEquals(1, one(""""at": "${iso(now - 23 * h)}", "until": null, "home": true""").size, "until home, a fix 23 h old")
        assertTrue(one(""""at": "${iso(now - 25 * h)}", "until": null, "home": true""").isEmpty(), "until home, quiet a day: its stop was lost")
        assertTrue(one(""""until": null, "home": true""").isEmpty(), "no fix time, no end time")
        assertTrue(locationShares(doc("""{"in": {"~bus": {"lat": "91", "lon": "2", "at": "${iso(now)}"}}}"""), now).inbound.isEmpty(), "no such latitude")
        assertTrue(locationShares(doc("""{"in": {"bus": {"lat": "1", "lon": "2", "at": "${iso(now)}"}}}"""), now).inbound.isEmpty(), "no ship")
    }

    @Test
    fun `a share going out runs to its time, or until home`() {
        val d = doc(
            """{"out": [
              {"ship": "~bus", "until": "${iso(now + h)}", "home": false, "exact": true, "since": "${iso(now)}", "sent": null, "left": false},
              {"ship": "~nec", "until": null, "home": true, "exact": false, "since": "${iso(now)}"},
              {"ship": "~fed", "until": "${iso(now - 1)}", "home": false, "exact": true, "since": "${iso(now - h)}"},
              {"ship": "~wet", "until": null, "home": false, "exact": true, "since": "${iso(now)}"}
            ]}""",
        )
        assertEquals(
            listOf(OutShare("~bus", now + h, home = false, exact = true), OutShare("~nec", null, home = true, exact = false)),
            locationShares(d, now).out,
        )
    }

    // The wire, against orrery's +serve-location-share: (gs jon 'ship'),
    // (gn jon 'hours'), (gj jon 'home') as %b, and (gs jon 'precision')
    // "exact", anything else the area.
    @Test
    fun `a share asks for hours or home, and exact or the area`() {
        assertEquals("""{"ship":"~bus","hours":1,"precision":"exact"}""", shareBody("~bus", ShareLength.Hour, exact = true))
        assertEquals("""{"ship":"~bus","hours":8,"precision":"area"}""", shareBody("~bus", ShareLength.EightHours, exact = false))
        assertEquals("""{"ship":"~bus","home":true,"precision":"area"}""", shareBody("~bus", ShareLength.Home, exact = false))
    }

    // The phone's fast listening (Android's LiveShares) follows this.
    @Test
    fun `the phone sends fast until the last share ends, and reads again for one until home`() {
        val none = LocationShares(emptyList(), emptyList())
        assertNull(sendFastUntil(none, now))
        val hour = OutShare("~bus", now + h, home = false, exact = true)
        val eight = OutShare("~nec", now + 8 * h, home = false, exact = true)
        val home = OutShare("~fed", null, home = true, exact = true)
        assertEquals(now + 8 * h, sendFastUntil(LocationShares(listOf(hour, eight), emptyList()), now))
        assertEquals(now + 15 * 60_000, sendFastUntil(LocationShares(listOf(home), emptyList()), now))
        assertEquals(now + h, sendFastUntil(LocationShares(listOf(hour, home), emptyList()), now))
        assertEquals(now + 2 * h, sendFastUntil(LocationShares(listOf(hour, home), emptyList()), now, recheckMs = 2 * h))
    }

    @Test
    fun `what a share says`() {
        val inbound = InShare("~bus", "Bus", 1.0, 2.0, 1000.0, now - 3 * 60_000, null, home = true)
        assertEquals("Updated 3m ago · until they're home · area only", inboundDetail(inbound, now))
        assertEquals("Updated 3m ago", inboundDetail(inbound.copy(accuracyM = 12.0, home = false), now))
        assertTrue(inboundDetail(inbound.copy(untilMs = now + h), now).startsWith("Updated 3m ago · until "))
        assertEquals("until you're home · exact", outboundDetail(OutShare("~bus", null, home = true, exact = true)))
        assertTrue(outboundDetail(OutShare("~bus", now + h, home = false, exact = false)).let { it.startsWith("until ") && it.endsWith(" · area only") })
    }

    @Test
    fun `a failure says what the ship said, or that it did not answer, and never an exception's text`() {
        assertEquals(
            "Couldn't share: home: this ship does not know where home is; give hours too",
            shareProblem("share", OrreryError.Refused(400, "home: this ship does not know where home is; give hours too")),
        )
        assertEquals("Couldn't stop: the ship said no (500)", shareProblem("stop", OrreryError.Refused(500, "")))
        assertEquals("Couldn't share: your ship didn't answer. Try again.", shareProblem("share", OrreryError.Unreachable(RuntimeException("java.net.ConnectException: refused"))))
        assertEquals("Couldn't share. Try again.", shareProblem("share", IllegalStateException("Not attached to a ship.")))
    }
}
