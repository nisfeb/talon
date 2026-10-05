package io.nisfeb.talon.notify

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.nisfeb.talon.urbit.asText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The ship's own pushes: grubbery's web push (app/grubbery.hoon,
 * handle-push-http), which a browser subscribes to and which a phone's
 * UnifiedPush endpoint can take as well. Calendar reminders and orrery's
 * time-to-leave come this way, not through the Talon relay.
 */

/** One push as grubbery sends it (lib/web-push.hoon): title, body, and an optional tag. Its url is not used. */
data class ShipPushMessage(val title: String, val body: String, val tag: String?)

fun parseShipPush(text: String): ShipPushMessage? {
    val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return ShipPushMessage(
        title = o["title"].asText()?.takeIf { it.isNotBlank() } ?: return null,
        body = o["body"].asText().orEmpty(),
        tag = o["tag"].asText()?.takeIf { it.isNotBlank() },
    )
}

class ShipPushRefused(val status: Int, said: String) : Exception("HTTP $status: ${said.take(200)}")

/** The ship's push routes, under the owner's session (grubbery refuses them unauthenticated). */
class ShipPushApi(private val http: HttpClient, baseUrl: String) {
    private val root = baseUrl.trimEnd('/') + "/grubbery/push"

    /** The ship's VAPID public key, base64url: what the distributor checks the pushes against. */
    suspend fun vapidKey(): String = ok(http.get("$root/vapid-key")).trim()

    /**
     * Subscribe [endpoint] with its keys (base64url: p256dh 65 bytes,
     * auth 16), the shape a browser sends. The ship's id for it, which
     * is a hash of the endpoint, so the same endpoint twice is one.
     */
    suspend fun subscribe(endpoint: String, p256dh: String, auth: String): String {
        val text = ok(http.post("$root/subscribe") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("endpoint", endpoint); put("p256dh", p256dh); put("auth", auth) }.toString())
        })
        return runCatching { Json.parseToJsonElement(text).jsonObject["sub_id"].asText() }.getOrNull()
            ?: throw ShipPushRefused(200, "no sub_id in $text")
    }

    suspend fun unsubscribe(subId: String) {
        ok(http.post("$root/unsubscribe") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("sub_id", subId) }.toString())
        })
    }

    private suspend fun ok(resp: HttpResponse): String {
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw ShipPushRefused(resp.status.value, text)
        return text
    }
}
