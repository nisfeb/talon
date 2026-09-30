package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.nisfeb.talon.util.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The card under a message that links a furum board or post, from what
 * the reader's own furum knows of it: `GET /apps/furum/preview` on the
 * reader's ship ([FurumRef.previewUrl]).
 *
 * Not the page. Opening a board the ship does not follow yet makes it
 * follow the board, and a card must not subscribe anyone to whatever
 * was linked at them. A furum without the route, or a ship without
 * furum, answers 404, and there is no card.
 */
object FurumPreview {
    /** One line over the title, the title, and a line under it. */
    data class Card(val caption: String, val title: String, val snippet: String?)

    private val lock = Mutex()

    /** Answered cards, and answered "no card" as null; a failed fetch is neither. */
    private val results = HashMap<FurumRef, Card?>()
    private val json = Json { ignoreUnknownKeys = true }

    /** The ship asked the host and was still waiting: once more, this long after. */
    private const val PENDING_RETRY_MS = 5_000L

    suspend fun await(
        http: HttpClient,
        shipUrl: String,
        cookie: String,
        ref: FurumRef,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Card? {
        lock.withLock { if (results.containsKey(ref)) return results[ref] }
        repeat(2) { attempt ->
            val resp = runSuspendCatching {
                http.get(ref.previewUrl(shipUrl)) { header(HttpHeaders.Cookie, cookie) }
            }.getOrNull() ?: return null
            when (resp.status.value) {
                200 -> {
                    val card = runSuspendCatching { cardOf(ref, json.parseToJsonElement(resp.bodyAsText()).jsonObject) }.getOrNull()
                    lock.withLock { results[ref] = card }
                    return card
                }
                202 -> if (attempt == 0) wait(PENDING_RETRY_MS)
                in 400..499 -> {
                    lock.withLock { results[ref] = null }
                    return null
                }
                else -> return null
            }
        }
        return null
    }

    /** The card from the route's answer; null where it says too little to show. */
    internal fun cardOf(ref: FurumRef, o: JsonObject): Card? {
        val board = o["board"] as? JsonObject
        val boardName = "f/${ref.host}/${ref.board}"
        if (ref.post == null) {
            val title = board.str("title") ?: return null
            return Card(boardName, title, board.str("description"))
        }
        val post = o["post"] as? JsonObject ?: return null
        val title = post.str("title") ?: return null
        val points = post.num("points")?.let { "$it ${if (it == 1L) "point" else "points"}" }
        val comments = post.num("comments")?.let { "$it ${if (it == 1L) "comment" else "comments"}" }
        val by = post.str("author")?.let { "by $it" }
        return Card(
            caption = listOfNotNull(boardName, points, comments).joinToString(" · "),
            title = title,
            snippet = post.str("excerpt") ?: by,
        )
    }

    private fun JsonObject?.str(k: String): String? =
        (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject?.num(k: String): Long? = (this?.get(k) as? JsonPrimitive)?.longOrNull

    /** For tests: forget every answer. */
    internal suspend fun clear() = lock.withLock { results.clear() }
}
