package io.nisfeb.talon.urbit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Whether a bot's gateway is up, as its ship publishes it (Tlon 12.3.0,
 * docs/bot-liveness.md): the `bot-liveness` contact field. Kept here and
 * not stored: it changes with the gateway, and every launch's contact
 * sync brings it again.
 */
object BotLiveness {
    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Bot ship to whether its gateway is up; a ship not here is unknown. */
    val online: StateFlow<Map<String, Boolean>> = _online.asStateFlow()

    /** What [ship]'s contact [fields] say, replacing what was known. */
    fun record(ship: String, fields: JsonObject) {
        val up = botLivenessOf(ship, fields)
        // Nearly every contact: no bot, nothing known of it, nothing to do.
        // It runs for each of thousands in a contacts bootstrap.
        if (_online.value[ship] == up) return
        _online.update { if (up == null) it - ship else it + (ship to up) }
    }
}

/**
 * A bot's liveness claim, read as Tlon reads it: shown for bots only (a
 * `~pinser-botter-` ship, or one claiming `bot-info`), from a text field
 * of at most 128 bytes holding `{"v":1,"state":"online"|"offline"}`.
 * True for online, false for offline, null for anything else.
 */
internal fun botLivenessOf(ship: String, fields: JsonObject): Boolean? {
    if (!ship.startsWith("~pinser-botter-") && !fields.containsKey("bot-info")) return null
    val field = fields["bot-liveness"] as? JsonObject ?: return null
    if (field["type"].asStr() != "text") return null
    val raw = field["value"].asStr() ?: return null
    if (raw.encodeToByteArray().size > 128) return null
    val claim = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    if ((claim["v"] as? JsonPrimitive)?.intOrNull != 1) return null
    return when (claim["state"].asStr()) {
        "online" -> true
        "offline" -> false
        else -> null
    }
}
