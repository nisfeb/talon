package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.mail.AuspexError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One occurrence of an event inside a window: [l]..[r] in unix ms,
 * [all] for whole days, and the event's display [meta] (name, note,
 * color, location) passed through as the calendar stores it.
 */
@Serializable
data class CalendarRow(
    val id: String,
    val cal: String = "default",
    val idx: Int = 0,
    val meta: JsonObject = JsonObject(emptyMap()),
    val cat: String = "timed",
    val all: Boolean = false,
    val l: Long,
    val r: Long,
    /** A task's tick; false for events. */
    val done: Boolean = false,
) {
    val name: String get() = meta.metaStr("name")
    val note: String get() = meta.metaStr("note")
    val location: String get() = meta.metaStr("location")
    val color: String? get() = meta.metaStr("color").takeIf { it.isNotBlank() }
    /** iCalendar CATEGORIES, as the calendar keeps them: `meta.tags`. */
    val tags: List<String> get() = meta.metaTags()
    val isTask: Boolean get() = cat == "todo"
}

/**
 * One entry of the calendar's full listing (events.json): every event,
 * dated or not. The window feed places dated ones; this is where an
 * undated task lives. [dueMs] is midnight UTC of the due day.
 */
@Serializable
data class CalendarTask(
    val id: String,
    val cal: String = "default",
    val meta: JsonObject = JsonObject(emptyMap()),
    val cat: String = "timed",
    @SerialName("due_ms") val dueMs: Long? = null,
    val done: Boolean = false,
) {
    val name: String get() = meta.metaStr("name")
    val note: String get() = meta.metaStr("note")
    val color: String? get() = meta.metaStr("color").takeIf { it.isNotBlank() }
    val tags: List<String> get() = meta.metaTags()
}

internal fun JsonObject.metaStr(k: String): String = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
internal fun JsonObject.metaTags(): List<String> = (this["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

@Serializable
data class TagCount(val tag: String, val count: Int = 0)

@Serializable
data class CalendarWindow(val rows: List<CalendarRow> = emptyList())

@Serializable
data class CalendarInfo(val id: String, val name: String = "", val color: String = "", val kind: String = "local")

@Serializable
data class CalendarConfig(val title: String = "", val zone: String? = null, val ball: String = "")

/**
 * The calendar nexus on the user's ship, at /apps/calendar, over the
 * session's own cookie. Reads only: the calendar's own page does the
 * editing, and Talon opens it for that.
 *
 * Errors are [AuspexError]s: the three outcomes a nexus request has
 * are the same whichever nexus it is.
 */
class CalendarApi(private val http: HttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val root = base + APP_PATH

    /** Every occurrence between [fromMs] and [toMs]. */
    suspend fun window(fromMs: Long, toMs: Long): CalendarWindow =
        decode(get("/window.json?from=$fromMs&to=$toMs"))

    suspend fun calendars(): List<CalendarInfo> = decode(get("/calendars.json"))

    /** The tasks, dated or not: the full listing, kept to `cat == todo`. */
    suspend fun tasks(): List<CalendarTask> = decode<List<CalendarTask>>(get("/events.json")).filter { it.cat == "todo" }

    suspend fun config(): CalendarConfig = decode(get("/config.json"))

    /** Every zone name the calendar knows, for the editor. */
    suspend fun zones(): List<String> = decode(get("/zones.json"))

    /** Every tag in use, with how many events carry it. */
    suspend fun tags(): List<TagCount> = decode(get("/tags.json"))

    /** Turn a followed or Google calendar into a plain local one: one
     *  last pull, then the sync row goes. The source is left alone. */
    suspend fun migrate(calId: String): Boolean =
        postJson("$root/migrate", buildJsonObject { put("id", calId) })

    /** One event's full rule breakdown, for the editor. */
    suspend fun event(id: String): JsonObject =
        decode(get("/event.json?id=" + id.encodeURLParameter()))

    /**
     * A write. The calendar takes them as pokes on its ball through
     * the grubbery shell, the way its own page sends them; [ball] is
     * what config.json named. True when the shell accepted it.
     */
    suspend fun poke(ball: String, body: JsonObject): Boolean =
        postJson("$base/grubbery/api/poke/$ball/calendar.calendar?blot=/json", body)

    private suspend fun postJson(url: String, body: JsonObject): Boolean {
        val resp = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw AuspexError.Unreachable(t)
        }
        return resp.status.isSuccess()
    }

    private suspend fun get(path: String): String {
        val resp = try {
            http.get(root + path)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw AuspexError.Unreachable(t)
        }
        val text = try { resp.bodyAsText() } catch (t: Throwable) { throw AuspexError.Garbled(t) }
        if (!resp.status.isSuccess()) throw AuspexError.Refused(resp.status.value, text.take(200))
        return text
    }

    private inline fun <reified T> decode(text: String): T =
        try { AuspexApi.json.decodeFromString<T>(text) } catch (t: Throwable) { throw AuspexError.Garbled(t) }

    companion object {
        const val APP_PATH = "/apps/calendar"
    }
}
