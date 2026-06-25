package io.nisfeb.talon.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
