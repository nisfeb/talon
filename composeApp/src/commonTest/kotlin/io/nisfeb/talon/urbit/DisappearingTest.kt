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
    private val flag = "~bel/dmtest"
    private val week = "0w4D.k0000.00000.002A0"
    /** (scot %uw (jam '~bel/dmtest')), from ~bel's dojo. */
    private val belGroup = "0wWe.raWdH.8nJza.NfOY0"
    private val weekHere = """{"delay": "$week", "group": "$belGroup"}"""

    /**
     * ~bel's /v0/hooks (src cut): our hook's [config] for the channel,
     * the channel's [order], and its sweep's own [cron] config. A null
     * leaves that part out. By default fully on for a week.
     */
    private fun ship(config: String? = weekHere, order: List<String> = listOf(id), cron: String? = weekHere) =
        """{"order": {"$nest": [${order.joinToString { "\"$it\"" }}]}, "hooks": {"$id": {"name": "Talon disappearing messages", "version": "", "id": "$id", "compiled": true, "src": "<src>", "config": {${config?.let { "\"$nest\": $it" } ?: ""}}, "meta": {"image": "", "title": "", "cover": "", "description": ""}}}, "crons": {"$id": {${cron?.let { "\"$nest\": {\"hook\": \"$id\", \"schedule\": {\"repeat\": \"~d1\", \"next\": \"~2026.10.29..14.52.48..4a84\"}, \"config\": $it}" } ?: ""}}}}"""

    private val on = ship()

    private fun hooks(json: String) = Disappearing.Hooks.parse(Json.parseToJsonElement(json))

    @Test
    fun `a group's name is encoded as the ship encodes it`() {
        // (scot %uw (jam '~bel/dmtest')) and the rest, from ~bel's dojo.
        assertEquals(belGroup, Disappearing.groupNoun("~bel/dmtest"))
        assertEquals("0wTc.H8Vcb.enIzu.ZfOY0", Disappearing.groupNoun("~zod/garden"))
        assertEquals("0w3wWJ.XAPBH.OSBXE.OJPoM.K1qSc.HwSIb.C~dU0", Disappearing.groupNoun("~sampel-palnet/my-group"))
        for (g in listOf("~bel/dmtest", "~sampel-palnet/my-group", "~zod/café")) assertEquals(g, Disappearing.groupOf(Disappearing.groupNoun(g)))
        assertNull(Disappearing.groupOf("0w2"), "(jam 0)")
        assertNull(Disappearing.groupOf("~bel/dmtest"))
    }

    @Test
    fun `the ship's answer reads as the setting`() {
        val h = hooks(on)
        assertEquals(id, h.id)
        assertEquals(true, h.compiled)
        assertEquals(WEEK, h.delayOn(nest, flag))
        assertEquals(mapOf(nest to Disappearing.Config(WEEK, flag)), h.crons, "the sweep's own copy of the config")
        assertEquals(false, h.unfinished(nest, flag))
        assertNull(h.delayOn("chat/~bel/other", flag))
        assertEquals(false, h.unfinished("chat/~bel/other", flag))
        // Nothing added yet, or another app's hook only.
        assertNull(hooks("""{"hooks":{},"order":{},"crons":{}}""").id)
        assertNull(hooks(on.replace("Talon disappearing messages", "word filter")).id)
    }

    /**
     * Set for one group, it is nothing to any other: a channel another
     * group lists, or one a Tlon template copied the config to, is not on
     * for that group, and does nothing on the ship either.
     */
    @Test
    fun `a config for another group is not on here`() {
        assertNull(hooks(on).delayOn(nest, "~bel/other"))
        assertEquals(true, hooks(on).unfinished(nest, "~bel/other"))
        // Written by a Talon from before the group went in: half on, to be set again.
        val noGroup = """{"delay": "$week"}"""
        val old = hooks(ship(config = noGroup, cron = noGroup))
        assertNull(old.delayOn(nest, flag))
        assertEquals(true, old.unfinished(nest, flag))
        // The config's group and the sweep's must both be this one.
        val sweepElsewhere = hooks(ship(cron = """{"delay": "$week", "group": "${Disappearing.groupNoun("~bel/other")}"}"""))
        assertNull(sweepElsewhere.delayOn(nest, flag))
        assertEquals(true, sweepElsewhere.unfinished(nest, flag))
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

    /** A noun cut short reads as nothing, not as a smaller delay or another group. */
    @Test
    fun `a noun cut short reads as nothing`() {
        val week = Disappearing.delayNoun(WEEK)
        assertNull(Disappearing.delaySeconds("0w" + week.removePrefix("0w").drop(2)))
        assertNull(Disappearing.groupOf("0w" + Disappearing.groupNoun("~bel/dmtest").removePrefix("0w").drop(3)))
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
        val halves = listOf(
            "out of the order" to ship(order = emptyList()),
            "no config" to ship(config = null),
            "no sweep" to ship(cron = null),
            "the sweep's delay differs" to ship(cron = """{"delay": "0wY000.00000.000I0", "group": "$belGroup"}"""),
            "only in the order" to ship(config = null, cron = null),
        )
        for ((what, json) in halves) {
            assertNull(hooks(json).delayOn(nest, flag), what)
            assertEquals(true, hooks(json).unfinished(nest, flag), what)
        }
        assertEquals(
            listOf("""{"order":{"nest":"$nest","seq":[]}}"""),
            Disappearing.pokes(hooks(ship(config = null, cron = null)), flag, listOf(nest), null).map { it.toString() },
        )
        assertEquals(false, hooks(ship(config = null, order = listOf("0vother"), cron = null)).unfinished(nest, flag), "another hook in the order is not ours")
    }

    @Test
    fun `turning on sends config, order and cron for this group, keeping other hooks`() {
        val h = hooks(ship(order = listOf("0vother")))
        assertEquals(
            listOf(
                """{"config":{"id":"$id","nest":"$nest","config":{"delay":"0wY000.00000.000I0","group":"$belGroup"}}}""",
                """{"order":{"nest":"$nest","seq":["0vother","$id"]}}""",
                """{"cron":{"id":"$id","origin":"$nest","schedule":"~d1","config":{"delay":"0wY000.00000.000I0","group":"$belGroup"}}}""",
            ),
            Disappearing.pokes(h, flag, listOf(nest), 30).map { it.toString() },
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
            Disappearing.pokes(hooks(on), flag, listOf(nest), null).map { it.toString() },
        )
        // What a half-taken off left is all that is sent again.
        assertEquals(
            listOf("""{"rest":{"id":"$id","origin":"$nest"}}""", """{"order":{"nest":"$nest","seq":[]}}"""),
            Disappearing.pokes(hooks(ship(config = null)), flag, listOf(nest), null).map { it.toString() },
        )
        // A delay this Talon cannot read is emptied all the same.
        val odd = hooks(ship(config = """{"delay": "0wc"}"""))
        assertEquals("""{"config":{"id":"$id","nest":"$nest","config":{}}}""", Disappearing.pokes(odd, flag, listOf(nest), null).first().toString())
        assertEquals(true, odd.unfinished(nest, flag))
        // A channel it never ran on: nothing to send, and no %rest, which
        // would crash on the missing cron.
        assertEquals(emptyList(), Disappearing.pokes(hooks(on), flag, listOf("chat/~bel/other"), null))
        assertEquals(emptyList(), Disappearing.pokes(hooks("""{"hooks":{},"order":{},"crons":{}}"""), flag, listOf(nest), null))
    }

    @Test
    fun `adding and editing the hook`() {
        assertEquals("""{"add":{"name":"Talon disappearing messages","src":"|=  a  a"}}""", Disappearing.add("|=  a  a").toString())
        assertEquals("""{"edit":{"id":"$id","name":null,"src":"x","meta":null}}""", Disappearing.edit(id, "x").toString())
    }
}
