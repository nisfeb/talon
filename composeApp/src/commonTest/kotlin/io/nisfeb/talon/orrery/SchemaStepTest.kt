package io.nisfeb.talon.orrery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Orrery 60's owner step, as its docs/releasing.md gives it word for word. */
class SchemaStepTest {
    private fun o(s: String) = Json.parseToJsonElement(s).jsonObject

    private val old = o(
        """{"style":"Short.","actions":["task","message","merge"],"payloads":{"message":{"to":"required: a body id","text":"required: the message; never an exclamation mark"}},""" +
            """"kinds":{"person":{"attrs":["name","spouse"],"notes":{"name":"what they go by"}},"place":{"attrs":["name"]}},"multi":["likes"],"mine":{"kept":true}}""",
    )

    @Test
    fun `the step adds what the schema lacks, word for word, and moves nothing else`() {
        val (s, lines) = withVersion60(old)
        assertEquals(listOf("task", "message", "merge", "correct", "fact", "preference"), (s["actions"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') })
        val payloads = s["payloads"]!!.jsonObject
        assertEquals(o("""{"from":"required: the body id folded away","into":"required: the body id kept"}"""), payloads["merge"])
        assertEquals(
            o("""{"subject":"required: the body id the wrong fact is about","attr":"required: the attribute, as the state names it","value":"required: the wrong value as the state shows it, a string, or {\"ref\": \"kind/slug\"}","why":"optional: the owner's reason, short"}"""),
            payloads["correct"],
        )
        assertEquals(o("""{"to":"required: a body id","text":"required: the message, short, in the owner's own voice"}"""), payloads["message"], "only its note changes")
        val person = s["kinds"]!!.jsonObject["person"]!!.jsonObject
        assertEquals("""["name","spouse","children","parents","siblings"]""", person["attrs"].toString(), "spouse was there, and is not twice")
        val notes = person["notes"]!!.jsonObject
        assertEquals("what they go by", notes["name"].toString().trim('"'))
        assertEquals("each brother or sister, a person body as a ref, one row per sibling; written on both of them", notes["siblings"].toString().trim('"'))
        assertEquals("""["likes","children","parents","siblings"]""", s["multi"].toString())
        assertEquals(old["kinds"]!!.jsonObject["place"], s["kinds"]!!.jsonObject["place"])
        assertEquals(o("""{"kept":true}"""), s["mine"])
        assertEquals("Short.", s["style"].toString().trim('"'))
        assertEquals(3, lines.size, lines.toString())
        assertTrue("Orrery may propose: correct, fact, merge, preference." in lines, "merge lacked its shape")
    }

    @Test
    fun `a schema that has it all is left as it is, and says nothing`() {
        val done = withVersion60(old).first
        val (again, lines) = withVersion60(done)
        assertEquals(done, again)
        assertTrue(lines.isEmpty(), lines.toString())
    }

    @Test
    fun `a schema with no person kind or no message gains only what it can`() {
        val (s, lines) = withVersion60(JsonObject(emptyMap()))
        assertEquals(null, s["kinds"])
        assertEquals(null, s["payloads"]!!.jsonObject["message"])
        assertEquals(listOf("Orrery may propose: correct, fact, merge, preference.", "People may have: children, parents, siblings."), lines)
    }

    @Test
    fun `family attributes already there still get the notes they lack, and no more`() {
        val some = o("""{"kinds":{"person":{"attrs":["spouse","children","parents","siblings"],"notes":{"spouse":"mine"}}},"multi":["children","parents","siblings"]}""")
        val (s, lines) = withVersion60(some)
        val person = s["kinds"]!!.jsonObject["person"]!!.jsonObject
        assertEquals("""["spouse","children","parents","siblings"]""", person["attrs"].toString())
        assertEquals("mine", person["notes"]!!.jsonObject["spouse"].toString().trim('"'), "the owner's own note stays")
        assertEquals(setOf("spouse", "children", "parents", "siblings"), person["notes"]!!.jsonObject.keys)
        assertTrue("People may have: children, parents, siblings." in lines, lines.toString())
    }
}
