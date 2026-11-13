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
        assertTrue(one(""""at": "${iso(now - 24 * h)}", "until": null, "home": true""").isEmpty(), "a day to the millisecond is a day")
        assertEquals(1, locationShares(doc("""{"in": {"~bus": {"lat": "90", "lon": "-180", "at": "${iso(now)}"}}}"""), now).inbound.size, "the poles and the date line are places")
        assertEquals(1, locationShares(doc("""{"in": {"~bus": {"lat": "-90", "lon": "180", "at": "${iso(now)}"}}}"""), now).inbound.size)
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
              {"ship": "~wet", "until": null, "home": false, "exact": true, "since": "${iso(now)}"},
              {"ship": "~nut", "until": "${iso(now)}", "home": false, "exact": true, "since": "${iso(now - h)}"}
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
        // A check-in (orrery 142): with until home only, to the second, as de-iso reads it.
        assertEquals("""{"ship":"~bus","home":true,"precision":"area","by":"${iso(now)}"}""", shareBody("~bus", ShareLength.Home, exact = false, byMs = now + 345))
        assertEquals("""{"ship":"~bus","hours":1,"precision":"area"}""", shareBody("~bus", ShareLength.Hour, exact = false, byMs = now), "no check-in on a share for hours")
        // Worked out by the ship from the drive home (orrery 143), and only until home.
        assertEquals("""{"ship":"~bus","home":true,"precision":"exact","by":"auto"}""", shareBody("~bus", ShareLength.Home, exact = true, byMs = now, autoBy = true))
        assertEquals("""{"ship":"~bus","hours":8,"precision":"area"}""", shareBody("~bus", ShareLength.EightHours, exact = false, autoBy = true))
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
        val clock = io.nisfeb.talon.util.formatClock(now + h)
        assertEquals("Updated 3m ago · until $clock · area only", inboundDetail(inbound.copy(untilMs = now + h), now))
        assertEquals("until you're home · exact", outboundDetail(OutShare("~bus", null, home = true, exact = true)))
        assertEquals("until $clock · area only", outboundDetail(OutShare("~bus", now + h, home = false, exact = false)))
        // A check-in: ours says by when; theirs, late, says so first.
        assertEquals("until you're home · check in by $clock · exact", outboundDetail(OutShare("~bus", null, home = true, exact = true, byMs = now + h)))
        assertEquals("Not home yet, meant to be by $clock · Updated 3m ago · until they're home · area only", inboundDetail(inbound.copy(lateByMs = now + h), now))
    }

    @Test
    fun `a check-in's time and lateness are read from the file`() {
        val s = locationShares(doc("""{"out":[{"ship":"~bus","until":null,"home":true,"exact":false,"since":"${iso(now)}","by":"${iso(now + h)}","told":false}],
            "in":{"~nec":{"name":"Nec","lat":"1","lon":"2","at":"${iso(now)}","until":null,"home":true,"late":"${iso(now - h)}"}}}"""), now)
        assertEquals(now + h, s.outTo("~bus")?.byMs)
        assertEquals(now - h, s.from("~nec")?.lateByMs)
        val plain = locationShares(doc("""{"out":[{"ship":"~bus","until":null,"home":true,"exact":false,"since":"${iso(now)}"}],"in":{}}"""), now)
        assertNull(plain.outTo("~bus")?.byMs, "a share from before orrery 142")
    }

    @Test
    fun `a check-in's time is the next one, today's or tomorrow's once it has gone`() {
        val utc = kotlinx.datetime.TimeZone.UTC
        // 1_790_000_000_000 is 2026-09-21 13:33:20 UTC.
        assertEquals(kotlin.time.Instant.parse("2026-09-21T22:00:00Z").toEpochMilliseconds(), nextAtMinute(22 * 60, now, utc))
        assertEquals(kotlin.time.Instant.parse("2026-09-22T07:00:00Z").toEpochMilliseconds(), nextAtMinute(7 * 60, now, utc))
        assertEquals(kotlin.time.Instant.parse("2026-09-22T13:33:00Z").toEpochMilliseconds(), nextAtMinute(13 * 60 + 33, now, utc), "the minute already begun is gone")
    }

    @Test
    fun `a failure says what the ship said, or that it did not answer, and never an exception's text`() {
        assertEquals(
            "Couldn't share: home: this ship does not know where home is; give hours too",
            shareProblem("share", OrreryError.Refused(400, "home: this ship does not know where home is; give hours too")),
        )
        assertEquals("Couldn't stop: the node said no (500)", shareProblem("stop", OrreryError.Refused(500, "")))
        assertEquals("Couldn't share: your Groundwire node didn't answer. Try again.", shareProblem("share", OrreryError.Unreachable(RuntimeException("java.net.ConnectException: refused"))))
        assertEquals("Couldn't share. Try again.", shareProblem("share", IllegalStateException("Not attached to a Groundwire node.")))
    }

    // Orrery 137: a share from a ship it does not know waits as an ask.
    @Test
    fun `an ask is listed by ship, from the file or the route, while its last fix is under a day old`() {
        val file = doc("""{"asks": {"~bus": {"lat": "1", "lon": "2", "at": "${iso(now - h)}", "asked": "${iso(now - h)}"}, "~nec": {"lat": "1", "lon": "2", "at": "${iso(now - 25 * h)}"}, "~fed": {"lat": "1", "lon": "2"}}}""")
        val route = doc("""{"asks": [{"ship": "~bus", "lat": "1", "lon": "2", "at": "${iso(now - h)}"}, {"lat": "1", "lon": "2", "at": "${iso(now)}"}]}""")
        assertEquals(listOf("~bus"), locationShares(file, now).asks)
        assertEquals(listOf("~bus"), locationShares(route, now).asks)
        assertTrue(locationShares(file, now).asking("~bus"))
        assertTrue(!locationShares(file, now).asking("~nec"), "a day quiet: gone")
        assertTrue(locationShares(doc("{}"), now).asks.isEmpty(), "an orrery before 137 has none")
    }

    // The wire, against the 137 contract: {"ship": "~bus"} to /accept and /decline.
    @Test
    fun `an answer to an ask names the ship`() {
        assertEquals("""{"ship":"~bus"}""", askBody("~bus"))
    }

    // iOS hands over a fix on every 100 m (IosLocationWatch): the minute is this rule's.
    @Test
    fun `a live share sends at most a fix a minute, and otherwise every fix the platform gives`() {
        assertTrue(fixDue(null, now, fast = true), "the first fix goes")
        assertTrue(!fixDue(now - 59_999, now, fast = true), "not twice in a minute")
        assertTrue(fixDue(now - 60_000, now, fast = true), "a minute on, it goes")
        assertTrue(fixDue(now - 1, now, fast = false), "a significant move goes at once")
    }
}
