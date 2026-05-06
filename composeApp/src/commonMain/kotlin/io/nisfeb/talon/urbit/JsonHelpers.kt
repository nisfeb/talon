package io.nisfeb.talon.urbit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Safe JSON accessors. The stock `.jsonPrimitive` extension throws if
 * the element is a JsonObject or JsonArray — and the parsers in this
 * package feed on external payloads where schema drift means we can
 * show up with the wrong shape. The fuzzer surfaced every parser that
 * crashed instead of degrading to null.
 *
 * Use these on any `JsonElement?` retrieved from a foreign object.
 */

/** String content if this is a JSON string primitive. Numbers, bools, null, and composite elements return null. */
internal fun JsonElement?.asStr(): String? {
    val p = this as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

/** String content for any non-null primitive (string, number, bool). Null for JsonNull and composite elements. */
internal fun JsonElement?.asText(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/** Long value if this is a numeric primitive, else null. */
internal fun JsonElement?.asLong(): Long? =
    (this as? JsonPrimitive)?.longOrNull

/** Int value if this is a numeric primitive, else null. */
internal fun JsonElement?.asInt(): Int? =
    (this as? JsonPrimitive)?.intOrNull

/** Boolean value if this is a boolean primitive, else null. */
internal fun JsonElement?.asBool(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull
