package io.nisfeb.talon.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", nextId.getAndIncrement())
                put("method", method)
                put("params", params)
            }
            http.newCall(post(payload)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: error("mcp $method: HTTP ${resp.code}, non-JSON body")
                obj["error"]?.jsonObject?.let { err ->
                    throw McpException(
                        err["code"]?.jsonPrimitive?.intOrNull ?: -1,
                        err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error",
                    )
                }
                obj["result"] ?: JsonObject(emptyMap())
            }
        }

    /** Fire a notification (no id, no response body expected). */
    private suspend fun notify(method: String) {
        withContext(Dispatchers.IO) {
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

        /** Flatten an MCP `tools/call` result's content blocks to plain
         *  text for the model. Pure. */
        internal fun extractToolText(result: JsonObject): String {
            val content = result["content"]?.jsonArray
                ?: return "(no output)"
            val text = content
                .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                .joinToString("\n")
            val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
            return when {
                isError -> "Error: ${text.ifBlank { "the tool reported a failure" }}"
                text.isBlank() -> "(no output)"
                else -> text
            }
        }
    }
}
