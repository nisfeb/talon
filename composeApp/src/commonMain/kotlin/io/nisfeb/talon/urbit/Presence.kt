package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * %presence — short-lived activity indicators ("~bob is typing").
 * Shipped in the default groups desk as of Tlon v11.4.0; older ships
 * don't run the agent, so every call here is best-effort and a failed
 * subscribe/poke must be survivable.
 *
 * The wire, from desk/sur/presence.hoon + desk/lib/presence-json.hoon:
 *
 *  - We poke `%presence-action-1` at our OWN ship. It rewrites the
 *    action into a `%command` and forwards it to the context's host.
 *  - We subscribe `/v1` and receive `%presence-response-1` facts:
 *    `{init: {…}}` on attach, then `{here: {…}}` / `{gone: {…}}`.
 *  - A `context` is a path. DMs are `/dm/~peer`; channels are
 *    `/channel/<kind>/~host/<name>`. Clubs have no context — the
 *    agent's `+context-host` crashes on anything else, so we must not
 *    announce in one.
 *
 * Expiry is the subtle part. Each entry carries `timing.timeout` (30s
 * for typing) and the host does NOT push a `%gone` when an entry
 * simply times out — only an explicit `%clear` propagates. So a client
 * must expire peers locally, and must re-announce before its own entry
 * lapses.
 */
object Presence {

    const val TOPIC_TYPING = "typing"

    /** The agent's `+default-timeout`, in millis. */
    fun defaultTimeoutMs(topic: String): Long = when (topic) {
        "computing" -> 60_000L
        else -> 30_000L // typing, other
    }

    /**
     * Our own typing entry lapses after [defaultTimeoutMs]; re-announce
     * at half that so a slow poke never leaves a gap.
     */
    const val REANNOUNCE_MS = 15_000L

    /** The `@dr` we ask for. Matches the agent's typing default. */
    private const val TYPING_TIMEOUT_DR = "~s30"

    /**
     * The presence context for a Talon `whom`, or null when the
     * conversation has no presence context (clubs).
     *
     * A DM's context names the *peer* in both directions: we announce
     * under `/dm/~peer`, and the agent rewrites an incoming peer fact
     * from their `/dm/~us` into our `/dm/~them` before storing it.
     */
    fun contextFor(whom: String): String? = when {
        whom.startsWith("~") -> "/dm/$whom"
        whom.startsWith("chat/") || whom.startsWith("diary/") || whom.startsWith("heap/") ->
            "/channel/$whom"
        else -> null
    }

    private fun key(context: String, ship: String, topic: String) = buildJsonObject {
        put("context", context)
        put("ship", ship)
        put("topic", topic)
    }

    /**
     * `%set`: announce that [ship] is doing [topic] in [context].
     * An empty `disclose` means public — everyone watching the context
     * sees it. `display` keys are all required, even when null.
     */
    fun setAction(
        context: String,
        ship: String,
        topic: String = TOPIC_TYPING,
    ): JsonObject = buildJsonObject {
        put(
            "set",
            buildJsonObject {
                put("disclose", buildJsonArray { })
                put("key", key(context, ship, topic))
                put("timeout", TYPING_TIMEOUT_DR)
                put(
                    "display",
                    buildJsonObject {
                        put("icon", JsonNull)
                        put("text", JsonNull)
                        put("blob", JsonNull)
                    },
                )
            },
        )
    }

    /** `%clear`: retract early. Unlike a timeout, this DOES propagate. */
    fun clearAction(
        context: String,
        ship: String,
        topic: String = TOPIC_TYPING,
    ): JsonObject = buildJsonObject {
        put("clear", key(context, ship, topic))
    }

    /**
     * Parse an `@dr` as serialized by `scot %dr` — `~s30`, `~m1`,
     * `~m1.s30`, `~d1.h2`. Returns null on anything unrecognized so the
     * caller can fall back to the topic default rather than trusting a
     * zero.
     */
    fun parseDurationMs(dr: String?): Long? {
        val body = dr?.removePrefix("~")?.takeIf { it.isNotBlank() } ?: return null
        var total = 0L
        for (segment in body.split('.')) {
            if (segment.length < 2) return null
            val n = segment.drop(1).toLongOrNull() ?: return null
            total += when (segment.first()) {
                'd' -> n * 86_400_000L
                'h' -> n * 3_600_000L
                'm' -> n * 60_000L
                's' -> n * 1_000L
                else -> return null
            }
        }
        return total
    }

    /** One live presence entry, already resolved to a wall-clock expiry. */
    data class Entry(
        val context: String,
        val ship: String,
        val topic: String,
        val timeoutMs: Long,
    )

    /**
     * Interpret one `%presence-response-1` fact. Returns the entries it
     * asserts and the keys it retracts. `{init: …}` is a full snapshot,
     * so it also clears everything not mentioned — the caller signals
     * that by looking at [snapshot].
     */
    data class Update(
        val here: List<Entry> = emptyList(),
        val gone: List<Entry> = emptyList(),
        val snapshot: Boolean = false,
    )

    private fun timeoutOf(entry: JsonObject?, topic: String): Long {
        val timing = entry?.get("timing") as? JsonObject
        return parseDurationMs(timing?.get("timeout").asStr()) ?: defaultTimeoutMs(topic)
    }

    fun parseResponse(payload: JsonObject): Update? {
        (payload["init"] as? JsonObject)?.let { places ->
            val entries = mutableListOf<Entry>()
            places.forEach { (context, topics) ->
                (topics as? JsonObject)?.forEach { (topic, people) ->
                    (people as? JsonObject)?.forEach { (ship, body) ->
                        entries.add(
                            Entry(context, ship, topic, timeoutOf(body as? JsonObject, topic)),
                        )
                    }
                }
            }
            return Update(here = entries, snapshot = true)
        }
        (payload["here"] as? JsonObject)?.let { here ->
            val k = here["key"] as? JsonObject ?: return null
            val context = k["context"].asStr() ?: return null
            val ship = k["ship"].asStr() ?: return null
            val topic = k["topic"].asStr() ?: return null
            return Update(here = listOf(Entry(context, ship, topic, timeoutOf(here, topic))))
        }
        (payload["gone"] as? JsonObject)?.let { k ->
            val context = k["context"].asStr() ?: return null
            val ship = k["ship"].asStr() ?: return null
            val topic = k["topic"].asStr() ?: return null
            return Update(gone = listOf(Entry(context, ship, topic, 0L)))
        }
        return null
    }
}
