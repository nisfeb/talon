package io.nisfeb.talon.update

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches latest.json from a fixed HTTPS URL. Caller injects clock
 * + persistence hooks so tests don't need a real Context.
 *
 * Rate-limited: skip the network if we checked less than
 * [minIntervalMs] ago. The cold-start caller invokes this once per
 * launch; this guard keeps us honest if the app gets cold-started
 * many times in a short window.
 */
class HttpUpdateChecker(
    private val http: OkHttpClient,
    private val url: String,
    private val now: () -> Long,
    private val lastCheckedAtMs: () -> Long,
    private val recordCheckedAt: (Long) -> Unit,
    private val minIntervalMs: Long,
) : UpdateChecker {

    override suspend fun check(): UpdateManifest? {
        val nowMs = now()
        val last = lastCheckedAtMs()
        if (nowMs - last < minIntervalMs) return null
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Talon-UpdateChecker")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val m = UpdateManifest.parse(body) ?: return@runCatching null
                recordCheckedAt(nowMs)
                m
            }
        }.onFailure {
            Log.w("HttpUpdateChecker", "check failed", it)
        }.getOrNull()
    }
}
