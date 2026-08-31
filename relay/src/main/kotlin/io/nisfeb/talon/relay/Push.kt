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
 *   {
 *     "event": "ring",
 *     "patp":  "<ship>",
 *     "from":  "<caller>",
 *     "id":    "<call-id>"
 *   }
 * The client wakes, posts the local notification, and pulls the
 * actual message body — or the call's SDP — via SSE. Neither message
 * text nor any part of the media negotiation transits the relay or
 * the distributor.
 */
class Push {

    private val log = LoggerFactory.getLogger("Push")
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Wake the device for an incoming call. Same hint-only contract:
     *  the caller's @p and the call id are all that travel — the SDP
     *  and fingerprint stay on the ship's own channel. */
    fun sendRing(endpoint: String, patp: String, from: String, callId: String) {
        val body = buildString {
            append("""{"event":"ring","patp":"""")
            append(escape(patp))
            append("""","from":"""")
            append(escape(from))
            append("""","id":"""")
            append(escape(callId))
            append("\"}")
        }
        // A ring is worthless once the caller has given up, so it is
        // urgent and short-lived: better to drop it than deliver it
        // into an empty room ten minutes later.
        post(endpoint, body, urgency = "high", ttlSecs = RING_TTL_SECS)
    }

    /** Un-ring the device: the caller hung up, or another of the
     *  user's clients answered. Same urgency/TTL treatment as the
     *  ring itself — a cancel is worthless late, better dropped than
     *  delivered to a phone that stopped ringing minutes ago. */
    fun sendRingCancel(endpoint: String, patp: String, callId: String) {
        val body = buildString {
            append("""{"event":"ring-cancel","patp":"""")
            append(escape(patp))
            append("""","id":"""")
            append(escape(callId))
            append("\"}")
        }
        post(endpoint, body, urgency = "high", ttlSecs = RING_TTL_SECS)
    }

    fun send(endpoint: String, patp: String, whom: String, postId: String) {
        // Hand-rolled JSON to avoid pulling kotlinx-serialization
        // through Push's hot path. The fields are all server-
        // controlled: patp matches `~[a-z0-9-]+`, whom is one of
        // {ship-patp, club-id (0v…), nest path}, and postId is the
        // globally-unique `<author>/<128-bit-id>` from the activity
        // event's dm-post.key.id (or chan-post / club-post equivalent).
        // Quote-escape on whom is defensive for the nest case which
        // can carry slashes; postId carries a forward slash by design
        // so it's escaped too.
        val body = buildString {
            append("""{"event":"new-message","patp":"""")
            append(escape(patp))
            append("""","whom":"""")
            append(escape(whom))
            append("""","id":"""")
            append(escape(postId))
            append("\"}")
        }
        post(endpoint, body)
    }

    /**
     * @param urgency RFC 8030 urgency. A push server may batch or defer
     *   anything below "high" to save the device's battery, which is
     *   right for a message and fatal for a ring: the notification
     *   carries a 45s timeout, so a ring delivered late shows nothing
     *   at all. Distributors backed by a real WebPush service honour
     *   this; ntfy holds a socket open and never needed it, which is
     *   why the gap only appears on some distributors.
     * @param ttlSecs how long the server may hold it for a device that
     *   is offline. RFC 8030 makes TTL mandatory, and we were sending
     *   none — a ring worth nothing after a minute was being retained
     *   on the server's default, which is hours.
     */
    private fun post(
        endpoint: String,
        body: String,
        urgency: String = "normal",
        ttlSecs: Int = 86_400,
    ) {
        val req = Request.Builder()
            .url(endpoint)
            .header("TTL", ttlSecs.toString())
            .header("Urgency", urgency)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
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

        /** Slightly over the client's 45s ring timeout. */
        private const val RING_TTL_SECS = 60
    }
}
