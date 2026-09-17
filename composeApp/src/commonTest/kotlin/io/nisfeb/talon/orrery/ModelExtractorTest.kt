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
