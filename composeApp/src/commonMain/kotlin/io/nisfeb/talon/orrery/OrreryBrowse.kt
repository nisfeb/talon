package io.nisfeb.talon.orrery

import io.nisfeb.talon.ui.parseIsoUtc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One thing orrery knows, as the Orrery section shows it. [values] holds
 * each attribute's current values: one for most, several for a list such
 * as participants. A value is text, or `{"ref": "kind/slug"}` for another
 * thing it knows.
 */
data class OrreryItem(
    val id: String,
    val kind: String,
    val name: String,
    val aliases: List<String>,
    val values: Map<String, List<JsonElement>>,
) {
    fun text(attr: String): String? = (values[attr]?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun refs(attr: String): List<String> =
        values[attr].orEmpty().mapNotNull { ((it as? JsonObject)?.get("ref") as? JsonPrimitive)?.contentOrNull }
    fun ms(attr: String): Long? = text(attr)?.let(::parseIsoUtc)
    /** Where it is: an activity's or a situation's location, a place's address. */
    val where: String? get() = text("location") ?: text("address")
}

/** Every thing in orrery's state (GET /api/state), as it stands. */
fun orreryItems(state: JsonObject): List<OrreryItem> = (state["bodies"] as? JsonArray).orEmpty().mapNotNull { b ->
    val o = b as? JsonObject ?: return@mapNotNull null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    val id = str("id") ?: return@mapNotNull null
    fun current(v: JsonElement): JsonElement? = (v as? JsonObject)?.get("value")?.takeUnless { it is JsonNull }
    OrreryItem(
        id = id,
        kind = str("kind") ?: id.substringBefore('/'),
        name = str("name")?.takeIf { it.isNotBlank() } ?: id.substringAfter('/'),
        aliases = (o["aliases"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        values = (o["attrs"] as? JsonObject).orEmpty()
            .mapValues { (_, v) -> if (v is JsonArray) v.mapNotNull(::current) else listOfNotNull(current(v)) }
            .filterValues { it.isNotEmpty() },
    )
}

/** One entry of Coming up: [item] from [startMs], to [endMs] where it says. */
data class Upcoming(val item: OrreryItem, val startMs: Long, val endMs: Long?)

/** A situation or activity so marked is over, whatever its times say. */
private val FINISHED = setOf("closed", "ended", "done", "resolved", "cancelled", "canceled")

/** An activity's next time stays on Coming up this long after it starts: it says no end. */
private const val UNDER_WAY_MS = 60 * 60_000L

/**
 * What is ahead, soonest first: situations by their start, activities by
 * their next time. One under way stays (a situation until it ends, an
 * activity for an hour); one finished or already over does not.
 */
fun comingUp(items: List<OrreryItem>, nowMs: Long): List<Upcoming> = items.mapNotNull { i ->
    if (i.text("status") in FINISHED) return@mapNotNull null
    when (i.kind) {
        "situation" -> {
            val start = i.ms("starts") ?: return@mapNotNull null
            val end = i.ms("ends")
            Upcoming(i, start, end).takeIf { (end ?: start) >= nowMs }
        }
        "activity" -> {
            val next = i.ms("next") ?: return@mapNotNull null
            Upcoming(i, next, null).takeIf { next + UNDER_WAY_MS >= nowMs }
        }
        else -> null
    }
}.sortedBy { it.startMs }

/** The kinds in the order Browse lists them, with their headings. Others follow, as named. */
val ORRERY_KINDS = listOf(
    "situation" to "Situations", "activity" to "Activities", "person" to "People", "place" to "Places",
    "org" to "Organizations", "thing" to "Things", "note" to "Notes",
)

/** Every thing whose name or an alias holds [query], under its kind's heading, each kind by name. */
fun browse(items: List<OrreryItem>, query: String): List<Pair<String, List<OrreryItem>>> {
    val q = query.trim()
    val hits = items.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.aliases.any { a -> a.contains(q, ignoreCase = true) } }
    val known = ORRERY_KINDS.map { it.first }.toSet()
    val headed = ORRERY_KINDS + hits.map { it.kind }.filter { it !in known }.distinct().sorted().map { it to it.replaceFirstChar(Char::uppercase) }
    return headed.mapNotNull { (kind, heading) ->
        hits.filter { it.kind == kind }.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { heading to it }
    }
}

/** The thing a leave alert is about: its tag's key without the occurrence ("activity/x@123" is "activity/x"). */
fun leaveItemOf(tag: String?): String? = leaveKeyOfTag(tag)?.substringBefore('@')?.takeIf { it.isNotBlank() }

/** The ship's next time to leave (GET /api/travel/last's `next`): for which thing, when, and how long the drive. */
data class LeaveBy(val itemId: String, val leaveByMs: Long, val minutes: Int)

fun leaveByOf(last: JsonObject?): LeaveBy? {
    val next = last?.get("next") as? JsonObject ?: return null
    fun str(k: String) = (next[k] as? JsonPrimitive)?.contentOrNull
    return LeaveBy(
        itemId = str("key")?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: return null,
        leaveByMs = str("leave_by")?.let(::parseIsoUtc) ?: return null,
        minutes = (next["minutes"] as? JsonPrimitive)?.intOrNull ?: 0,
    )
}
