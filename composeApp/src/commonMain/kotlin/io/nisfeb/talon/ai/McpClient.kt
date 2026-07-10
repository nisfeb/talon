package io.nisfeb.talon.ai
import io.nisfeb.talon.util.ioDispatcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal client for the ship's MCP server (Gall agent `%mcp-server`,
 * see ~/software/groundwire/urbit-mcp-server). It's served through Eyre
 * at `/mcp` on the ship's normal HTTP host, authenticated by the same
 * Eyre session cookie Talon already holds — so we reuse the session's
 * OkHttp client + base URL and add no new auth.
 *
 * Transport note: despite advertising SSE, every JSON-RPC method on this
 * server replies as a single JSON body on the POST's own connection
 * (deferred ops like `tools/call` just hold the connection open until the
 * khan thread finishes, then reply on the same connection — confirmed in
 * the agent's `+send-event` = `give-simple-payload`). So this is plain
 * request/response: no SSE stream, no session id, no out-of-order
 * correlation. The GET SSE endpoint exists only for server-pushed
 * `list_changed` notifications, which we don't need.
 */
class McpException(val code: Int, message: String) : RuntimeException(message)

data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/**
 * Session-lifetime cache of the MCP handshake. The assistant screen is
 * unmounted whenever it's closed, which used to discard its remembered
 * client and re-run initialize + tools/list (two ship round-trips, plus a
 * "Connecting to ship tools…" flash) on every reopen.
 *
 * Keyed on the session's OkHttp identity, not just the ship URL: the
 * McpClient copies the session's cookie jar, and sign-out clears that jar
 * while re-login mints a NEW session + jar. A URL-only key kept serving
 * the dead client — tools advertised, every call unauthenticated — until
 * relaunch. New session ⇒ new http instance ⇒ cache miss ⇒ fresh
 * handshake. Single entry, so stale sessions drop out on their own.
 *
 * ponytail: a ship that starts exposing new MCP tools mid-session won't
 * see them until reconnect. Add an explicit refresh if that ever bites.
 */
object McpSessions {
    // The OkHttpClient reference IS the identity (no equals override) —
    // collision-proof where an identityHashCode Int is not, and the entry
    // already transitively retains the pool/jar, so no new leak.
    private var key: Pair<OkHttpClient, String>? = null
    private var entry: Pair<McpClient, List<McpToolDef>>? = null

    fun cached(http: OkHttpClient, baseUrl: String): Pair<McpClient, List<McpToolDef>>? =
        entry.takeIf { key == http to baseUrl }

    fun put(http: OkHttpClient, baseUrl: String, client: McpClient, defs: List<McpToolDef>) {
        key = http to baseUrl
        entry = client to defs
    }
}

class McpClient(
    parentHttp: OkHttpClient,
    baseUrl: HttpUrl,
) {
    // tools/call can be slow (a khan thread on the ship). The session's
    // client carries readTimeout=0 for SSE; bound our calls instead.
    private val http: OkHttpClient = parentHttp.newBuilder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val endpoint: HttpUrl = baseUrl.newBuilder().addPathSegment("mcp").build()
    private val nextId = AtomicInteger(1)

    @Volatile private var initialized = false

    private fun post(payload: JsonObject): Request =
        Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

    /** Issue one JSON-RPC call and return its `result`, throwing
     *  [McpException] on a JSON-RPC error frame. */
    private suspend fun rpc(method: String, params: JsonObject): JsonElement =
        withContext(ioDispatcher) {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId.getAndIncrement())
                put("method", method)
                put("params", params)
            }
            http.newCall(post(payload)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: error(diagnoseFailure(method, resp.code, body))
                obj["error"]?.jsonObject?.let { err ->
                    throw McpException(
                        err["code"]?.jsonPrimitive?.intOrNull ?: -1,
                        err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error",
                    )
                }
                obj["result"] ?: JsonObject(emptyMap())
            }
        }

    /** Turn an unparseable HTTP response into an actionable message.
     *  The ship's MCP server rejects requests it can't confirm are secure
     *  with a *bodyless* 400 (a cleartext/rebinding guard, before auth or
     *  body parsing — see mcp-server.hoon), which otherwise surfaces as a
     *  baffling "non-JSON body". Name the likely cause when we can. */
    private fun diagnoseFailure(method: String, code: Int, body: String): String {
        val detail = body.trim().take(200)
        if (code == 400 && detail.isEmpty()) {
            val hint = if (endpoint.scheme == "http" && !isLoopbackHost(endpoint.host)) {
                "Talon is on http to ${endpoint.host} — reconnect using your ship's https:// URL."
            } else {
                "Your ship treated the request as insecure. If it's behind a reverse proxy " +
                    "(nginx), the proxy must send X-Forwarded-Proto and the MCP server must trust it."
            }
            return "mcp $method: HTTP 400 — the ship's MCP server rejected the request before " +
                "processing it. $hint"
        }
        return "mcp $method: HTTP $code — ${detail.ifBlank { "(empty response body)" }}"
    }

    /** Fire a notification (no id, no response body expected). */
    private suspend fun notify(method: String) {
        withContext(ioDispatcher) {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
            }
            runCatching { http.newCall(post(payload)).execute().close() }
        }
    }

    /** MCP handshake. Idempotent — safe to call before each session. */
    suspend fun initialize() {
        if (initialized) return
        rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "talon")
                        put("version", "1.0.0")
                    },
                )
            },
        )
        notify("notifications/initialized")
        initialized = true
    }

    suspend fun listTools(): List<McpToolDef> {
        val tools = rpc("tools/list", buildJsonObject {}).jsonObject["tools"]?.jsonArray
            ?: return emptyList()
        return tools.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpToolDef(
                name = name,
                description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): String {
        val result = rpc(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        ).jsonObject
        return extractToolText(result)
    }

    companion object {
        const val MCP_PROTOCOL_VERSION = "2025-11-25"
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Loopback host per the ship's own loopback-authority check, so
         *  Talon's diagnostic matches when the server's HTTPS gate fires. */
        internal fun isLoopbackHost(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]") // strip IPv6 brackets
            return h == "localhost" || h == "::1" || h.startsWith("127.")
        }

        /** Flatten an MCP `tools/call` result to plain text for the model.
         *  Pure. Handles all three shapes the ship's server emits:
         *   - unstructured `content` blocks (`%text`, and `%resource` whose
         *     text is nested under `resource.text`);
         *   - structured tools (e.g. get-our-id) that send `structuredContent`
         *     with NO text block — without this they read as "(no output)";
         *   - `isError` flagged failures. */
        internal fun extractToolText(result: JsonObject): String {
            val blocks = (result["content"] as? JsonArray) ?: JsonArray(emptyList())
            val text = blocks
                .mapNotNull { block ->
                    val o = block as? JsonObject ?: return@mapNotNull null
                    o["text"]?.jsonPrimitive?.contentOrNull
                        ?: (o["resource"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }
                .joinToString("\n")
            val structured = result["structuredContent"]
                ?.takeUnless { it is JsonNull }
                ?.toString()
            val combined = listOfNotNull(
                text.takeIf { it.isNotBlank() },
                structured?.takeIf { it.isNotBlank() && it != text },
            ).joinToString("\n")
            val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
            return when {
                isError -> "Error: ${combined.ifBlank { "the tool reported a failure" }}"
                combined.isBlank() -> "(no output)"
                else -> combined
            }
        }
    }
}
