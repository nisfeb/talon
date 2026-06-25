package io.nisfeb.talon.ai

/**
 * Bridges MCP server tools into the agent's [ToolCatalog]. An MCP tool's
 * `inputSchema` is already a JSON-Schema object, so it drops straight
 * into [ToolSpec.parameters] and the model sees it like any native tool.
 *
 * Trust posture (user-chosen: "read + gated pokes"):
 *  - `eval-thread-builder` / `install-mcp-feature` — the arbitrary-code
 *    and desk-install paths — are HIDDEN from the model entirely.
 *  - `scry-agent` (and anything named `scry*`) is a read: no confirmation.
 *  - everything else is exposed but marked `write = true`, so the agent
 *    loop's confirm gate fires before it runs. Unknown tools default to
 *    write (safe — worst case is an extra confirmation tap).
 *
 * To loosen later (e.g. allow eval), edit [MCP_HIDDEN_TOOLS].
 */

/** Never handed to the model. Matched on the unqualified tool name. */
internal val MCP_HIDDEN_TOOLS = setOf("eval-thread-builder", "install-mcp-feature")

/** Known read-only tools — exposed without the confirm gate. */
internal val MCP_READ_TOOLS = setOf("scry-agent")

internal enum class McpVisibility { Hidden, Read, Write }

internal fun mcpToolVisibility(name: String): McpVisibility {
    val short = name.substringAfterLast('/')
    // Defence in depth: keep the arbitrary-code / install surface hidden
    // even if a tool is renamed or namespaced differently.
    if (short in MCP_HIDDEN_TOOLS || "eval" in short || short.startsWith("install")) {
        return McpVisibility.Hidden
    }
    if (short in MCP_READ_TOOLS || short.startsWith("scry")) return McpVisibility.Read
    return McpVisibility.Write
}

/** Map discovered MCP tool defs to agent [Tool]s, dropping hidden ones. */
fun mcpAgentTools(client: McpClient, defs: List<McpToolDef>): List<Tool> =
    defs.mapNotNull { def ->
        when (mcpToolVisibility(def.name)) {
            McpVisibility.Hidden -> null
            McpVisibility.Read -> Tool(
                spec = ToolSpec(def.name, def.description, def.inputSchema),
                write = false,
            ) { args -> client.callTool(def.name, args) }
            McpVisibility.Write -> Tool(
                spec = ToolSpec(def.name, def.description, def.inputSchema),
                write = true,
            ) { args -> client.callTool(def.name, args) }
        }
    }
