package io.nisfeb.talon.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.Log
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Fetches latest.json from a fixed HTTPS URL. Caller injects clock
 * + persistence hooks so tests don't need a real Context.
 *
 * Rate-limited: skip the network if we checked less than
 * [minIntervalMs] ago. The cold-start caller invokes this once per
 * launch; this guard keeps us honest if the app gets cold-started
 * many times in a short window.
 *
 * Uses a 15s per-request timeout — the shared app client leaves the
 * request timeout uncapped for long-lived SSE, which would let a hung
 * manifest fetch hold a connection indefinitely.
 */
class HttpUpdateChecker(
    private val http: HttpClient,
    private val url: String,
    private val now: () -> Long,
    private val lastCheckedAtMs: () -> Long,
    private val recordCheckedAt: (Long) -> Unit,
    private val minIntervalMs: Long,
) : UpdateChecker {

    override suspend fun check(): UpdateManifest? = withContext(ioDispatcher) {
        val nowMs = now()
        val last = lastCheckedAtMs()
        if (nowMs - last < minIntervalMs) return@withContext null
        runCatching {
            val resp = http.get(url) {
                header("User-Agent", "Talon-UpdateChecker")
                timeout { requestTimeoutMillis = 15_000 }
            }
            if (!resp.status.isSuccess()) return@runCatching null
            val body = resp.bodyAsText()
            val m = UpdateManifest.parse(body) ?: return@runCatching null
            recordCheckedAt(nowMs)
            m
        }.onFailure {
            // Don't swallow cancellation — let the parent scope's
            // termination propagate so coroutine teardown is clean.
            if (it is CancellationException) throw it
            Log.w(TAG, "check failed: ${it.message}")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "HttpUpdateChecker"
    }
}
