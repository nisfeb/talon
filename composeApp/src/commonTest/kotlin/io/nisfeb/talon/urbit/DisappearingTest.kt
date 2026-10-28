package io.nisfeb.talon.urbit

import io.nisfeb.talon.urbit.Disappearing.DAY
import io.nisfeb.talon.urbit.Disappearing.HOUR
import io.nisfeb.talon.urbit.Disappearing.MINUTE
import io.nisfeb.talon.urbit.Disappearing.WEEK
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The pure half of disappearing messages: the delay as the hook's config
 * carries it, what the group is told, and the pokes for each change.
 * The reference encodings and the hooks answer come from a fake ship
 * running Tlon's channels-server (~bel, groups 938f0c4261).
 */
class DisappearingTest {

    @Test
    fun `a delay is encoded as the ship encodes it`() {
        // (scot %uw (jam ~s30)) and (scot %uw (jam ~d7)), from ~bel's dojo.
        assertEquals("0wY000.00000.000I0", Disappearing.delayNoun(30))
        assertEquals("0w4D.k0000.00000.002A0", Disappearing.delayNoun(WEEK))
    }

    @Test
    fun `every delay reads back as itself`() {
        val delays = Disappearing.CHOICES + listOf(1L, 59, 61, 90 * MINUTE, 3 * DAY, 52 * WEEK, Disappearing.MAX_SECONDS)
        for (s in delays) assertEquals(s, Disappearing.delaySeconds(Disappearing.delayNoun(s)), "delay $s")
    }

    @Test
    fun `what is not a whole number of seconds reads as no delay`() {
        assertNull(Disappearing.delaySeconds("0w2"), "(jam 0)")
        assertNull(Disappearing.delaySeconds("0wc"), "(jam 1): a fraction of a second")
        assertNull(Disappearing.delaySeconds("~s30"))
        assertNull(Disappearing.delaySeconds("0w!!"))
        assertNull(Disappearing.delaySeconds("0w"))
    }

    @Test
    fun `a delay outside a second to a year is not sent`() {
        assertFailsWith<IllegalArgumentException> { Disappearing.delayNoun(0) }
        assertFailsWith<IllegalArgumentException> { Disappearing.delayNoun(Disappearing.MAX_SECONDS + 1) }
    }

    @Test
    fun `labels use the largest unit that divides`() {
        assertEquals(
            listOf("4 weeks", "1 week", "1 day", "8 hours", "1 hour", "5 minutes", "30 seconds"),
            Disappearing.CHOICES.map(Disappearing::label),
        )
        assertEquals("90 minutes", Disappearing.label(90 * MINUTE))
        assertEquals("3 days", Disappearing.label(3 * DAY))
        assertEquals("25 hours", Disappearing.label(25 * HOUR))
        assertEquals("61 seconds", Disappearing.label(61))
    }

    @Test
    fun `the description carries one line for the setting`() {
        assertEquals("Messages disappear after 1 week.", Disappearing.descriptionWith("", WEEK))
        assertEquals("Growing things\n\nMessages disappear after 1 day.", Disappearing.descriptionWith("Growing things", DAY))
        // A change replaces the line, and off takes it away.
        val on = Disappearing.descriptionWith("Growing things", DAY)
        assertEquals("Growing things\n\nMessages disappear after 8 hours.", Disappearing.descriptionWith(on, 8 * HOUR))
        assertEquals("Growing things", Disappearing.descriptionWith(on, null))
        assertEquals("", Disappearing.descriptionWith("Messages disappear after 1 week.", null))
        // Only the setting's line: the owner's own words stay.
        assertEquals(
            "Messages disappear sometimes\n\nMessages disappear after 1 hour.",
            Disappearing.descriptionWith("Messages disappear sometimes", HOUR),
        )
    }

    @Test
    fun `the notice says deleted for everyone`() {
        assertEquals(
            "Disappearing messages are on. Messages here are deleted for everyone 1 week after they are sent.",
            Disappearing.notice(WEEK),
        )
        assertEquals("Disappearing messages are off.", Disappearing.notice(null))
    }

