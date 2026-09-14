package io.nisfeb.talon.calendar

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.nisfeb.talon.mail.AuspexApi
import io.nisfeb.talon.mail.AuspexError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
) {
    val name: String get() = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val note: String get() = meta["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val location: String get() = meta["location"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val color: String? get() = meta["color"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

@Serializable
data class CalendarWindow(val rows: List<CalendarRow> = emptyList())

@Serializable
data class CalendarInfo(val id: String, val name: String = "", val color: String = "", val kind: String = "local")

@Serializable
data class CalendarConfig(val title: String = "", val zone: String? = null)

/**
 * The calendar nexus on the user's ship, at /apps/calendar, over the
 * session's own cookie. Reads only: the calendar's own page does the
 * editing, and Talon opens it for that.
 *
 * Errors are [AuspexError]s: the three outcomes a nexus request has
 * are the same whichever nexus it is.
 */
class CalendarApi(private val http: HttpClient, baseUrl: String) {
    private val root = baseUrl.trimEnd('/') + APP_PATH

    /** Every occurrence between [fromMs] and [toMs]. */
    suspend fun window(fromMs: Long, toMs: Long): CalendarWindow =
        decode(get("/window.json?from=$fromMs&to=$toMs"))

    suspend fun calendars(): List<CalendarInfo> = decode(get("/calendars.json"))

    suspend fun config(): CalendarConfig = decode(get("/config.json"))

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
