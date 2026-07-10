package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.nisfeb.talon.util.decodeHtmlEntities
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * Platform HTTP client for [UrlFetcher] with SSRF hardening. The
 * post-DNS internal-address guard needs the engine's DNS hook, which is
 * JVM-only (OkHttp `Dns`), so it lives in the leaf actuals. iOS gets the
 * literal-hostname guard ([isBlockedName]) that runs in common before
 * the request; a full per-hop IP guard there awaits an NSURLSession hook.
 */
expect fun createUrlFetcherClient(): HttpClient

/**
 * Read-only URL fetcher backing the assistant's `fetch_url` tool. Fetches a
 * single http(s) URL and returns its content as model-readable text (HTML is
 * reduced to text). Gated on the same web-access opt-in as web_search, and
 * reads the live config each call so the toggle takes effect immediately.
 *
 * SSRF hardening — this tool fetches whatever URL the model is told to, so:
 *  - only http/https;
 *  - [isBlockedName] refuses localhost / *.local / ULA-hint hostnames in
 *    common, and the JVM engine's DNS guard refuses any host resolving to a
 *    loopback / private / link-local / any-local address (incl. cloud
 *    metadata 169.254.169.254), on every redirect hop before connect;
 *  - response size and output length are capped.
 * Parsing/classification are pure top-level fns, unit-tested offline.
 */
class UrlFetcher(private val settings: () -> AiSettings.Config) {

    private val http: HttpClient = createUrlFetcherClient()

    suspend fun fetch(url: String): String {
        if (!settings().assistantOn()) return "Web access is part of the assistant, which is off in Settings."
        val trimmed = url.trim()
        val parsed = runCatching { Url(trimmed) }.getOrNull()
        if (parsed == null || parsed.protocol.name !in HTTP_SCHEMES) {
            return "Error: not a valid http(s) URL: ${url.take(120)}"
        }
        if (isBlockedName(parsed.host)) {
            return "Fetch failed: refusing to fetch internal host: ${parsed.host}"
        }
        return withContext(ioDispatcher) {
            runCatching {
                http.prepareGet(trimmed) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "text/html,application/json,text/plain,*/*")
                    timeout { requestTimeoutMillis = 30_000 }
                }.execute { resp ->
                    val finalUrl = resp.call.request.url.toString()
                    val ctype = resp.headers[HttpHeaders.ContentType].orEmpty()
                    val body = readCapped(resp.bodyAsChannel(), MAX_BYTES)
                    if (!resp.status.isSuccess()) {
                        "Fetch error ${resp.status.value} for $finalUrl: ${body.take(200)}"
                    } else {
                        val text = if (ctype.contains("html", ignoreCase = true)) {
                            htmlToText(body)
                        } else {
                            body.trim()
                        }
                        val clipped = text.take(MAX_OUTPUT_CHARS)
                        val note = if (text.length > MAX_OUTPUT_CHARS) "\n…[truncated]" else ""
                        "URL: $finalUrl\n\n$clipped$note".ifBlank { "(empty response)" }
                    }
                }
            }.getOrElse { "Fetch failed: ${it.message ?: it::class.simpleName}" }
        }
    }

    private suspend fun readCapped(
        channel: io.ktor.utils.io.ByteReadChannel,
        max: Int,
    ): String {
        val buf = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = channel.readAvailable(buf, total, max - total)
            if (n <= 0) break
            total += n
        }
        return buf.decodeToString(0, total)
    }

    companion object {
        private const val MAX_BYTES = 400_000
        private const val MAX_OUTPUT_CHARS = 8_000
        private const val USER_AGENT = "TalonAssistant/1.0 (+https://github.com/nisfeb/talon)"
        private val HTTP_SCHEMES = setOf("http", "https")
    }
}

/** Hostname literals that never warrant a DNS round-trip — refuse outright. */
internal fun isBlockedName(host: String): Boolean {
    val h = host.lowercase().trim().removeSurrounding("[", "]")
    if (h.isBlank()) return true
    if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
    // IPv6 unique-local (fc00::/7).
    return h.startsWith("fc") || h.startsWith("fd")
}

/** Reduce an HTML document to readable plain text: drop script/style/
 *  comments, turn block-closing tags into newlines, strip the rest, and
 *  decode the handful of entities that matter. Pure + unit-tested. */
internal fun htmlToText(html: String): String {
    var s = html
    s = s.replace(Regex("(?is)<script.*?</script>"), " ")
    s = s.replace(Regex("(?is)<style.*?</style>"), " ")
    s = s.replace(Regex("(?s)<!--.*?-->"), " ")
    s = s.replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)\\s*/?>"), "\n")
    s = s.replace(Regex("<[^>]+>"), " ")
    s = decodeHtmlEntities(s)
    s = s.replace(Regex("[ \\t]+"), " ")
    s = s.replace(Regex("\\n{3,}"), "\n\n")
    return s.trim()
}
