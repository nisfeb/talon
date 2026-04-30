package io.nisfeb.talon.update

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches latest.json from a fixed HTTPS URL. Caller injects clock
 * + persistence hooks so tests don't need a real Context.
 *
 * Rate-limited: skip the network if we checked less than
 * [minIntervalMs] ago. The cold-start caller invokes this once per
 * launch; this guard keeps us honest if the app gets cold-started
 * many times in a short window.
 *
 * Owns a derived OkHttpClient with a 15s callTimeout — the shared
 * app client uses readTimeout(0) for long-lived SSE, which would
 * let a hung manifest fetch hold an IO thread indefinitely.
 */
class HttpUpdateChecker(
    http: OkHttpClient,
    private val url: String,
    private val now: () -> Long,
    private val lastCheckedAtMs: () -> Long,
    private val recordCheckedAt: (Long) -> Unit,
    private val minIntervalMs: Long,
) : UpdateChecker {

    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        val nowMs = now()
        val last = lastCheckedAtMs()
        if (nowMs - last < minIntervalMs) return@withContext null
        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Talon-UpdateChecker")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val m = UpdateManifest.parse(body) ?: return@runCatching null
                recordCheckedAt(nowMs)
                m
            }
        }.onFailure {
            // Don't swallow cancellation — let the parent scope's
            // termination propagate so coroutine teardown is clean.
            // See DailyDigest.kt for the same pattern.
            if (it is CancellationException) throw it
            Log.w("HttpUpdateChecker", "check failed", it)
        }.getOrNull()
    }
}
