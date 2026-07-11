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
