package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The funnel's floor: scope, names, and the few claims the rules read. */
class OrreryTriageTest {
    private val me = "~zod"
    private val index = NameIndex(listOf(
        KnownBody("person/me", "me", listOf("me", "I"), me),
        KnownBody("person/sarah", "Sarah", listOf("Sarah", "wife"), "~sampel-palnet"),
        KnownBody("place/home", "Home", listOf("home"), null),
        KnownBody("place/johns-machine-shop", "John's Machine Shop", listOf("John's", "the shop"), null),
    ))

    @Test
    fun `dms are always in scope and channels only when allowed or naming us`() {
        assertTrue(inScope("~bus", "hi", me, null, emptySet()))
        assertTrue(inScope("0v1.abc", "hi", me, null, emptySet()))
        assertTrue(!inScope("chat/~host/general", "hi all", me, null, emptySet()))
        assertTrue(inScope("chat/~host/general", "hi all", me, null, setOf("chat/~host/general")))
        assertTrue(inScope("chat/~host/general", "~zod are you there", me, null, emptySet()))
        assertTrue(inScope("chat/~host/general", "hey Zed!", me, "Zed", emptySet()))
        assertTrue(!inScope("chat/~host/general", "the zedd thing", me, "Zed", emptySet()))
        // The pattern is kept between posts; a new nickname is its own.
        assertTrue(inScope("chat/~host/general", "ask Al", me, "Al", emptySet()), "two letters is a nickname")
        assertTrue(!inScope("chat/~host/general", "hey Zed!", me, "Al", emptySet()), "not the nickname before")
    }

    @Test
    fun `a name, alias, ship or id resolves exactly, and nothing else does`() {
        assertEquals("person/sarah", index.resolveExact("person/sarah")?.id)
        assertEquals("person/sarah", index.resolveExact("~sampel-palnet")?.id)
        assertEquals("person/sarah", index.resolveExact(" sarah ")?.id)
        assertEquals("person/sarah", index.resolveExact("Wife")?.id)
        assertEquals("place/johns-machine-shop", index.resolveExact("John's Machine Shop")?.id)
        assertNull(index.resolveExact("Sar"))
        assertNull(index.resolveExact("  "))
    }

    @Test
    fun `what the model reads is a statement of some length, and a command is not for the gate`() {
        assertTrue(!forTheReader("ok sure"), "seven letters")
        assertTrue(forTheReader("ok sure!"))
        assertTrue(!forTheReader("is it done yet? "))
        assertTrue(forTheGate("I'm at the shop"))
        assertTrue(!forTheGate("/remind me at the shop"))
    }

    @Test
    fun `names match whole words only and know a ship`() {
        assertEquals(listOf("person/sarah"), index.find("Sarah said so").map { it.first.id })
        assertTrue(index.find("Sarahs bag").isEmpty(), "a prefix is not a name")
        assertEquals("person/sarah", index.find("ping ~sampel-palnet").single().first.id)
        assertEquals("place/johns-machine-shop", index.place("the shop")?.id)
        assertNull(index.place("the mall"))
    }

    @Test
    fun `where I am, as the author, at conf 70 for six hours`() {
        val out = ruleFacts("I'm at the shop now, car's being looked at", "~bus", 1_000L, me, index)
        val loc = out.single { it.attr == "location" }
        assertEquals("person/bus", loc.subject)
        assertEquals("place/johns-machine-shop", loc.value.jsonObject["ref"]!!.jsonPrimitive.content, "a known place is a ref")
        assertEquals(70, loc.conf)
        assertEquals(1_000L + 6 * 3_600_000, loc.untilMs)
        assertEquals("person/bus", loc.body?.id, "a stranger's person comes with the claim")
        assertEquals(JsonPrimitive("Denver"), ruleFacts("we're in Denver until Friday", "~bus", 1L, me, index).single().value)
        assertEquals("person/me", ruleFacts("I am at the airport.", me, 1L, me, index).single().subject)
        assertNull(ruleFacts("I am at the airport.", me, 1L, me, index).single().body, "me needs no body")
    }

