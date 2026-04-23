package io.nisfeb.talon.urbit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal link-preview fetcher. For each requested URL we do one
 * GET, scan the first chunk of HTML for OpenGraph / Twitter card tags,
 * and stash the result in-memory. No disk cache for v1.
 */
object LinkPreviewCache {

    data class Preview(
        val url: String,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
    ) {
        val domain: String get() = runCatching {
            java.net.URI(url).host?.removePrefix("www.")
        }.getOrNull() ?: url
    }

    // ConcurrentHashMap rejects null values, so we wrap the result to
    // distinguish "fetched, no preview" from "not fetched yet".
    private sealed interface Entry {
        object None : Entry
        data class Some(val preview: Preview) : Entry
    }

    private val results = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun record(url: String, preview: Preview?) {
        results[url] = if (preview != null) Entry.Some(preview) else Entry.None
    }

    private fun cached(url: String): Preview? =
        (results[url] as? Entry.Some)?.preview

    private fun hasAttempted(url: String): Boolean = results.containsKey(url)

    /**
     * Returns a cached preview if we have one, kicks off a fetch if not.
     * UI should poll this via a Compose-friendly mechanism (e.g. LaunchedEffect).
     * Returns null both for "still loading" and "no preview available".
     */
    fun get(http: OkHttpClient, url: String): Preview? {
        if (hasAttempted(url)) return cached(url)
        if (inFlight[url]?.isActive == true) return null
        inFlight[url] = scope.launch {
            val fetched = runCatching { fetch(http, url) }.getOrNull()
            record(url, fetched)
            inFlight.remove(url)
        }
        return null
    }

    /** Poll-friendly suspend variant: waits for an in-flight fetch. */
    suspend fun await(http: OkHttpClient, url: String): Preview? {
        if (hasAttempted(url)) return cached(url)
        val job = inFlight[url]
        if (job != null) {
            job.join()
            return cached(url)
        }
        return withContext(Dispatchers.IO) {
            val fetched = runCatching { fetch(http, url) }.getOrNull()
            record(url, fetched)
            fetched
        }
    }

    private fun fetch(http: OkHttpClient, url: String): Preview? {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Android; Talon) AppleWebKit/537.36",
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val type = resp.header("Content-Type").orEmpty()
            if (!type.contains("html")) return null
            // Only read the first ~64KB — OG tags are always in <head>.
            val reader = resp.body?.byteStream()?.bufferedReader() ?: return null
            val buf = CharArray(CHUNK_SIZE)
            val sb = StringBuilder()
            var total = 0
            while (total < CHUNK_SIZE * 8) {
                val n = reader.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
                total += n
                if (sb.contains("</head>", ignoreCase = true)) break
            }
            val head = sb.toString()
            val title = META_OG_TITLE.find(head)?.groupValues?.get(1)
                ?: META_TW_TITLE.find(head)?.groupValues?.get(1)
                ?: TITLE_TAG.find(head)?.groupValues?.get(1)
            val description = META_OG_DESC.find(head)?.groupValues?.get(1)
                ?: META_TW_DESC.find(head)?.groupValues?.get(1)
                ?: META_NAME_DESC.find(head)?.groupValues?.get(1)
            val image = META_OG_IMAGE.find(head)?.groupValues?.get(1)
                ?: META_TW_IMAGE.find(head)?.groupValues?.get(1)
            val normalizedImage = image?.let { resolveUrl(url, it) }
            // Skip previews that have nothing useful to show.
            if (title.isNullOrBlank() && description.isNullOrBlank() && normalizedImage.isNullOrBlank()) {
                return null
            }
            return Preview(
                url = url,
                title = title?.let(::decodeHtmlEntities),
                description = description?.let(::decodeHtmlEntities),
                imageUrl = normalizedImage,
            )
        }
    }

    private fun resolveUrl(base: String, ref: String): String = runCatching {
        java.net.URI(base).resolve(ref).toString()
    }.getOrElse { ref }

    /**
     * Decode the handful of HTML entities OG text uses heavily. Not a
     * full HTML entity table — just what shows up in real-world titles.
     */
    private fun decodeHtmlEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private const val CHUNK_SIZE = 8192

    // Capture the content= attribute without being pedantic about quote style
    // or attribute order. Real OG tags are well-formed in practice.
    private val META_OG_TITLE = Regex(
        """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_OG_DESC = Regex(
        """<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_OG_IMAGE = Regex(
        """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_TW_TITLE = Regex(
        """<meta[^>]+name=["']twitter:title["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_TW_DESC = Regex(
        """<meta[^>]+name=["']twitter:description["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_TW_IMAGE = Regex(
        """<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val META_NAME_DESC = Regex(
        """<meta[^>]+name=["']description["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_TAG = Regex(
        """<title>([^<]+)</title>""",
        RegexOption.IGNORE_CASE,
    )
}
