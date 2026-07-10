package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import io.nisfeb.talon.util.decodeHtmlEntities
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            Url(url).host.removePrefix("www.")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    private sealed interface Entry {
        object None : Entry
        data class Some(val preview: Preview) : Entry
    }

    private val lock = SynchronizedObject()
    private val results = HashMap<String, Entry>()
    private val inFlight = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private fun record(url: String, preview: Preview?) = synchronized(lock) {
        results[url] = if (preview != null) Entry.Some(preview) else Entry.None
    }

    private fun cached(url: String): Preview? =
        synchronized(lock) { (results[url] as? Entry.Some)?.preview }

    private fun hasAttempted(url: String): Boolean =
        synchronized(lock) { results.containsKey(url) }

    /**
     * Returns a cached preview if we have one, kicks off a fetch if not.
     * UI should poll this via a Compose-friendly mechanism (e.g. LaunchedEffect).
     * Returns null both for "still loading" and "no preview available".
     */
    fun get(http: HttpClient, url: String): Preview? {
        if (hasAttempted(url)) return cached(url)
        synchronized(lock) {
            if (inFlight[url]?.isActive == true) return null
            inFlight[url] = scope.launch {
                val fetched = runCatching { fetch(http, url) }.getOrNull()
                record(url, fetched)
                synchronized(lock) { inFlight.remove(url) }
            }
        }
        return null
    }

    /** Poll-friendly suspend variant: waits for an in-flight fetch. */
    suspend fun await(http: HttpClient, url: String): Preview? {
        if (hasAttempted(url)) return cached(url)
        val job = synchronized(lock) { inFlight[url] }
        if (job != null) {
            job.join()
            return cached(url)
        }
        return withContext(ioDispatcher) {
            val fetched = runCatching { fetch(http, url) }.getOrNull()
            record(url, fetched)
            fetched
        }
    }

    private suspend fun fetch(http: HttpClient, url: String): Preview? {
        return http.prepareGet(url) {
            header(
                HttpHeaders.UserAgent,
                "Mozilla/5.0 (Android; Talon) AppleWebKit/537.36",
            )
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }.execute { resp ->
            if (!resp.status.isSuccess()) return@execute null
            val type = resp.headers[HttpHeaders.ContentType].orEmpty()
            if (!type.contains("html")) return@execute null
            // Only read the first ~64KB — OG tags are always in <head>.
            val limit = CHUNK_SIZE * 8
            val channel = resp.bodyAsChannel()
            val buf = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val n = channel.readAvailable(buf, total, limit - total)
                if (n <= 0) break
                total += n
                if (buf.decodeToString(0, total).contains("</head>", ignoreCase = true)) break
            }
            val head = buf.decodeToString(0, total)
            val title = META_OG_TITLE.find(head)?.groupValues?.get(1)
                ?: META_TW_TITLE.find(head)?.groupValues?.get(1)
                ?: TITLE_TAG.find(head)?.groupValues?.get(1)
            val description = META_OG_DESC.find(head)?.groupValues?.get(1)
                ?: META_TW_DESC.find(head)?.groupValues?.get(1)
                ?: META_NAME_DESC.find(head)?.groupValues?.get(1)
            val image = META_OG_IMAGE.find(head)?.groupValues?.get(1)
                ?: META_TW_IMAGE.find(head)?.groupValues?.get(1)
            val normalizedImage = image?.let { resolveUrl(url, it) }
            if (title.isNullOrBlank() && description.isNullOrBlank() && normalizedImage.isNullOrBlank()) {
                return@execute null
            }
            Preview(
                url = url,
                title = title?.let(::decodeHtmlEntities),
                description = description?.let(::decodeHtmlEntities),
                imageUrl = normalizedImage,
            )
        }
    }

    private fun resolveUrl(base: String, ref: String): String = runCatching {
        URLBuilder().takeFrom(base).takeFrom(ref).buildString()
    }.getOrElse { ref }

    private const val CHUNK_SIZE = 8192

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
