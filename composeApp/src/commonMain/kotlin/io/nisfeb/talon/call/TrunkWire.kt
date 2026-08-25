package io.nisfeb.talon.call

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

data class TrunkRecv(val from: String, val sig: TrunkSig)

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

    /** Parse a /calls fact. Null for anything that isn't a trunk update. */
    fun parseUpdate(body: JsonElement): TrunkRecv? {
        val recv = (body as? JsonObject)?.get("recv") as? JsonObject ?: return null
        val fromRaw = recv["from"]?.jsonPrimitive?.content ?: return null
        // Belt + suspenders: the agent now sends "~feb", but normalize
        // anyway so a stale desk can't silently break the reply path.
        val from = if (fromRaw.startsWith("~")) fromRaw else "~$fromRaw"
        val sig = parseSig(recv["sig"] as? JsonObject ?: return null) ?: return null
        return TrunkRecv(from, sig)
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

    val json = Json { ignoreUnknownKeys = true }
}
