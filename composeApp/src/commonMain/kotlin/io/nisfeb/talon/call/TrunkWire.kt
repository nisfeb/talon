package io.nisfeb.talon.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON wire for the %trunk agent. Shapes mirror urbit/trunk/lib/
 * trunk-json.hoon exactly — that file is the source of truth:
 *
 *   action  {"send":{"ship":"~zod","sig":{...}}}
 *   sig     {"ring":{"id":i}} | {"offer":{"id":i,"sdp":s,"fpr":f}}
 *           {"accept":{...}}  | {"reject":{"id":i,"reason":r}}
 *           {"hangup":{"id":i}}
 *   update  {"recv":{"from":"~zod","sig":{...}}}
 */
sealed interface TrunkSig {
    val id: String

    data class Ring(override val id: String) : TrunkSig
    data class Offer(override val id: String, val sdp: String, val fpr: String) : TrunkSig
    data class Accept(override val id: String, val sdp: String, val fpr: String) : TrunkSig
    data class Reject(override val id: String, val reason: String) : TrunkSig
    data class Hangup(override val id: String) : TrunkSig
}

/** Authorization to join one party line, minted by the host's ship. */
data class TrunkTicket(val name: String, val location: String, val token: String)

/** Anything the agent surfaces on /calls. */
sealed interface TrunkUpdate {
    val from: String

    data class Recv(override val from: String, val sig: TrunkSig) : TrunkUpdate
    data class Ticket(override val from: String, val ticket: TrunkTicket) : TrunkUpdate
    data class Denied(
        override val from: String,
        val name: String,
        val why: String,
    ) : TrunkUpdate
}

object TrunkWire {
    const val AGENT = "trunk"
    const val ACTION_MARK = "trunk-action"
    const val CALLS_PATH = "/calls"

    /** [%send ship sig] poke payload for our own %trunk. */
    fun sendAction(peer: String, sig: TrunkSig): JsonElement = buildJsonObject {
        putJsonObject("send") {
            put("ship", peer)
            put("sig", sigToJson(sig))
        }
    }

    private fun sigToJson(sig: TrunkSig): JsonObject = buildJsonObject {
        when (sig) {
            is TrunkSig.Ring -> putJsonObject("ring") { put("id", sig.id) }
            is TrunkSig.Offer -> putJsonObject("offer") {
                put("id", sig.id); put("sdp", sig.sdp); put("fpr", sig.fpr)
            }
            is TrunkSig.Accept -> putJsonObject("accept") {
                put("id", sig.id); put("sdp", sig.sdp); put("fpr", sig.fpr)
            }
            is TrunkSig.Reject -> putJsonObject("reject") {
                put("id", sig.id); put("reason", sig.reason)
            }
            is TrunkSig.Hangup -> putJsonObject("hangup") { put("id", sig.id) }
        }
    }

    /** Ask our ship to join a party line hosted by [host]. */
    fun joinRoomAction(host: String, name: String): JsonElement = buildJsonObject {
        putJsonObject("join-room") { put("host", host); put("name", name) }
    }

    /** Host a party line: [members] are the ships allowed to join. */
    fun openRoomAction(name: String, title: String, members: List<String>): JsonElement =
        buildJsonObject {
            putJsonObject("open-room") {
                put("name", name)
                put("title", title)
                putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
            }
        }

    fun closeRoomAction(name: String): JsonElement = buildJsonObject {
        putJsonObject("close-room") { put("name", name) }
    }

    /** Point this ship at its sidecar's SFU. */
    fun setSfuAction(base: String, group: String, key: String): JsonElement = buildJsonObject {
        putJsonObject("set-sfu") { put("base", base); put("group", group); put("key", key) }
    }

    /** Parse a /calls fact. Null for anything that isn't a trunk update. */
    fun parseUpdate(body: JsonElement): TrunkUpdate? {
        val obj = body as? JsonObject ?: return null
        // Belt + suspenders on `from`: the agent sends "~feb", but
        // normalize anyway so a stale desk can't silently break the
        // reply path (enjs's +ship drops the leading sig).
        fun who(o: JsonObject): String? =
            o["from"]?.jsonPrimitive?.content
                ?.let { if (it.startsWith("~")) it else "~${'$'}it" }

        (obj["recv"] as? JsonObject)?.let { recv ->
            val from = who(recv) ?: return null
            val sig = parseSig(recv["sig"] as? JsonObject ?: return null) ?: return null
            return TrunkUpdate.Recv(from, sig)
        }
        (obj["ticket"] as? JsonObject)?.let { t ->
            return TrunkUpdate.Ticket(
                from = who(t) ?: return null,
                ticket = TrunkTicket(
                    name = t["name"]?.jsonPrimitive?.content ?: return null,
                    location = t["location"]?.jsonPrimitive?.content ?: return null,
                    token = t["token"]?.jsonPrimitive?.content ?: return null,
                ),
            )
        }
        (obj["denied"] as? JsonObject)?.let { d ->
            return TrunkUpdate.Denied(
                from = who(d) ?: return null,
                name = d["name"]?.jsonPrimitive?.content ?: "",
                why = d["why"]?.jsonPrimitive?.content ?: "",
            )
        }
        return null
    }

    private fun parseSig(sig: JsonObject): TrunkSig? {
        fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
        sig["ring"]?.jsonObject?.let { return TrunkSig.Ring(it.str("id") ?: return null) }
        sig["offer"]?.jsonObject?.let {
            return TrunkSig.Offer(
                it.str("id") ?: return null,
                it.str("sdp") ?: return null,
                it.str("fpr") ?: return null,
            )
        }
        sig["accept"]?.jsonObject?.let {
            return TrunkSig.Accept(
                it.str("id") ?: return null,
                it.str("sdp") ?: return null,
                it.str("fpr") ?: return null,
            )
        }
        sig["reject"]?.jsonObject?.let {
            return TrunkSig.Reject(it.str("id") ?: return null, it.str("reason") ?: "")
        }
        sig["hangup"]?.jsonObject?.let { return TrunkSig.Hangup(it.str("id") ?: return null) }
        return null
    }

    /** Parse the /x/ice scry body: [{"url":u,"user":s,"cred":c}, …]. */
    fun parseIce(body: JsonElement): List<IceServer> =
        (body as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            IceServer(
                url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                user = o["user"]?.jsonPrimitive?.content ?: "",
                cred = o["cred"]?.jsonPrimitive?.content ?: "",
            )
        } ?: emptyList()

    val json = Json { ignoreUnknownKeys = true }
}
