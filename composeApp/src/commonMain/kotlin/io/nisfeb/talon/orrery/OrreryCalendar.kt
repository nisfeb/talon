package io.nisfeb.talon.orrery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * What the triage proposes about the calendar when somebody says an
 * occurrence is off. Writing the calendar's events to the ship is the
 * ship's own work (orrery 47), not this app's.
 */

internal const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The action that offers to take a cancelled occurrence off the
 * calendar, as orrery 37 takes one.
 *
 * `title` and `starts` are kept as an ordinary add has them, because
 * the ship's payload shape refuses one that lacks a required key
 * whatever the mode says. `event` is the calendar's own id for the
 * event, which is what the executor pokes the calendar with, and
 * `starts` is the moment the occurrence really begins, since the
 * calendar skips by that moment and drops a skip it cannot place.
 */
internal fun cancelAction(subject: String, name: String, event: String, startsMs: Long): JsonObject =
    buildJsonObject {
        put("kind", "calendar")
        put("title", "Cancel $name")
        putJsonArray("about") { add(JsonPrimitive(subject)) }
        put("payload", buildJsonObject {
            put("title", name)
            put("starts", isoUtc(startsMs))
            put("mode", "cancel")
            put("event", event)
        })
        put("message", "$name on ${isoUtc(startsMs)} was called off.")
    }

/**
 * The occurrence [starts] nearest [nearMs], within a day of it.
 *
 * A reading of "tonight" lands on the hour the schedule says, which
 * is not always the hour the calendar holds: the occurrence that was
 * moved half an hour is still the one being called off. Beyond a day
 * it is a different occurrence and nothing is matched, because
 * skipping the wrong evening is worse than skipping none.
 */
internal fun occurrenceNear(starts: List<Long>, nearMs: Long): Long? =
    starts.filter { kotlin.math.abs(it - nearMs) < DAY_MS }
        .minByOrNull { kotlin.math.abs(it - nearMs) }
