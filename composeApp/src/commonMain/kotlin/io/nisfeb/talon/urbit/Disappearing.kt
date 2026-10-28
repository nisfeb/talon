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
     * [seconds] over 64 zero bits.
     */
    internal fun delayNoun(seconds: Long): String {
        require(seconds in 1..MAX_SECONDS) { "delay out of range: $seconds" }
        return jamAtom(List(64) { false } + List(64 - seconds.countLeadingZeroBits()) { i -> (seconds shr i) and 1L == 1L })
    }

    /** `(scot %uw (jam '~host/name'))`: the group a channel's config is for, as a cord. */
    internal fun groupNoun(flag: String): String =
        jamAtom(flag.encodeToByteArray().flatMap { byte -> List(8) { i -> (byte.toInt() shr i) and 1 == 1 } }.dropLastWhile { !it })

    /** [delayNoun] read back: whole seconds, or null for anything else. */
    internal fun delaySeconds(noun: String): Long? {
        val bits = cueAtom(noun) ?: return null
        if (bits.size <= 64 || bits.size > 64 + 40 || bits.take(64).any { it }) return null
        return bits.drop(64).foldIndexed(0L) { i, s, bit -> if (bit) s or (1L shl i) else s }
    }

    /** [groupNoun] read back, or null for anything else. */
    internal fun groupOf(noun: String): String? {
        val bits = cueAtom(noun)?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = bits.chunked(8) { eight -> eight.foldIndexed(0) { i, b, bit -> if (bit) b or (1 shl i) else b }.toByte() }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * `(scot %uw (jam a))` for an atom given as its bits, low first,
     * with no high zeros. Bit by bit because common Kotlin has no big
     * integers.
     */
    private fun jamAtom(atom: List<Boolean>): String {
        val bits = ArrayList<Boolean>()
        bits += false // jam's tag: an atom
        if (atom.isEmpty()) bits += true // +mat of 0
        else {
            val b = atom.size
            val c = 32 - b.countLeadingZeroBits()
            repeat(c) { bits += false } // +mat: c zeros, a one, b's low c-1 bits, the atom
            bits += true
            for (i in 0 until c - 1) bits += (b shr i) and 1 == 1
            bits += atom
        }
        val digits = bits.chunked(6) { six -> six.foldIndexed(0) { i, acc, bit -> if (bit) acc or (1 shl i) else acc } }
            .dropLastWhile { it == 0 }
        val text = digits.asReversed().joinToString("") { UW[it].toString() }
        return "0w" + text.reversed().chunked(5).joinToString(".").reversed()
    }

    /** The atom a [jamAtom] noun holds, as its bits, low first; null for a cell or anything not an @uw. */
    private fun cueAtom(noun: String): List<Boolean>? {
        if (!noun.startsWith("0w")) return null
        val digits = noun.removePrefix("0w").filter { it != '.' }.map { ch -> UW.indexOf(ch).takeIf { it >= 0 } ?: return null }
        val bits = digits.asReversed().flatMap { d -> List(6) { i -> (d shr i) and 1 == 1 } }
        var p = 0
        fun bit() = bits.getOrElse(p++) { false }
        if (bit()) return null // a cell
        var c = 0
        while (!bit()) if (++c > 16 || p > bits.size) return null
        if (c == 0) return emptyList() // the atom 0
        var b = 1 shl (c - 1)
        for (i in 0 until c - 1) if (bit()) b = b or (1 shl i)
        if (p + b > bits.size) return null
        return List(b) { bit() }
    }

    /** One channel's config, or its sweep's own copy: the delay, and the group it is for. */
    data class Config(val delay: Long?, val group: String?)

    /** What the host's channels-server holds of this hook, from its `/v0/hooks` scry. */
    data class Hooks(
        /** Our hook's id, or null where it was never added. */
        val id: String?,
        val src: String?,
        val compiled: Boolean,
        /** Each channel whose config holds a delay, read or not: what an off must empty. */
        val config: Map<String, Config>,
        /** Each channel's hook order: every hook, ours or not. */
        val order: Map<String, List<String>>,
        /** Channels our hook has a daily sweep on, and the sweep's own copy of the config. */
        val crons: Map<String, Config>,
    ) {
        /**
         * The delay fully in force on [nest] for the group [flag]: its
         * config, its order and its sweep, at one delay and for [flag].
         */
        fun delayOn(nest: String, flag: String): Long? {
            val c = config[nest] ?: return null
            val d = c.delay ?: return null
            return d.takeIf { c.group == flag && id != null && id in order[nest].orEmpty() && crons[nest] == c }
        }

        /**
         * Some of it is left on [nest] without being fully on for [flag].
         * A delay in the config still deletes, as timers already set read
         * it when they fire, and so does a sweep, which keeps its own copy.
         * A switch-off the ship half took leaves this, and so can a Tlon
         * channel template, or a Talon from before the group was written
         * in. Never shown as off.
         */
        fun unfinished(nest: String, flag: String): Boolean =
            delayOn(nest, flag) == null && (nest in config || nest in crons || (id != null && id in order[nest].orEmpty()))

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
                fun configOf(cfg: JsonElement?): Config? = (cfg as? JsonObject)?.takeIf { it.containsKey("delay") }?.let {
                    Config(it["delay"].asStr()?.let(::delaySeconds), it["group"].asStr()?.let(::groupOf))
                }
                val config = (hook?.get("config") as? JsonObject).orEmpty().mapNotNull { (nest, cfg) -> configOf(cfg)?.let { nest to it } }.toMap()
                // A sweep is kept whatever its config: an off must rest it.
                val crons = ((root["crons"] as? JsonObject)?.get(id ?: "") as? JsonObject).orEmpty()
                    .mapValues { (_, job) -> configOf((job as? JsonObject)?.get("config")) ?: Config(null, null) }
                return Hooks(
                    id = id,
                    src = hook?.get("src").asStr(),
                    compiled = (hook?.get("compiled") as? JsonPrimitive)?.booleanOrNull == true,
                    config = config,
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

    /** [seconds] for the group [flag], or an empty config for off. */
    internal fun config(id: String, nest: String, seconds: Long?, flag: String?) = action("config") {
        put("id", id); put("nest", nest); put("config", delayConfig(seconds, flag))
    }

    internal fun order(nest: String, seq: List<String>) = action("order") {
        put("nest", nest); put("seq", buildJsonArray { seq.forEach { add(JsonPrimitive(it)) } })
    }

    /** A daily sweep; a bare @dr starts it now. Sent again on a change, the old timer's
     *  fire comes before the job's new `next` and channels-server drops it. */
    internal fun cron(id: String, nest: String, seconds: Long, flag: String) = action("cron") {
        put("id", id); put("origin", nest); put("schedule", "~d1"); put("config", delayConfig(seconds, flag))
    }

    internal fun rest(id: String, nest: String) = action("rest") { put("id", id); put("origin", nest) }

    /** The hook acts only where 'group' is the channel's own group, so a config that strays does nothing. */
    private fun delayConfig(seconds: Long?, flag: String?) = buildJsonObject {
        if (seconds != null && flag != null) { put("delay", delayNoun(seconds)); put("group", groupNoun(flag)) }
    }

    private fun action(tag: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put(tag, buildJsonObject(body)) }

    /**
     * The pokes that put [nests] of the group [flag] at [seconds], or off for null, given
     * what the ship holds now ([hooks] with our hook's id). On, config
     * goes first so a post arriving between pokes finds its delay. Off,
     * what deletes goes first: the config is emptied, which leaves new
     * posts and any timer still out with nothing to do, then the sweep
     * is rested, then it leaves the order. Each is sent only where it is
     * there, so an off the ship half took is finished by another.
     */
    internal fun pokes(hooks: Hooks, flag: String, nests: List<String>, seconds: Long?): List<JsonObject> {
        val id = hooks.id ?: return emptyList()
        return nests.flatMap { nest ->
            val seq = hooks.order[nest].orEmpty()
            if (seconds != null) listOf(
                config(id, nest, seconds, flag),
                order(nest, seq.filter { it != id } + id),
                cron(id, nest, seconds, flag),
            ) else listOfNotNull(
                config(id, nest, null, null).takeIf { nest in hooks.config },
                // ho-rest crashes on a cron that isn't there.
                rest(id, nest).takeIf { nest in hooks.crons },
                order(nest, seq.filter { it != id }).takeIf { id in seq },
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

    /** Put [nests] of the group [flag] at [seconds], or off for null, on our own ship. Returns what it holds after. */
    internal suspend fun apply(ch: UrbitChannel, flag: String, nests: List<String>, seconds: Long?): Hooks {
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
        for (p in pokes(hooks, flag, nests, seconds)) ch.poke("channels-server", "hook-action-0", p, confirm = true)
        return read(ch)
    }
}
