package io.nisfeb.talon.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Read-only URL fetcher backing the assistant's `fetch_url` tool. Fetches a
 * single http(s) URL and returns its content as model-readable text (HTML is
 * reduced to text). Gated on the same web-access opt-in as web_search, and
 * reads the live config each call so the toggle takes effect immediately.
 *
 * SSRF hardening — this tool fetches whatever URL the model is told to, and
 * the model can be steered by a chat message or a fetched page. So:
 *  - only http/https (toHttpUrlOrNull rejects file://, ftp://, etc.);
 *  - a custom [BlockingDns] refuses any host — on every redirect hop, before
 *    the socket connects — that resolves to a loopback / private / link-local
 *    / any-local address, so the agent can't reach the ship's localhost
 *    endpoints or a cloud metadata service (169.254.169.254);
 *  - response size and output length are capped.
 * Parsing/classification are pure top-level fns, unit-tested offline.
 */
class UrlFetcher(private val settings: () -> AiSettings.Config) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(BlockingDns)
        .build()

    suspend fun fetch(url: String): String {
        if (!settings().webSearchEnabled) return "Web access is turned off in Settings."
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return "Error: not a valid http(s) URL: ${url.take(120)}"
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(parsed)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/json,text/plain,*/*")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val finalUrl = resp.request.url
                    val ctype = resp.header("Content-Type").orEmpty()
                    val body = resp.peekBody(MAX_BYTES).string()
                    if (!resp.isSuccessful) {
                        "Fetch error ${resp.code} for $finalUrl: ${body.take(200)}"
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

    companion object {
        private const val MAX_BYTES = 400_000L
        private const val MAX_OUTPUT_CHARS = 8_000
        private const val USER_AGENT = "TalonAssistant/1.0 (+https://github.com/nisfeb/talon)"
    }
}

/** OkHttp [Dns] that resolves normally but refuses internal targets — runs
 *  per hop (so redirects are covered) and before connect (so the socket to
 *  an internal host is never opened). */
internal object BlockingDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (isBlockedName(hostname)) {
            throw UnknownHostException("refusing to fetch internal host: $hostname")
        }
        val addrs = Dns.SYSTEM.lookup(hostname)
        if (addrs.any(::isInternalAddress)) {
            throw UnknownHostException("refusing to fetch internal host: $hostname")
        }
        return addrs
    }
}

/** Hostname literals that never warrant a DNS round-trip — refuse outright. */
internal fun isBlockedName(host: String): Boolean {
    val h = host.lowercase().trim().removeSurrounding("[", "]")
    if (h.isBlank()) return true
    if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
    // IPv6 unique-local (fc00::/7) — InetAddress.isSiteLocalAddress misses it.
    return h.startsWith("fc") || h.startsWith("fd")
}

/** True for loopback / any-local / link-local (incl. cloud metadata) /
 *  site-local (RFC1918) / multicast addresses — everything an outbound
 *  fetch tool has no business reaching. Also covers IPv6 unique-local
 *  (fc00::/7), which [InetAddress.isSiteLocalAddress] misses (it only
 *  recognises the deprecated fec0::/10), so a domain resolving to a ULA
 *  address can't slip past. */
internal fun isInternalAddress(addr: InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
        addr.isSiteLocalAddress || addr.isMulticastAddress
    ) {
        return true
    }
    val bytes = addr.address
    return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
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
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
    s = s.replace(Regex("[ \\t]+"), " ")
    s = s.replace(Regex("\\n{3,}"), "\n\n")
    return s.trim()
}
