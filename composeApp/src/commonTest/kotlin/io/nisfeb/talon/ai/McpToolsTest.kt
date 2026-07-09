package io.nisfeb.talon.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the trust-posture classification (what the model may see / must
 * confirm) and the tool-result flattening — the two pure pieces of the
 * MCP bridge. Getting the classification wrong is a security issue, so
 * it's worth a test even though the transport itself needs a live ship.
 */
class McpToolsTest {

    @Test
    fun `eval and install tools are hidden`() {
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("mcp/eval-thread-builder"))
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("mcp/install-mcp-feature"))
        // Defence-in-depth: renamed/namespaced eval or install stays hidden.
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("custom/eval-hoon"))
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("install-desk"))
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("run-eval-now"))
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("evaluate-code"))
        // Word PREFIX, so mid-name eval spellings stay hidden too…
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("hoon-evaluator"))
        assertEquals(McpVisibility.Hidden, mcpToolVisibility("run-evaluate-now"))
        // …but not mid-WORD: these are ordinary tools.
        assertEquals(McpVisibility.Write, mcpToolVisibility("retrieval-search"))
        assertEquals(McpVisibility.Write, mcpToolVisibility("uninstall-nothing"))
    }

    @Test
    fun `scry tools are read, everything else is write-gated`() {
        assertEquals(McpVisibility.Read, mcpToolVisibility("mcp/scry-agent"))
        assertEquals(McpVisibility.Read, mcpToolVisibility("scry-anything"))
        // poke + unknown default to write (confirm gate).
        assertEquals(McpVisibility.Write, mcpToolVisibility("mcp/poke-agent"))
        assertEquals(McpVisibility.Write, mcpToolVisibility("mcp/some-future-tool"))
    }

    private fun result(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `extractToolText joins content text blocks`() {
        val r = result("""{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""")
        assertEquals("a\nb", McpClient.extractToolText(r))
    }

    @Test
    fun `extractToolText surfaces isError`() {
        val r = result("""{"content":[{"type":"text","text":"boom"}],"isError":true}""")
        assertEquals("Error: boom", McpClient.extractToolText(r))
    }

    @Test
    fun `extractToolText handles empty content`() {
        assertEquals("(no output)", McpClient.extractToolText(result("""{"content":[]}""")))
        assertEquals("(no output)", McpClient.extractToolText(result("""{}""")))
    }

    @Test
    fun `extractToolText surfaces structuredContent when there is no text block`() {
        // Structured tools (e.g. get-our-id) return structuredContent and
        // NO content[].text — that read as "(no output)" and made every
        // structured tool look like it returned nothing.
        val r = result("""{"structuredContent":{"ship":"~sampel-palnet"},"isError":false}""")
        val out = McpClient.extractToolText(r)
        assertTrue(out.contains("~sampel-palnet"), "must surface the structured data: $out")
    }

    @Test
    fun `extractToolText reads text nested under a resource block`() {
        val r = result("""{"content":[{"type":"resource","resource":{"uri":"x","text":"file body"}}]}""")
        assertEquals("file body", McpClient.extractToolText(r))
    }

    @Test
    fun `extractToolText combines text and structuredContent`() {
        val r = result("""{"content":[{"type":"text","text":"summary"}],"structuredContent":{"n":3}}""")
        val out = McpClient.extractToolText(r)
        assertTrue(out.contains("summary") && out.contains("\"n\":3"), out)
    }

    @Test
    fun `sanitizeToolName coerces namespaced MCP names to the provider pattern`() {
        // The bug: an MCP tool named with a "/" (or ":" / "%") was sent as
        // a function name and the provider 400'd the whole request with
        // tools.N.name must match ^[A-Za-z0-9_-]{1,128}$.
        val pattern = Regex("^[A-Za-z0-9_-]{1,64}$")
        listOf(
            "chat/send" to "chat_send",
            "%settings:get" to "settings_get",
            "scry-agent" to "scry-agent", // already valid, unchanged
            "poke agent!" to "poke_agent",
        ).forEach { (input, expected) ->
            val out = sanitizeToolName(input)
            assertEquals(expected, out, "for '$input'")
            assertTrue(pattern.matches(out), "'$out' must match the provider pattern")
        }
        // Never blank, even when every char is illegal.
        assertEquals("tool", sanitizeToolName("///"))
        assertTrue(pattern.matches(sanitizeToolName("////")))
    }

    @Test
    fun `mcpAgentTools sanitizes names, de-dups, and drops hidden tools`() {
        val schema = JsonObject(emptyMap())
        // Dispatch closures are never invoked here, so a throwaway client is fine.
        val client = McpClient(OkHttpClient(), "http://localhost".toHttpUrl())
        val defs = listOf(
            McpToolDef("chat/send", "send", schema),
            McpToolDef("chat:send", "send2", schema), // sanitizes to the same base
            McpToolDef("mcp/eval-thread-builder", "danger", schema), // hidden
        )
        val tools = mcpAgentTools(client, defs)
        // eval is dropped; the two collide-on-sanitize names are made unique.
        assertEquals(listOf("chat_send", "chat_send_2"), tools.map { it.spec.name })
        val pattern = Regex("^[A-Za-z0-9_-]{1,64}$")
        assertTrue(tools.all { pattern.matches(it.spec.name) })
    }

    @Test
    fun `isLoopbackHost recognizes only localhost forms`() {
        // These pass the ship's HTTPS-or-loopback gate over http; the rest
        // trip the bodyless 400 → drive the "use https" diagnostic.
        assertTrue(McpClient.isLoopbackHost("localhost"))
        assertTrue(McpClient.isLoopbackHost("127.0.0.1"))
        assertTrue(McpClient.isLoopbackHost("127.13.2.9"))
        assertTrue(McpClient.isLoopbackHost("::1"))
        assertTrue(McpClient.isLoopbackHost("[::1]"))
        assertFalse(McpClient.isLoopbackHost("ship.example.com"))
        assertFalse(McpClient.isLoopbackHost("192.168.1.50"))
        assertFalse(McpClient.isLoopbackHost("10.0.0.5"))
    }
}
