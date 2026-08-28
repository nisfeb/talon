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
 * JSON wire for the %trunk agent. Shapes mirror lib/trunk-json.hoon
 * in github.com/gwbtc/trunk exactly — that file is the source of
 * truth, and it is mirrored here by hand, so the two can drift:
 *
 *   action  {"send":{"ship":"~zod","sig":{...}}}
 *   sig     {"ring":{"id":i}} | {"offer":{"id":i,"sdp":s,"fpr":f}}
 *           {"accept":{...}}  | {"reject":{"id":i,"reason":r}}
 *           {"hangup":{"id":i}}
 *   update  {"recv":{"from":"~zod","sig":{...}}}
 *   policy  {"mode":"open","allow":["~zod"],"block":["~bus"]}
 */
sealed interface TrunkSig {
    val id: String

    data class Ring(override val id: String) : TrunkSig
    data class Offer(override val id: String, val sdp: String, val fpr: String) : TrunkSig
    data class Accept(override val id: String, val sdp: String, val fpr: String) : TrunkSig
    data class Reject(override val id: String, val reason: String) : TrunkSig
    data class Hangup(override val id: String) : TrunkSig
}

/**
 * A party line as its host describes it. [customSfu] says the room
 * runs on a sidecar the group chose rather than the host ship's own;
 * the secret behind it never leaves the ship, so only [sfuBase] is
 * visible here.
 */
data class PartyRoom(
    val name: String,
    val title: String,
    val listen: Boolean,
    val sfuBase: String,
    val customSfu: Boolean,
    val members: Set<String> = emptySet(),
    val admins: Set<String> = emptySet(),
)

/** A line another ship has invited us onto. */
data class PartyInvite(
    val host: String,
    val name: String,
    val title: String,
    val listen: Boolean,
    val sfuBase: String,
)

/** Where a room's audio runs. Null means the host ship's own sidecar. */
data class SfuConfig(val base: String, val group: String, val key: String)

/** Authorization to join one party line, minted by the host's ship. */
data class TrunkTicket(val name: String, val location: String, val token: String)

/**
 * Who may ring this ship. Enforced by the agent, not here — the client
 * only reads and edits it. [block] always applies; [mode] decides what
 * happens to everyone who isn't blocked.
 */
data class CallPolicy(
    val mode: Mode = Mode.Open,
    val allow: Set<String> = emptySet(),
    val block: Set<String> = emptySet(),
) {
    enum class Mode(val wire: String) {
        /** Anyone may ring, except blocked ships. */
        Open("open"),

        /** Only ships in [allow] may ring. */
        Allow("allow"),
        ;

        companion object {
            fun from(wire: String): Mode = entries.firstOrNull { it.wire == wire } ?: Open
        }
    }
}

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

    /**
     * Echoed after every policy edit so a ship's other devices
     * converge without re-scrying. [from] is always our own ship.
     */
    data class Policy(override val from: String, val policy: CallPolicy) : TrunkUpdate

    /** A freshly minted anonymous listen link, for the user to share. */
    /** The host opened (or reconfigured) a line we're a member of. */
    data class Open(override val from: String, val invite: PartyInvite) : TrunkUpdate

    /** The host closed a line. */
    data class Shut(override val from: String, val name: String) : TrunkUpdate

    data class ListenLink(
        override val from: String,
        val room: String,
        val url: String,
        val expiresSecs: Long,
    ) : TrunkUpdate
}

object TrunkWire {
    const val AGENT = "trunk"
    const val ACTION_MARK = "trunk-action"
    const val CALLS_PATH = "/calls"

    /**
     * Where %trunk comes from when a ship doesn't have it. Calls need
     * the desk on BOTH ships, and it isn't part of %base, so the app
     * offers to fetch it rather than leaving the call button dead.
     */
    const val PUBLISHER = "~ricsul-bilwyt"
    const val DESK = "trunk"
    private const val KILN_AGENT = "hood"
    private const val KILN_INSTALL_MARK = "kiln-install"
    private const val KILN_REVIVE_MARK = "kiln-revive"

    /**
     * Ask our own %hood to install [DESK] from [PUBLISHER]. %kiln's
     * install mark takes json, so this needs no dojo — it is the same
     * action as `|install ~ricsul-bilwyt %trunk`.
     */
    fun installTrunkPoke(
        publisher: String = PUBLISHER,
    ): Triple<String, String, JsonElement> = Triple(
        KILN_AGENT,
        KILN_INSTALL_MARK,
        buildJsonObject {
            put("local", DESK)
            put("ship", publisher)
            put("desk", DESK)
        },
    )

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

    /**
     * Host a party line: [members] are the ships allowed to join, and
     * [admins] the ships allowed to reconfigure it afterwards without
     * owning the host ship. %trunk has no idea what a Tlon group is —
     * the host seeds this list from the group's own roster.
     */
    fun openRoomAction(
        name: String,
        title: String,
        members: List<String>,
        admins: List<String> = emptyList(),
    ): JsonElement =
        buildJsonObject {
            putJsonObject("open-room") {
                put("name", name)
                put("title", title)
                putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("admins") { admins.forEach { add(JsonPrimitive(it)) } }
            }
        }

