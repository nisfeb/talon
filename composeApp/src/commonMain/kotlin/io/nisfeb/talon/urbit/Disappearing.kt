package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.ExperimentalResourceApi
import talon.composeapp.generated.resources.Res

/**
 * Disappearing messages: a group setting that deletes every post, for
 * everyone, once it is older than a chosen time.
 *
 * It runs as a Tlon channel hook (files/disappearing.hoon) on the
 * channels-server of the ship that hosts each channel, so it keeps
 * working with every member's app closed. channels-server takes hook
 * pokes only from the ship itself, so only the host sets it, and only
 * on the channels it hosts. Per channel the hook gets the delay as
 * config, a place in the channel's hook order (new posts set their
 * timers there) and a daily cron that sweeps what no timer covers.
 * The cron's first run is at once, which is what deletes a history
 * already older than the delay.
 *
 * The wire is hooks-json's +dejs in tlon-apps (desk/lib/hooks-json.hoon).
 */
object Disappearing {
    const val HOOK_NAME = "Talon disappearing messages"

    const val MINUTE = 60L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    /** Signal's choices, longest first. */
    val CHOICES = listOf(4 * WEEK, WEEK, DAY, 8 * HOUR, HOUR, 5 * MINUTE, 30L)

    /** The longest custom delay. Keeps seconds << 64 well inside what [delayNoun] writes. */
    const val MAX_SECONDS = 365 * DAY

    /** The hook as it goes to the ship. The file is what was run on a fake ship. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun source(): String = Res.readBytes("files/disappearing.hoon").decodeToString()

    /** "1 week", "8 hours", "90 minutes": the largest unit that divides it. */
    fun label(seconds: Long): String {
        val (n, unit) = when {
            seconds % WEEK == 0L -> seconds / WEEK to "week"
            seconds % DAY == 0L -> seconds / DAY to "day"
            seconds % HOUR == 0L -> seconds / HOUR to "hour"
            seconds % MINUTE == 0L -> seconds / MINUTE to "minute"
            else -> seconds to "second"
        }
        return if (n == 1L) "1 $unit" else "$n ${unit}s"
    }

    private val LINE = Regex("Messages disappear after .+\\.")

    /** The group description with its line for [seconds], or with none when off. */
    fun descriptionWith(description: String, seconds: Long?): String {
        val rest = description.lines().filterNot { LINE.matches(it.trim()) }.joinToString("\n").trimEnd()
        val line = seconds?.let { "Messages disappear after ${label(it)}." }
        return listOfNotNull(rest.takeIf { it.isNotEmpty() }, line).joinToString("\n\n")
    }

    /** What the group is told in its chats when the setting changes. */
    fun notice(seconds: Long?): String =
        if (seconds == null) "Disappearing messages are off."
        else "Disappearing messages are on. Messages here are deleted for everyone ${label(seconds)} after they are sent."

    private const val UW = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-~"

    /**
     * `(scot %uw (jam (mul seconds ~s1)))`, the delay as hook config
     * carries it: the mark cues an @uw. ~s1 is 2^64, so the atom is
     * [seconds] over 64 zero bits. Written bit by bit because common
     * Kotlin has no big integers.
     */
    internal fun delayNoun(seconds: Long): String {
        require(seconds in 1..MAX_SECONDS) { "delay out of range: $seconds" }
        val sBits = 64 - seconds.countLeadingZeroBits()
        val b = sBits + 64
        val c = 32 - b.countLeadingZeroBits()
        val bits = ArrayList<Boolean>()
        bits += false // jam's tag: an atom
        repeat(c) { bits += false } // +mat: c zeros, a one, b's low c-1 bits, the atom
        bits += true
        for (i in 0 until c - 1) bits += (b shr i) and 1 == 1
        repeat(64) { bits += false }
        for (i in 0 until sBits) bits += (seconds shr i) and 1L == 1L
        val digits = bits.chunked(6) { six -> six.foldIndexed(0) { i, acc, bit -> if (bit) acc or (1 shl i) else acc } }
            .dropLastWhile { it == 0 }
        val text = digits.asReversed().joinToString("") { UW[it].toString() }
        return "0w" + text.reversed().chunked(5).joinToString(".").reversed()
    }

    /** [delayNoun] read back: whole seconds, or null for anything else. */
    internal fun delaySeconds(noun: String): Long? {
        if (!noun.startsWith("0w")) return null
        val digits = noun.removePrefix("0w").filter { it != '.' }.map { ch -> UW.indexOf(ch).takeIf { it >= 0 } ?: return null }
        val bits = digits.asReversed().flatMap { d -> List(6) { i -> (d shr i) and 1 == 1 } }
        var p = 0
        fun bit() = bits.getOrElse(p++) { false }
        if (bit()) return null // a cell
        var c = 0
        while (!bit()) if (++c > 16 || p > bits.size) return null
        if (c == 0) return null // the atom 0
        var b = 1 shl (c - 1)
        for (i in 0 until c - 1) if (bit()) b = b or (1 shl i)
        if (b <= 64 || b > 64 + 40) return null
        repeat(64) { if (bit()) return null } // a fraction of a second
        var s = 0L
        for (i in 0 until b - 64) if (bit()) s = s or (1L shl i)
        return s
    }

