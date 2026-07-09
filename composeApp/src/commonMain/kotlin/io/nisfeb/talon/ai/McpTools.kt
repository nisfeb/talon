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
    // even if a tool is renamed or namespaced differently. Match the word,
    // not the substring — `retrieval-search` is not an eval tool. A name
    // that STARTS with the word still counts, so `evaluate-code` is hidden.
    val words = short.split('-', '_', '.', ':')
    if (short in MCP_HIDDEN_TOOLS ||
        short.startsWith("eval") || short.startsWith("install") ||
        words.any { it == "eval" || it == "install" }
    ) {
        return McpVisibility.Hidden
    }
    if (short in MCP_READ_TOOLS || short.startsWith("scry")) return McpVisibility.Read
    return McpVisibility.Write
}

/** Map discovered MCP tool defs to agent [Tool]s, dropping hidden ones.
 *
 *  The model-facing tool name is sanitized to the provider-required
 *  `^[A-Za-z0-9_-]{1,64}$`: MCP names are routinely namespaced (e.g.
 *  `chat/send`, `%settings:get`) and OpenAI/OpenRouter/Anthropic all
 *  reject a `/`, `:` or `%` in a function name with a 400 that fails the
 *  WHOLE request — not just that tool. Dispatch still uses the original
 *  [McpToolDef.name] against the server; only what the model sees and
 *  calls back by is sanitized (so the [AgentLoop] name→tool lookup, which
 *  keys on [ToolSpec.name], still resolves). Names are de-duplicated
 *  because sanitization can map distinct originals onto the same string. */
fun mcpAgentTools(client: McpClient, defs: List<McpToolDef>): List<Tool> {
    val used = mutableSetOf<String>()
    return defs.mapNotNull { def ->
        val visibility = mcpToolVisibility(def.name)
        if (visibility == McpVisibility.Hidden) return@mapNotNull null
        val safeName = uniqueToolName(sanitizeToolName(def.name), used)
        Tool(
            spec = ToolSpec(safeName, def.description, def.inputSchema),
            write = visibility == McpVisibility.Write,
        ) { args -> client.callTool(def.name, args) }
    }
}

/** Coerce a name to `^[A-Za-z0-9_-]{1,64}$`: ASCII alphanumerics, `_` and
 *  `-` pass; everything else becomes `_`. Trims leading/trailing `_`, caps
 *  length, and never returns blank. 64 (not 128) satisfies every provider
 *  dialect's limit, Anthropic's being the tightest. */
internal fun sanitizeToolName(name: String): String {
    val cleaned = buildString {
        for (c in name) {
            append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_')
        }
    }.trim('_').take(64)
    return cleaned.ifBlank { "tool" }
}

/** Ensure a name is unique within [used] by appending `_2`, `_3`, … —
 *  two distinct MCP names can sanitize to the same string, which would
 *  otherwise collide in the agent's name→tool map. */
private fun uniqueToolName(base: String, used: MutableSet<String>): String {
    if (used.add(base)) return base
    var i = 2
    while (true) {
        val candidate = "${base.take(60)}_$i"
        if (used.add(candidate)) return candidate
        i++
    }
}
