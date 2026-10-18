package io.nisfeb.talon.util

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException

/**
 * Is [t] (or a shallow cause chain) a transient network hiccup worth
 * retrying / giving up on quietly? Multiplatform replacement for the
 * `java.io.InterruptedIOException` / `java.net.SocketException` checks
 * commonMain used — Ktor's engines throw its own socket/timeout types
 * on native, not the JVM ones.
 *
 * ponytail: treats any kotlinx-io IOException as transient, which is a
 * touch broader than the old timeout+reset-only check. For retry/backoff
 * logic that errs harmlessly toward retrying; tighten if a specific
 * non-transient IOException starts getting retried in vain.
 */
fun isTransientNetworkError(t: Throwable?): Boolean {
    var e = t
    var depth = 0
    while (e != null && depth < 5) {
        when (e) {
            is SocketTimeoutException,
            is ConnectTimeoutException,
            is HttpRequestTimeoutException,
            is IOException -> return true
        }
        e = e.cause
        depth++
    }
    return false
}

/**
 * Whether [t] says the ship was slow or out of reach, rather than that it
 * refused: timed out, lost the connection, answered a channel PUT with a
 * 5xx or anything with a proxy's 502 to 504 ([SHIP_DOWN]), or was not
 * connected at all. A write that failed so is not the ship saying no; it
 * can go again when the ship is back, and is said calmly.
 */
fun isShipSlow(t: Throwable): Boolean =
    t !is io.nisfeb.talon.urbit.PokeNacked && (
        isTransientNetworkError(t) ||
            t is io.nisfeb.talon.urbit.PokeUnacked ||
            t.message.orEmpty().let { it.startsWith("channel PUT: HTTP 5") || it.startsWith("not connected") || SHIP_DOWN.matches(it) }
        )

/**
 * A 502, 503 or 504 in the words UrbitChannel and UrbitSession give it:
 * "scry <app><path>: HTTP 502", "PUT <path>: HTTP 502", "thread <name>:
 * HTTP 504", "channel SSE: HTTP 503", "login HTTP 502". The web server in
 * front of the ship answering for a ship that is down, or too slow. Not
 * every 5xx: a scry's 500 is a path its agent does not serve, which a read
 * falls back from ([io.nisfeb.talon.urbit.notServed]). Nor another
 * service's 502 (S3, memex, a model), which says nothing of the ship.
 */
private val SHIP_DOWN = Regex("""^(?:(?:channel (?:PUT|SSE)|scry \S+|thread \S+|[A-Z]+ /\S*): |login )HTTP 50[234]$""")

/**
 * A failure as the person is told it: [line] in words, the error whole in
 * [details] behind "Copy error details", and [calm] where the ship was
 * only slow, drawn quietly rather than in the error colour.
 */
data class Problem(val line: String, val details: String? = null, val calm: Boolean = false)

/**
 * [what] ("Couldn't save the channel") did not go through, said in words:
 * a slow or unreachable ship calmly, as something to try again; a refusal
 * as one, with the ship's reason; anything else by what it said, if that
 * reads as a sentence. A
 * URL, an exception's name, a raw reply or a dump never reaches the line;
 * it is in the details.
 */
fun problemOf(what: String, err: Throwable): Problem {
    val slow = isShipSlow(err)
    val why = when {
        slow -> "your ship is slow or out of reach. Try again when it's back."
        // Its reason where that reads as words ("banned", "not an admin"),
        // which is why it refused; a trace stays in the details.
        err is io.nisfeb.talon.urbit.PokeNacked ->
            readableReason(err.reason)?.let { "the ship refused it ($it)." } ?: "the ship refused it."
        else -> readableReason(err.message) ?: "something went wrong."
    }
    return Problem("$what: $why", errorDetailsOf(err), calm = slow)
}

/**
 * The calm line over a chat while [queued] writes wait for the ship, in
 * the words vere's healthz gives: busy, down, or out of reach; slow when
 * it has not said, or says it is idle.
 */
fun shipSlowLine(queued: Int, health: io.nisfeb.talon.urbit.ShipHealth?): String = when (health) {
    io.nisfeb.talon.urbit.ShipHealth.BUSY -> "Your ship is busy. $queued queued for when it catches up."
    io.nisfeb.talon.urbit.ShipHealth.DOWN -> "Your ship is down. $queued queued for when it's back."
    io.nisfeb.talon.urbit.ShipHealth.UNREACHABLE -> "Can't reach your ship. $queued queued for when it's back."
    else -> "Your ship is slow. $queued queued for when it's back."
}

/**
 * One line for a failure whose own message is already a sentence for the
 * person ("Install is taking a while"): that sentence, a slow or
 * unreachable ship said calmly, and [what] where the message is a dump.
 * [problemOf] would put [what] in front of a sentence that contradicts it.
 */
fun failureLine(what: String, err: Throwable): String =
    if (isShipSlow(err)) problemOf(what, err).line else readableReason(err.message) ?: "$what."

/** [message]'s first line when it reads as words, else null. */
internal fun readableReason(message: String?): String? {
    val first = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    // The internal path never reaches the line: said as what it means.
    if (SHIP_DOWN.matches(first)) return "your ship is down or out of reach"
    val technical = first.isEmpty() || first.length > 160 ||
        listOf("://", "Exception", "{", "<", "[url=").any { it in first }
    return first.takeUnless { technical }
}

/** A failure whole, causes and all, for "Copy error details". */
fun errorDetailsOf(t: Throwable): String =
    generateSequence(t) { it.cause }.take(4).joinToString("\ncaused by: ") { "${it::class.simpleName}: ${it.message}" }
