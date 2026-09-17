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
        assertEquals(JsonPrimitive("stranded"), status.value)
    }

    @Test
    fun `a named body's whereabouts and state, at conf 60`() {
        val out = ruleFacts("Sarah is at the shop and she's fine", "~bus", 1L, me, index)
        val loc = out.single { it.attr == "location" }
        assertEquals("person/sarah", loc.subject)
        assertEquals(60, loc.conf)
        assertEquals(JsonPrimitive("fine"), ruleFacts("wife is fine now", "~bus", 1L, me, index).single().value)
    }

    @Test
    fun `questions and chatter claim nothing`() {
        assertTrue(ruleFacts("are you at the shop?", "~bus", 1L, me, index).isEmpty())
        assertTrue(ruleFacts("lol same", "~bus", 1L, me, index).isEmpty())
    }

    @Test
    fun `a speaker's run of lines is one message, up to a cap`() {
        val lines = listOf(Spoken("~bus", "so the car"), Spoken("~bus", " died on route 9 "), Spoken("~zod", "oh no"), Spoken("~bus", ""), Spoken("~bus", "yeah"))
        assertEquals(listOf(Spoken("~bus", "so the car died on route 9"), Spoken("~zod", "oh no"), Spoken("~bus", "yeah")), mergeSpoken(lines))
        assertEquals(2, mergeSpoken(listOf(Spoken("~bus", "a".repeat(600)), Spoken("~bus", "b".repeat(600)))).size)
    }

    @Test
    fun `a claim keeps its id across passes`() {
        assertEquals(noticedId("talon://chat/~bus?id=1", "person/bus", "location"), noticedId("talon://chat/~bus?id=1", "person/bus", "location"))
    }
}
