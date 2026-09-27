package io.nisfeb.talon.urbit

import io.nisfeb.talon.util.decodeHtmlEntities
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Title + snippet for a urb:// address, for the inline preview card.
 *
 * Fetches the lattice `/fetch` endpoint on the viewer's own ship,
 * which returns the referent's body as gemtext JSON ({body, mark}).
 * The app's HTTP client already carries the ship session cookie for
 * that domain, so no manual auth is needed. Results (including "no
 * preview") are cached in memory, keyed by the urb:// address, which
 * is referentially transparent.
 */
object UrbUnfurlCache {

    data class Unfurl(val urbUrl: String, val title: String?, val snippet: String?)

    private sealed interface Entry {
        data object None : Entry
        data class Some(val unfurl: Unfurl) : Entry
    }

    private val lock = Mutex()
    private val results = HashMap<String, Entry>()
    private val inFlight = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val json = Json { ignoreUnknownKeys = true }

    /** How long a stored card stands before it is read again. */
    const val KEEP_MS = 24 * 60 * 60 * 1000L

    /**
     * The card for [urbUrl]: the one kept from last time, at once, then
     * the page's own where the kept one is a day old or there is none.
     * A remote page took forty seconds and more over Ames, and was read
     * again at every start before a card showed; a day keeps that off
     * the ship most of the time. A page read to have no card is kept as
     * such, so it is not asked for at every start either.
     */
    fun cards(
        http: HttpClient,
        shipUrl: String,
        cookie: String,
        urbUrl: String,
        kept: io.nisfeb.talon.data.UrbUnfurlDao?,
        now: () -> Long = { io.nisfeb.talon.util.nowMs() },
    ): kotlinx.coroutines.flow.Flow<Unfurl> = kotlinx.coroutines.flow.flow {
        val row = kept?.let { k -> io.nisfeb.talon.util.runSuspendCatching { k.get(urbUrl) }.getOrNull() }
        val old = row?.let { Unfurl(urbUrl, it.title, it.snippet) }
        if (old != null && (old.title != null || old.snippet != null)) emit(old)
        if (row != null && now() - row.fetchedAtMs < KEEP_MS) return@flow
        val fresh = await(http, shipUrl, cookie, urbUrl)
        // Only an answer is kept: a fetch that failed is not "no card".
        val answered = fresh != null || lock.withLock { results[urbUrl] == Entry.None }
        if (answered) {
            kept?.let { k -> io.nisfeb.talon.util.runSuspendCatching { k.put(io.nisfeb.talon.data.UrbUnfurlEntity(urbUrl, fresh?.title, fresh?.snippet, now())) } }
        }
        if (fresh != null && fresh != old) emit(fresh)
    }

    suspend fun await(
        http: HttpClient,
        shipUrl: String,
        cookie: String,
        urbUrl: String,
    ): Unfurl? {
        lock.withLock { (results[urbUrl] as? Entry.Some)?.let { return it.unfurl } }
        lock.withLock { if (results.containsKey(urbUrl)) return null } // cached "None"
        return withContext(ioDispatcher) {
            // Only an answer is remembered: a failed or cancelled fetch
            // (its card scrolled away) is not "nothing to show" for the run.
            val fetched = io.nisfeb.talon.util.runSuspendCatching { fetch(http, shipUrl, cookie, urbUrl) }
                .getOrElse { return@withContext null }
            lock.withLock {
                results[urbUrl] = if (fetched != null) Entry.Some(fetched) else Entry.None
            }
            fetched
        }
    }

    private suspend fun fetch(
        http: HttpClient,
        shipUrl: String,
        cookie: String,
        urbUrl: String,
    ): Unfurl? {
        val reader = UrbHttp.fetchUrl(shipUrl, urbUrl)
        // The shared http client has no cookie store (that lives in
        // UrbitSession), so authenticate this request explicitly.
        val text = http.get(reader) { header("Cookie", cookie) }.bodyAsText()
        val body = json.parseToJsonElement(text).jsonObject["body"]
            ?.jsonPrimitive?.content ?: return null
        return unfurlOf(urbUrl, body)
    }

    /** Title (first heading) + snippet (first prose line) from a
     *  gemtext body, or a web page's own from an HTML one. Internal so
     *  the parsing is unit-tested. */
    internal fun unfurlOf(urbUrl: String, body: String): Unfurl =
        if (looksLikeHtml(body)) htmlUnfurl(urbUrl, body) else Unfurl(urbUrl, titleOf(body), snippetOf(body))

    /**
     * An HTML page, whatever the mark says: lattice's fetch sends one as it
     * is and labels it gmi. Read as gemtext, its first prose line was its
     * style sheet, and the card read "head {display:flex". Gemtext does not
     * open with a tag.
     */
    private fun looksLikeHtml(body: String): Boolean {
        val t = body.trimStart()
        return t.startsWith("<") && HTML_TAG.containsMatchIn(t.take(4000))
    }

    /**
     * A web page's card: its own title and description, as a link to one
     * on the web is read ([LinkPreviewCache.titleAndDescription]); then the
     * title lattice's /c/ shell sets from a script, the first h1, and the
     * first paragraph. Never a style sheet, a script or a comment.
     */
    private fun htmlUnfurl(urbUrl: String, html: String): Unfurl {
        val (title, description) = LinkPreviewCache.titleAndDescription(html)
        val visible = html.replace(COMMENT, " ").replace(STYLE_OR_SCRIPT, " ")
        fun textOf(tag: String): String? =
            Regex("<$tag\\b[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(visible)?.groupValues?.get(1)
                ?.let { decodeHtmlEntities(it.replace(ANY_TAG, " ")).replace(SPACES, " ").replace(SPACE_BEFORE_MARK, "$1").trim() }
                ?.takeIf { it.isNotEmpty() }
        return Unfurl(
            urbUrl,
            title = title ?: SCRIPT_TITLE.find(html)?.groupValues?.get(1)?.let(::decodeHtmlEntities) ?: textOf("h1"),
            snippet = (description ?: textOf("p"))?.take(200),
        )
    }

    private val HTML_TAG = Regex(
        "<(?:!--|(?:!doctype|html|head|body|meta|style|script|link|title|div|p|h[1-6]|main|section|article|header|nav)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val STYLE_OR_SCRIPT = Regex("<(style|script)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SCRIPT_TITLE = Regex("""document\.title\s*=\s*['"]([^'"]+)['"]""")
    private val ANY_TAG = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    /** "a <b>bold</b>." stripped of its tags leaves "a bold ."; the stop goes back. */
    private val SPACE_BEFORE_MARK = Regex(" ([.,;:!?])")

    /** First gemtext heading ("# …"), or null. */
    private fun titleOf(gmi: String): String? =
        gmi.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("#") }
            ?.trimStart('#')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** First plain prose line — not a heading, link line, empty, or
     *  inside a ``` code fence. */
    private fun snippetOf(gmi: String): String? {
        var inFence = false
        for (raw in gmi.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence || line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("=>")) continue
            return line.take(200)
        }
        return null
    }
}