    private val id = "0v3.25eh6.4po8s.qe22n.fcrjh.g6ola"
    private val nest = "chat/~bel/dmtest-chat"

    /** ~bel's /v0/hooks with the hook on for a week (src cut). */
    private val on = """{"order": {"chat/~bel/dmtest-chat": ["$id"]}, "hooks": {"$id": {"name": "Talon disappearing messages", "version": "", "id": "$id", "compiled": true, "src": "<src>", "config": {"chat/~bel/dmtest-chat": {"delay": "0w4D.k0000.00000.002A0"}}, "meta": {"image": "", "title": "", "cover": "", "description": ""}}}, "crons": {"$id": {"chat/~bel/dmtest-chat": {"hook": "$id", "schedule": {"repeat": "~d1", "next": "~2026.10.29..14.52.48..4a84"}, "config": {"delay": "0w4D.k0000.00000.002A0"}}}}}"""

    private fun hooks(json: String) = Disappearing.Hooks.parse(Json.parseToJsonElement(json))

    @Test
    fun `the ship's answer reads as the setting`() {
        val h = hooks(on)
        assertEquals(id, h.id)
        assertEquals(true, h.compiled)
        assertEquals(WEEK, h.delayOn(nest))
        assertEquals(mapOf(nest to WEEK), h.crons, "the sweep's own copy of the delay")
        assertEquals(false, h.unfinished(nest))
        assertNull(h.delayOn("chat/~bel/other"))
        assertEquals(false, h.unfinished("chat/~bel/other"))
        // Nothing added yet, or another app's hook only.
        assertNull(hooks("""{"hooks":{},"order":{},"crons":{}}""").id)
        assertNull(hooks(on.replace("Talon disappearing messages", "word filter")).id)
    }

    @Test
    fun `of two hooks left by adds that raced, the one a channel runs is ours`() {
        for (first in listOf(true, false)) {
            val idle = """"0vidle": {"name": "Talon disappearing messages", "id": "0vidle", "compiled": true, "src": "", "config": {}}"""
            val running = """"$id": {"name": "Talon disappearing messages", "id": "$id", "compiled": true, "src": "", "config": {}}"""
            val both = if (first) "$idle, $running" else "$running, $idle"
            assertEquals(id, hooks("""{"hooks": {$both}, "order": {"$nest": ["$id"]}, "crons": {}}""").id, "listed first: $first")
        }
    }

    @Test
    fun `a hook the ship could not build reads as not built`() {
        assertEquals(false, hooks(on.replace("\"compiled\": true", "\"compiled\": false")).compiled)
        assertEquals(false, hooks(on.replace("\"compiled\": true, ", "")).compiled)
    }

    @Test
    fun `a noun of nothing but zeros ends`() {
        assertNull(Disappearing.delaySeconds("0w0"))
        assertNull(Disappearing.delaySeconds("0w0.00000.00000.00000"))
    }

