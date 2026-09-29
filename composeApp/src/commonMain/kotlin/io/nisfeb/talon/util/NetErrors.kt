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
 * refused: timed out, lost the connection, answered with a 5xx, or was not
 * connected at all. A write that failed so is not the ship saying no; it
 * can go again when the ship is back, and is said calmly.
 */
fun isShipSlow(t: Throwable): Boolean =
    t !is io.nisfeb.talon.urbit.PokeNacked && (
        isTransientNetworkError(t) ||
            t is io.nisfeb.talon.urbit.PokeUnacked ||
            t.message.orEmpty().let { it.startsWith("channel PUT: HTTP 5") || it.startsWith("not connected") }
        )

/** A failure whole, causes and all, for "Copy error details". */
fun errorDetailsOf(t: Throwable): String =
    generateSequence(t) { it.cause }.take(4).joinToString("\ncaused by: ") { "${it::class.simpleName}: ${it.message}" }