    /** What the host's channels-server holds of this hook, from its `/v0/hooks` scry. */
    data class Hooks(
        /** Our hook's id, or null where it was never added. */
        val id: String?,
        val src: String?,
        val compiled: Boolean,
        /** Each channel's delay, where the hook has one for it. */
        val delays: Map<String, Long>,
        /** Each channel's hook order: every hook, ours or not. */
        val order: Map<String, List<String>>,
        /** Channels our hook has a cron on. */
        val crons: Set<String>,
    ) {
        /** The delay in force on [nest]: configured, and in its order. */
        fun delayOn(nest: String): Long? = id?.takeIf { it in order[nest].orEmpty() }?.let { delays[nest] }

        companion object {
            fun parse(json: JsonElement): Hooks {
                val root = json as? JsonObject ?: JsonObject(emptyMap())
                val hooks = root["hooks"] as? JsonObject ?: JsonObject(emptyMap())
                val ours = hooks.values.mapNotNull { it as? JsonObject }.filter { it["name"].asStr() == HOOK_NAME }
                val order = (root["order"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                    (v as? JsonArray).orEmpty().mapNotNull { it.asStr() }
                }
                // Two adds that raced leave two: the one a channel runs, if any.
                val hook = ours.firstOrNull { h -> order.values.any { h["id"].asStr() in it } } ?: ours.firstOrNull()
                val id = hook?.get("id").asStr()
                val delays = (hook?.get("config") as? JsonObject).orEmpty().mapNotNull { (nest, cfg) ->
                    ((cfg as? JsonObject)?.get("delay").asStr())?.let(::delaySeconds)?.let { nest to it }
                }.toMap()
                val crons = ((root["crons"] as? JsonObject)?.get(id ?: "") as? JsonObject)?.keys.orEmpty()
                return Hooks(
                    id = id,
                    src = hook?.get("src").asStr(),
                    compiled = (hook?.get("compiled") as? JsonPrimitive)?.booleanOrNull == true,
                    delays = delays,
                    order = order,
                    crons = crons,
                )
            }
        }
    }

    private fun JsonElement?.asStr(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun add(src: String) = action("add") { put("name", HOOK_NAME); put("src", src) }

    internal fun edit(id: String, src: String) = action("edit") {
        put("id", id); put("name", JsonNull); put("src", src); put("meta", JsonNull)
    }

    internal fun config(id: String, nest: String, seconds: Long?) = action("config") {
        put("id", id); put("nest", nest); put("config", delayConfig(seconds))
    }

    internal fun order(nest: String, seq: List<String>) = action("order") {
        put("nest", nest); put("seq", buildJsonArray { seq.forEach { add(JsonPrimitive(it)) } })
    }

    /** A daily sweep; a bare @dr starts it now. Sent again on a change, the old timer's
     *  fire comes before the job's new `next` and channels-server drops it. */
    internal fun cron(id: String, nest: String, seconds: Long) = action("cron") {
        put("id", id); put("origin", nest); put("schedule", "~d1"); put("config", delayConfig(seconds))
    }

    internal fun rest(id: String, nest: String) = action("rest") { put("id", id); put("origin", nest) }

    private fun delayConfig(seconds: Long?) = buildJsonObject { seconds?.let { put("delay", delayNoun(it)) } }

    private fun action(tag: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put(tag, buildJsonObject(body)) }

    /**
     * The pokes that put [nests] at [seconds], or off for null, given
     * what the ship holds now ([hooks] with our hook's id). On, config
     * goes first so a post arriving between pokes finds its delay. Off,
     * order goes first so no new post sets a timer, then the config is
     * emptied, which leaves any timer still out with nothing to do.
     */
    internal fun pokes(hooks: Hooks, nests: List<String>, seconds: Long?): List<JsonObject> {
        val id = hooks.id ?: return emptyList()
        return nests.flatMap { nest ->
            val seq = hooks.order[nest].orEmpty()
            if (seconds != null) listOf(
                config(id, nest, seconds),
                order(nest, seq.filter { it != id } + id),
                cron(id, nest, seconds),
            ) else listOfNotNull(
                order(nest, seq.filter { it != id }).takeIf { id in seq },
                config(id, nest, null).takeIf { nest in hooks.delays },
                // ho-rest crashes on a cron that isn't there.
                rest(id, nest).takeIf { nest in hooks.crons },
            )
        }
    }

    /** The host's Groups app has no channel hooks: the scry is not there. */
    class NoHooks : Exception("your ship's Groups app is too old for disappearing messages. Update it and try again.")

    /** The ship took the hook but couldn't build it. */
    class NotBuilt : Exception("your ship's Groups app couldn't build the disappearing-messages hook. This Talon may be older than it.")

    /** Read what the host holds. Throws [NoHooks] where channels-server has none. */
    internal suspend fun read(ch: UrbitChannel): Hooks {
        val json = runCatching { ch.scry("channels-server", "/v0/hooks") }.getOrElse { e ->
            // ponytail: the status read off our own scry error, as fetchChannelWriters does.
            if ("HTTP 404" in e.message.orEmpty()) throw NoHooks() else throw e
        }
        return Hooks.parse(json)
    }

    /** Put [nests] at [seconds], or off for null, on our own ship. Returns what it holds after. */
    internal suspend fun apply(ch: UrbitChannel, nests: List<String>, seconds: Long?): Hooks {
        var hooks = read(ch)
        if (seconds != null) {
            val src = source()
            when {
                hooks.id == null -> ch.poke("channels-server", "hook-action-0", add(src), confirm = true)
                hooks.src != src || !hooks.compiled -> ch.poke("channels-server", "hook-action-0", edit(hooks.id!!, src), confirm = true)
            }
            hooks = read(ch)
            if (hooks.id == null || !hooks.compiled) throw NotBuilt()
        }
        for (p in pokes(hooks, nests, seconds)) ch.poke("channels-server", "hook-action-0", p, confirm = true)
        return read(ch)
    }
}