    /**
     * Anything that can still delete is not off. Timers already set read
     * the config when they fire, and a sweep keeps its own copy.
     */
    @Test
    fun `half on is neither on nor off`() {
        val outOfOrder = hooks(on.replace("[\"$id\"]", "[]"))
        val noConfig = hooks(on.replace("\"config\": {\"chat/~bel/dmtest-chat\": {\"delay\": \"0w4D.k0000.00000.002A0\"}}", "\"config\": {}"))
        val noSweep = hooks(on.replace("\"crons\": {\"$id\"", "\"crons\": {\"0vother\""))
        val sweepDiffers = hooks(on.replaceFirst("\"delay\": \"0w4D.k0000.00000.002A0\"}}}}}", "\"delay\": \"0wY000.00000.000I0\"}}}}}"))
        for ((what, h) in listOf("out of the order" to outOfOrder, "no config" to noConfig, "no sweep" to noSweep, "the sweep's delay differs" to sweepDiffers)) {
            assertNull(h.delayOn(nest), what)
            assertEquals(true, h.unfinished(nest), what)
        }
        assertEquals(30L, sweepDiffers.crons[nest], "the fixture's sweep was changed")
        // Left only in the order: it deletes nothing, but an off is not done until it goes.
        val orderOnly = hooks(
            on.replace("\"config\": {\"chat/~bel/dmtest-chat\": {\"delay\": \"0w4D.k0000.00000.002A0\"}}", "\"config\": {}")
                .replace("\"crons\": {\"$id\"", "\"crons\": {\"0vother\""),
        )
        assertEquals(true, orderOnly.unfinished(nest))
        assertEquals(listOf("""{"order":{"nest":"$nest","seq":[]}}"""), Disappearing.pokes(orderOnly, listOf(nest), null).map { it.toString() })
        assertEquals(false, hooks(on.replace("[\"$id\"]", "[\"0vother\"]").replace("\"config\": {\"chat/~bel/dmtest-chat\": {\"delay\": \"0w4D.k0000.00000.002A0\"}}", "\"config\": {}").replace("\"crons\": {\"$id\"", "\"crons\": {\"0vother\"")).unfinished(nest), "another hook in the order is not ours")
    }

    @Test
    fun `turning on sends config, order and cron, keeping other hooks`() {
        val h = hooks(on.replace("[\"$id\"]", "[\"0vother\"]"))
        assertEquals(
            listOf(
                """{"config":{"id":"$id","nest":"$nest","config":{"delay":"0wY000.00000.000I0"}}}""",
                """{"order":{"nest":"$nest","seq":["0vother","$id"]}}""",
                """{"cron":{"id":"$id","origin":"$nest","schedule":"~d1","config":{"delay":"0wY000.00000.000I0"}}}""",
            ),
            Disappearing.pokes(h, listOf(nest), 30).map { it.toString() },
        )
    }

    @Test
    fun `turning off empties its config, rests the sweep, then leaves the order`() {
        assertEquals(
            listOf(
                """{"config":{"id":"$id","nest":"$nest","config":{}}}""",
                """{"rest":{"id":"$id","origin":"$nest"}}""",
                """{"order":{"nest":"$nest","seq":[]}}""",
            ),
            Disappearing.pokes(hooks(on), listOf(nest), null).map { it.toString() },
        )
        // What a half-taken off left is all that is sent again.
        val swept = hooks(on.replace("\"config\": {\"chat/~bel/dmtest-chat\": {\"delay\": \"0w4D.k0000.00000.002A0\"}}", "\"config\": {}"))
        assertEquals(
            listOf("""{"rest":{"id":"$id","origin":"$nest"}}""", """{"order":{"nest":"$nest","seq":[]}}"""),
            Disappearing.pokes(swept, listOf(nest), null).map { it.toString() },
        )
        // A delay this Talon cannot read is emptied all the same.
        val odd = hooks(on.replace("\"config\": {\"chat/~bel/dmtest-chat\": {\"delay\": \"0w4D.k0000.00000.002A0\"}}", "\"config\": {\"chat/~bel/dmtest-chat\": {\"delay\": \"0wc\"}}"))
        assertEquals("""{"config":{"id":"$id","nest":"$nest","config":{}}}""", Disappearing.pokes(odd, listOf(nest), null).first().toString())
        assertEquals(true, odd.unfinished(nest))
        // A channel it never ran on: nothing to send, and no %rest, which
        // would crash on the missing cron.
        assertEquals(emptyList(), Disappearing.pokes(hooks(on), listOf("chat/~bel/other"), null))
        assertEquals(emptyList(), Disappearing.pokes(hooks("""{"hooks":{},"order":{},"crons":{}}"""), listOf(nest), null))
    }

    @Test
    fun `adding and editing the hook`() {
        assertEquals("""{"add":{"name":"Talon disappearing messages","src":"|=  a  a"}}""", Disappearing.add("|=  a  a").toString())
        assertEquals("""{"edit":{"id":"$id","name":null,"src":"x","meta":null}}""", Disappearing.edit(id, "x").toString())
    }
}