    /**
     * Ask a remote host to reconfigure a line we administer. The host
     * checks we are on its admin list; a non-admin is ignored.
     */
    fun configureRoomAction(
        host: String,
        name: String,
        open: Boolean,
        listen: Boolean,
        /** Null means the host ship's own sidecar. Ignored entirely
         *  when [keepSfu] — which is what every call that isn't the
         *  server picker wants, or toggling listening would silently
         *  reset a group's chosen server. */
        sfu: SfuConfig? = null,
        keepSfu: Boolean = true,
        /** Only used when the room doesn't exist yet — an admin
         *  turning the line on for the first time. */
        title: String = "",
        members: List<String> = emptyList(),
        admins: List<String> = emptyList(),
    ): JsonElement = buildJsonObject {
        putJsonObject("configure-room") {
            put("host", host); put("name", name)
            put("open", open); put("listen", listen)
            put("keep-sfu", keepSfu)
            put("title", title)
            putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("admins") { admins.forEach { add(JsonPrimitive(it)) } }
            if (sfu == null) {
                put("sfu", kotlinx.serialization.json.JsonNull)
            } else {
                putJsonObject("sfu") {
                    put("base", sfu.base); put("group", sfu.group); put("key", sfu.key)
                }
            }
        }
    }

    /** Allow (or stop allowing) anonymous listen links for a room. */
    fun setRoomListenAction(name: String, listen: Boolean): JsonElement = buildJsonObject {
        putJsonObject("set-room-listen") { put("name", name); put("listen", listen) }
    }

    /**
     * Mint a listen link. [ttlSecs] is the whole security model: the
     * link is a bearer token and Galène cannot revoke one, so the ship
     * caps it regardless of what we ask for.
     */
    fun shareRoomAction(host: String, name: String, ttlSecs: Int): JsonElement =
        buildJsonObject {
            putJsonObject("share-room") {
                put("host", host); put("name", name); put("ttl", ttlSecs)
            }
        }

    /** The sidecar this build ships with, or null if it has none. */
    fun defaultSfu(): SfuConfig? =
        io.nisfeb.talon.TalonBuild.defaultSfuBase.takeIf { it.isNotEmpty() }?.let {
            SfuConfig(
                it,
                io.nisfeb.talon.TalonBuild.defaultSfuGroup,
                io.nisfeb.talon.TalonBuild.defaultSfuKey,
            )
        }

    fun closeRoomAction(name: String): JsonElement = buildJsonObject {
        putJsonObject("close-room") { put("name", name) }
    }

    fun setCallModeAction(mode: CallPolicy.Mode): JsonElement =
        buildJsonObject { put("set-call-mode", mode.wire) }

    /** Add or drop one ship from the allow or block list. */
    fun allowAction(peer: String, allowed: Boolean): JsonElement =
        buildJsonObject { put(if (allowed) "allow" else "unallow", peer) }

    fun blockAction(peer: String, blocked: Boolean): JsonElement =
        buildJsonObject { put(if (blocked) "block" else "unblock", peer) }

    /** Point this ship at its sidecar's SFU. */
    fun setSfuAction(base: String, group: String, key: String): JsonElement = buildJsonObject {
        putJsonObject("set-sfu") { put("base", base); put("group", group); put("key", key) }
    }

    /**
     * Resume a suspended desk — `|revive %trunk`. A ship that once had
     * %trunk and removed it keeps the desk suspended, and a fresh
     * install re-syncs it without starting it, so the agent never
     * answers. Harmless on a desk that isn't suspended.
     */
    fun reviveTrunkPoke(): Triple<String, String, JsonElement> =
        Triple(KILN_AGENT, KILN_REVIVE_MARK, JsonPrimitive(DESK))

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
        (obj["open"] as? JsonObject)?.let { o ->
            val host = who(o) ?: return null
            return TrunkUpdate.Open(
                host,
                PartyInvite(
                    host = host,
                    name = o["name"]?.jsonPrimitive?.content ?: return null,
                    title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                    listen = o["listen"]?.jsonPrimitive?.content == "true",
                    sfuBase = o["sfu-base"]?.jsonPrimitive?.content.orEmpty(),
                ),
            )
        }
        (obj["shut"] as? JsonObject)?.let { o ->
            return TrunkUpdate.Shut(
                who(o) ?: return null,
                o["name"]?.jsonPrimitive?.content ?: return null,
            )
        }
        (obj["listen-link"] as? JsonObject)?.let { l ->
            return TrunkUpdate.ListenLink(
                from = "",
                room = l["name"]?.jsonPrimitive?.content ?: return null,
                url = l["url"]?.jsonPrimitive?.content ?: return null,
                expiresSecs = l["expires"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
        (obj["policy"] as? JsonObject)?.let { p ->
            return TrunkUpdate.Policy("", parsePolicy(p))
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

    /** Parse the /x/rooms scry body: the lines this ship hosts. */
    fun parseRooms(body: JsonElement): List<PartyRoom> =
        (body as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun ships(k: String) =
                (o[k] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
            PartyRoom(
                name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                listen = o["listen"]?.jsonPrimitive?.content == "true",
                sfuBase = o["sfu-base"]?.jsonPrimitive?.content.orEmpty(),
                customSfu = o["custom-sfu"]?.jsonPrimitive?.content == "true",
                members = ships("members"),
                admins = ships("admins"),
            )
        } ?: emptyList()

    /** Parse the /x/lines scry body: lines we've been invited onto. */
    fun parseLines(body: JsonElement): List<PartyInvite> =
        (body as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PartyInvite(
                host = o["host"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                listen = o["listen"]?.jsonPrimitive?.content == "true",
                sfuBase = o["sfu-base"]?.jsonPrimitive?.content.orEmpty(),
            )
        } ?: emptyList()

    /** Parse the /x/policy scry body, or a %policy fact's payload. */
    fun parsePolicy(body: JsonElement): CallPolicy {
        val o = body as? JsonObject ?: return CallPolicy()
        fun ships(k: String): Set<String> =
            (o[k] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                ?.toSet()
                ?: emptySet()
        return CallPolicy(
            mode = CallPolicy.Mode.from(o["mode"]?.jsonPrimitive?.content ?: "open"),
            allow = ships("allow"),
            block = ships("block"),
        )
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
