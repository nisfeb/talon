package io.nisfeb.talon.relay

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * UnifiedPush dispatch — plain HTTP POST to the per-device
 * distributor endpoint URL the client supplied at /register time.
 *
 * UnifiedPush in one paragraph: every device has a "distributor"
 * app installed (ntfy, NextPush, Conversations, …) holding one
 * persistent connection to its own server. When the user registers
 * Talon with that distributor, the distributor mints an HTTPS URL
 * (`endpoint`). Anything POSTed to that URL gets routed to Talon
 * on the device. We don't care about the URL's shape — just POST.
 *
 * Payload (hint-only, per the design doc):
 *   {
 *     "event": "new-message",
 *     "patp":  "<ship>",
 *     "whom":  "<conversation>",
 *     "id":    "<event-id>"
 *   }
 * The client wakes, posts the local notification, and pulls the
 * actual message body via SSE — message text never transits the
 * relay or the distributor.
 */
class Push {

    private val log = LoggerFactory.getLogger("Push")
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun send(endpoint: String, patp: String, whom: String, eventId: Long) {
        log.info("send entered: patp=$patp whom=$whom id=$eventId endpoint=${endpoint.take(48)}…")
        // Hand-rolled JSON to avoid pulling kotlinx-serialization
        // through Push's hot path. The fields are all server-
        // controlled: patp matches `~[a-z0-9-]+` and whom is one of
        // {ship-patp, club-id (0v…), nest path}. Quote-escape is
        // defensive for the nest case which can carry slashes.
        val body = buildString {
            append("""{"event":"new-message","patp":"""")
            append(escape(patp))
            append("""","whom":"""")
            append(escape(whom))
            append("""","id":"""")
            append(eventId)
            append("\"}")
        }
        val req = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    log.info("push HTTP ${resp.code} → ${endpoint.take(64)}…")
                } else {
                    // 410 Gone = UnifiedPush "endpoint expired,
                    // device must re-register." Surface the code so
                    // the caller can decide whether to mark the row
                    // expired; we don't side-effect from here.
                    log.warn("push HTTP ${resp.code} → ${endpoint.take(64)}…")
                }
            }
        } catch (e: Throwable) {
            // One bad endpoint shouldn't kill the SSE consumer for
            // every other user on the same ship.
            log.warn("push failed → ${endpoint.take(64)}…: ${e.message}")
        }
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
