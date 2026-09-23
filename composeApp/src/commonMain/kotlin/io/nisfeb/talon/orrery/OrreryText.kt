package io.nisfeb.talon.orrery

import io.nisfeb.talon.ui.parseIsoUtc
import io.nisfeb.talon.urbit.asText
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Reading the ship's state view, and saying when in the owner's own words. Pure. */
object OrreryText {
    fun bodies(state: JsonObject): List<JsonObject> =
        (state["bodies"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.str(k: String): String? = this[k].asText()

    /** The owner's own body id: what the ship says, or the one it always is. */
    fun me(state: JsonObject): String = state.str("me") ?: "person/me"

    /** One body of the state view by its id. */
    fun bodyOf(state: JsonObject, id: String): JsonObject? = bodies(state).firstOrNull { it.str("id") == id }

    /** An attribute's current value: one, or a list of them for a multi. */
    fun value(body: JsonObject, attr: String): JsonElement? =
        when (val v = (body["attrs"] as? JsonObject)?.get(attr)) {
            is JsonObject -> v["value"]
            is JsonArray -> JsonArray(v.mapNotNull { (it as? JsonObject)?.get("value") })
            else -> null
        }

    fun text(body: JsonObject, attr: String): String? = value(body, attr).asText()

    fun clock(ms: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    private fun titled(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }

    /** "Thu 24 Sep 22:00" in [zone], with the clock as the owner reads it there. */
    fun whenText(ms: Long, zone: TimeZone, twentyFourHour: Boolean = true): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return dayText(t.date) + " " + io.nisfeb.talon.ui.SkyClock.clockLabel(t.hour * 60 + t.minute, twentyFourHour)
    }

    private fun dayText(d: LocalDate) = titled(d.dayOfWeek.name).take(3) + " " + d.dayOfMonth + " " + titled(d.month.name).take(3)

    /**
     * An action's due as [whenText] says it, or "Thu 24 Sep" for a day
     * with no time, which an instant parse drops; null for neither.
     */
    fun dueText(due: String, zone: TimeZone, twentyFourHour: Boolean = true): String? =
        parseIsoUtc(due)?.let { whenText(it, zone, twentyFourHour) }
            ?: runCatching { LocalDate.parse(due.trim()) }.getOrNull()?.let(::dayText)
}
