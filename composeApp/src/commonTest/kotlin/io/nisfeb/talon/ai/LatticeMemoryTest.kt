package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The memory tools come from the ship's own registry, not from Talon. */
class LatticeMemoryTest {
    private val registry = """
        {"jsonrpc":"2.0","result":{"tools":[
          {"name":"lattice-save","description":"Create or overwrite a knowledge entry.",
           "inputSchema":{"type":"object","properties":{"key":{"type":"string","description":"Entry key"},
             "body":{"type":"string","description":"Entry body text"}},"required":["key","body"]}},
          {"name":"lattice-delete","description":"Soft-delete an entry.",
           "inputSchema":{"type":"object","properties":{"key":{"type":"string","description":"Entry key"}},"required":["key"]}},
          {"name":"echo","description":"Not memory.","inputSchema":{"type":"object","properties":{}}}
        ]}}
    """.trimIndent()

    private val ship = HttpClient(MockEngine { req ->
        when (req.url.encodedPath) {
            "/grubbery/mcp/api/tools" -> respond(registry, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else -> respond("""{"jsonrpc":"2.0","id":1,"result":{}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
    })

    @Test fun `only lattice tools, described by the ship, and only removal asks`() = runTest {
        val tools = LatticeMemory.connect(ship, "https://ship.example/").associateBy { it.spec.name }
        assertEquals(setOf("lattice-save", "lattice-delete"), tools.keys)
        assertEquals("Create or overwrite a knowledge entry.", tools.getValue("lattice-save").spec.description)
        val body = tools.getValue("lattice-save").spec.parameters["properties"]!!.jsonObject["body"]!!.jsonObject
        assertEquals("Entry body text", body["description"]!!.jsonPrimitive.content)
        assertFalse(tools.getValue("lattice-save").write)
        assertTrue(tools.getValue("lattice-delete").write)
    }
}
