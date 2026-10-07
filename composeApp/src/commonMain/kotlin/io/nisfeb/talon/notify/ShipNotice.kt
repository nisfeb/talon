package io.nisfeb.talon.notify

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A push from another app on the owner's ship (calendar, orrery, mail),
 * sent through its %trunk (wire 12 push-notice): shown like grubbery's
 * web push, by its tag. Null for anything else.
 */
fun noticeOf(push: JsonObject): ShipPushMessage? {
    if ((push["event"] as? JsonPrimitive)?.contentOrNull != "notice") return null
    fun str(k: String) = (push[k] as? JsonPrimitive)?.contentOrNull
    val title = str("title") ?: return null
    return ShipPushMessage(title = title, body = str("body").orEmpty(), tag = str("tag")?.takeIf { it.isNotBlank() })
}

/**
 * "Open what this notice is about" from outside the composition: a tapped
 * notice on iOS lands here by its tag, and the app host navigates (a
 * calendar tag opens its event).
 */
object OpenNoticeRequests {
    private val _requests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requests: kotlinx.coroutines.flow.SharedFlow<String> = _requests
    fun request(tag: String) { _requests.tryEmit(tag) }
}

/**
 * One line on how the ship's pushes to this device are going, from its
 * %trunk status (wire 12 debug.json), for Settings: when it last pushed
 * here, a failing last try, or that it dropped the device. Null without
 * a status to read (an older trunk).
 */
fun shipPushStatusLine(debug: JsonElement?, deviceId: String): String? {
    val d = debug as? JsonObject ?: return null
    val now = (d["now"] as? JsonPrimitive)?.longOrNull ?: return null
    fun ago(at: Long): String {
        val mins = ((now - at) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} days ago"
        }
    }
    fun JsonElement?.obj() = this as? JsonObject
    fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    val device = (d["devices"] as? kotlinx.serialization.json.JsonArray)?.map { it.obj() }
        ?.firstOrNull { (it?.get("id") as? JsonPrimitive)?.contentOrNull == deviceId }
    if (device == null) {
        val drop = (d["drops"] as? kotlinx.serialization.json.JsonArray)?.map { it.obj() }
            ?.firstOrNull { (it?.get("id") as? JsonPrimitive)?.contentOrNull == deviceId }
        val why = (drop?.get("reason") as? JsonPrimitive)?.contentOrNull
        return if (why != null) "Your ship stopped pushing here (its push service answered $why). Talon sets it up again when it next starts."
        else "Your ship does not have this device yet. Talon sets it up again when it next starts."
    }
    val sentAt = device["sent"].obj()?.long("at")
    val last = device["last"].obj()
    val lastAt = last?.long("at")
    val code = last?.long("code")
    if (lastAt != null && code != null && code !in 200..299 && (sentAt == null || lastAt > sentAt)) {
        val what = if (code == 0L) "got no answer" else "got $code"
        return "The last notification ${ago(lastAt)} $what from the push service; your ship tries again."
    }
    return if (sentAt != null) "Last notification ${ago(sentAt)}." else "No notification sent here yet."
}
