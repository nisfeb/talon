package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parse is the contract every rung is held to. A model that answers
 * in shape gets its claims through; anything outside orrery's rules is
 * dropped, not repaired.
 */
class ModelExtractorTest {
    private val me = "~zod"
    private val index = NameIndex(listOf(
        KnownBody("person/me", "me", listOf("me"), me),
        KnownBody("person/sarah", "Sarah", listOf("Sarah"), "~sampel-palnet"),
        KnownBody("place/johns-machine-shop", "John's Machine Shop", listOf("the shop"), null),
    ))

    @Test
    fun `claims in shape come through with refs, caps and a horizon`() {
        val answer = """{"claims":[
            {"subject":"person/bus","attr":"location","value":{"ref":"place/johns-machine-shop"},"conf":95,"until_hours":3},
            {"subject":"person/sarah","attr":"status","value":"stranded","conf":70},
            {"subject":"person/me","attr":"phone","value":null,"conf":60}
        ]}"""
        val out = ModelExtractor.parse(answer, index, author = "~bus", atMs = 1_000L, ourShip = me)
        assertEquals(3, out.size)
        val loc = out[0]
        assertEquals("place/johns-machine-shop", loc.value.jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals(80, loc.conf, "a model never claims above 80")
        assertEquals(1_000L + 3 * 3_600_000, loc.untilMs)
        assertEquals("person/bus", loc.body?.id, "the author is a stranger, so their body comes along")
        assertEquals(JsonPrimitive("stranded"), out[1].value)
        assertEquals(JsonNull, out[2].value)
    }

    @Test
    fun `what the rules would refuse is dropped`() {
        val answer = """{"claims":[
            {"subject":"person/nobody","attr":"status","value":"x","conf":90},
            {"subject":"person/sarah","attr":"Bad Attr!","value":"x","conf":90},
            {"subject":"person/sarah","attr":"spouse","value":{"ref":"person/unknown"},"conf":90},
            {"subject":"person/sarah","attr":"status","value":"low","conf":10},
            {"subject":"person/sarah","attr":"status","value":"","conf":90},
            {"subject":"person/sarah","attr":"status","value":"kept","conf":90}
        ]}"""
        val out = ModelExtractor.parse(answer, index, "~bus", 1L, me)
        assertEquals(listOf("kept"), out.map { it.value.jsonPrimitive.content })
    }

    @Test
    fun `a medical or money claim is dropped, whatever it is called`() {
        // The ship keeps health and income from keys, so this client
        // cannot write them; under another name it would write them
        // where every key can read them.
        val answer = """{"claims":[
            {"subject":"person/sarah","attr":"health","value":"broken ankle","conf":90},
            {"subject":"person/sarah","attr":"diagnosis","value":"broken ankle","conf":90},
            {"subject":"person/sarah","attr":"salary","value":"90k","conf":90},
            {"subject":"person/sarah","attr":"status","value":"on crutches","conf":90}
        ]}"""
        val out = ModelExtractor.parse(answer, index, "~bus", 1L, me, text = "sarah is on crutches, broken ankle, 90k")
        assertEquals(listOf("status"), out.map { it.attr })
    }

    @Test
    fun `not JSON, or not the shape, is nothing`() {
        assertTrue(ModelExtractor.parse("Sure! Here is", index, "~bus", 1L, me).isEmpty())
        assertTrue(ModelExtractor.parse("""{"result":[]}""", index, "~bus", 1L, me).isEmpty())
        assertTrue(ModelExtractor.parse("""{"claims":[]}""", index, "~bus", 1L, me).isEmpty())
    }

    @Test
    fun `the prompt lists the bodies and names the author's id`() {
        val u = ModelExtractor.user(listOf(KnownBody("person/sarah", "Sarah", listOf("Sarah", "wife"), "~sampel-palnet")), "~bus", "person/bus", "2026-09-17T12:00:00Z", "hi")
        assertTrue("- person/sarah: Sarah (wife, ~sampel-palnet)" in u, u)
        assertTrue("Author: ~bus (person/bus)" in u)
        assertTrue(ModelExtractor.GRAMMAR.startsWith("root ::="))
    }

    @Test
    fun `whether a medical fact may be written is the ship's to say`() {
        val claim = """{"claims":[{"subject":"person/sarah","attr":"health","value":"biopsy came back clear","conf":80}]}"""
        // The ship's schema view for this key does not name health, so
        // the key may not write it and the claim goes.
        assertTrue(ModelExtractor.parse(claim, index, "~bus", 1L, me, null, mapOf("person" to listOf("status"))).isEmpty())
        // A key the owner minted to write what it can never read is
        // told so by the schema it is served.
        assertEquals(
            1,
            ModelExtractor.parse(claim, index, "~bus", 1L, me, null, mapOf("person" to listOf("status", "health"))).size,
        )
    }

    @Test
    fun `a feeling has somewhere to go, and nothing comes of it`() {
        // The prompt offers mood so that "want to scream" does not land
        // on status, which is a circumstance an onlooker would state.
        val answer = """{"claims":[
            {"subject":"person/sarah","attr":"status","value":"on jury duty","conf":80},
            {"subject":"person/sarah","attr":"mood","value":"frustrated","conf":60},
            {"subject":"person/sarah","attr":"feelings","value":"fed up","conf":60}
        ]}"""
        val out = ModelExtractor.parse(answer, index, "~bus", 1L, me)
        assertEquals(listOf("status" to "on jury duty"), out.map { it.attr to it.value.jsonPrimitive.content })
    }

    @Test
    fun `an event is never said to be open`() {
        val here = NameIndex(listOf(KnownBody("situation/bed-delivery", "Bed delivery", emptyList(), null)))
        val answer = """{"claims":[
            {"subject":"situation/bed-delivery","attr":"status","value":"open","conf":90},
            {"subject":"situation/bed-delivery","attr":"status","value":"closed","conf":90}
        ]}"""
        val out = ModelExtractor.parse(answer, here, "~bus", 1L, me)
        assertEquals(listOf("closed"), out.map { it.value.jsonPrimitive.content }, "open reopens what the ship retired")
    }

    @Test
    fun `a status that reads like the message before is a reading of it`() {
        val answer = """{"claims":[{"subject":"person/sarah","attr":"status","value":"stranded, waiting for a tow","conf":80}]}"""
        val earlier = listOf("car died on route 9, stranded waiting for a tow")
        assertTrue(
            ModelExtractor.parse(answer, index, "~bus", 1L, me, null, emptyMap(), "still here", earlier).isEmpty(),
            "the words are the earlier message's, not this one's",
        )
        assertEquals(
            1,
            ModelExtractor.parse(answer, index, "~bus", 1L, me, null, emptyMap(), "still stranded", earlier).size,
        )
        // A status of the new message stands even when it shares no word
        // with it: a paraphrase is the whole point of the attribute.
        val paraphrase = """{"claims":[{"subject":"person/sarah","attr":"status","value":"at the DMV","conf":80}]}"""
        assertEquals(1, ModelExtractor.parse(paraphrase, index, "~bus", 1L, me, null, emptyMap(), "stuck at the DMV all day", earlier).size)
        assertEquals(1, ModelExtractor.parse(answer, index, "~bus", 1L, me, null, emptyMap(), "still here").size)
    }

    @Test
    fun `the prompt carries the ship's own wording and what was said before`() {
        val u = ModelExtractor.user(
            emptyList(), "~bus", "person/bus", "2026-09-17T12:00:00Z", "yes, at 8",
            notes = mapOf("person" to mapOf("status" to "what they are dealing with now, not a feeling")),
            context = listOf("~zod" to "dinner thursday?", "~bus" to "which one"),
        )
        assertTrue("person.status: what they are dealing with now, not a feeling" in u, u)
        assertTrue("Claim nothing from these" in u, u)
        assertTrue("- ~zod: dinner thursday?" in u, u)
        assertTrue("Message: yes, at 8" in u, u)
    }

    @Test
    fun `a fake model's answer goes through extract`() = kotlinx.coroutines.test.runTest {
        val fake = object : LocalModel {
            override val rung = "fake"
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int) =
                """{"claims":[{"subject":"person/bus","attr":"location","value":"the airport","conf":75,"until_hours":4}]}"""
            override fun close() = Unit
        }
        val out = ModelExtractor.extract(fake, index, emptyList(), "at the airport, boarding soon", "~bus", 1L, me)
        assertEquals("location", out.single().attr)
    }
}