    @Test
    fun `home and a status line`() {
        val home = ruleFacts("I'm home finally", "~bus", 1L, me, index).single()
        assertEquals("place/home", home.value.jsonObject["ref"]!!.jsonPrimitive.content)
        val status = ruleFacts("I'm stranded, waiting for a tow", "~bus", 1L, me, index).single()
        assertEquals("status", status.attr)
        assertEquals(1L + 6 * 3_600_000, home.untilMs)
        assertEquals(1L + 6 * 3_600_000, status.untilMs)
        assertEquals(JsonPrimitive("stranded"), status.value)
    }

    @Test
    fun `a named body's whereabouts and state, at conf 60`() {
        val out = ruleFacts("Sarah is at the shop and she's fine", "~bus", 1L, me, index)
        val loc = out.single { it.attr == "location" }
        assertEquals("person/sarah", loc.subject)
        assertEquals(60, loc.conf)
        assertEquals(1L + 6 * 3_600_000, loc.untilMs)
        assertEquals(1L + 6 * 3_600_000, ruleFacts("wife is fine now", "~bus", 1L, me, index).single().untilMs)
        assertEquals(JsonPrimitive("fine"), ruleFacts("wife is fine now", "~bus", 1L, me, index).single().value)
    }

    @Test
    fun `questions and chatter claim nothing`() {
        assertTrue(ruleFacts("are you at the shop?", "~bus", 1L, me, index).isEmpty())
        // The same words as a statement claim a place; asked, they claim nothing.
        assertTrue(ruleFacts("I'm at the shop", "~bus", 1L, me, index).isNotEmpty())
        assertTrue(ruleFacts("I'm at the shop?", "~bus", 1L, me, index).isEmpty())
        assertTrue(ruleFacts("lol same", "~bus", 1L, me, index).isEmpty())
    }

    @Test
    fun `a speaker's run of lines is one message, up to a cap`() {
        val lines = listOf(Spoken("~bus", "so the car"), Spoken("~bus", " died on route 9 "), Spoken("~zod", "oh no"), Spoken("~bus", ""), Spoken("~bus", "yeah"))
        assertEquals(listOf(Spoken("~bus", "so the car died on route 9"), Spoken("~zod", "oh no"), Spoken("~bus", "yeah")), mergeSpoken(lines))
        assertEquals(2, mergeSpoken(listOf(Spoken("~bus", "a".repeat(600)), Spoken("~bus", "b".repeat(600)))).size)
        assertEquals(1, mergeSpoken(listOf(Spoken("~bus", "hello"), Spoken("~bus", "world")), maxChars = 11).size, "exactly the cap, the space counted")
        assertEquals(2, mergeSpoken(listOf(Spoken("~bus", "hello"), Spoken("~bus", "world!")), maxChars = 11).size)
    }

    @Test
    fun `a phone yields only to a computer whose key was used in the last two hours`() {
        val now = 1_789_646_400_000L
        val hour = 3_600_000L
        assertTrue(computerActive(listOf(ClientKey("k", "talon/desktop-linux-6.6", now - hour)), now))
        assertTrue(!computerActive(listOf(ClientKey("k", "talon/desktop-linux-6.6", now - 3 * hour)), now), "a computer quiet for three hours does not hold the phone")
        assertTrue(!computerActive(listOf(ClientKey("k", "talon/desktop-linux-6.6", null)), now), "never used is not active")
        assertTrue(!computerActive(listOf(ClientKey("a", "talon/android-17", now), ClientKey("b", "claude-code", now)), now), "another phone or the analyst is not a computer running Talon")
    }

    @Test
    fun `a claim keeps its id across passes`() {
        val here = JsonPrimitive("the shop")
        assertEquals(noticedId("talon://chat/~bus?id=1", "person/bus", "location", here), noticedId("talon://chat/~bus?id=1", "person/bus", "location", here))
        // A single-valued attribute's id is what it always was, so the
        // rows already in the tray keep theirs.
        assertEquals("talon://chat/~bus?id=1|person/bus|location", noticedId("talon://chat/~bus?id=1", "person/bus", "location", here))
        // Two evenings off in one message are two rows, not one row and
        // a second insert quietly ignored.
        val tonight = noticedId("m1", "activity/practice", "skipped", JsonPrimitive("2026-09-22T22:00:00Z"))
        val nextWeek = noticedId("m1", "activity/practice", "skipped", JsonPrimitive("2026-09-29T22:00:00Z"))
        assertTrue(tonight != nextWeek)
    }
}
