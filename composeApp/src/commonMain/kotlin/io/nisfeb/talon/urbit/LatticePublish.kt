package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Publish a gemtext note to the user's own ship's Lattice, via the
 * authenticated same-origin POST /apps/lattice/save (the app HTTP
 * client already carries the ship session cookie). Returns the
 * canonical urb:// of the new note.
 */
object LatticePublish {
    suspend fun publish(
        http: HttpClient,
        shipUrl: String,
        ourShip: String,
        cookie: String,
        slug: String,
        gemtext: String,
    ): String {
        val resp: HttpResponse = http.post(UrbHttp.saveUrl(shipUrl, slug)) {
            // The shared http client has no cookie store; authenticate
            // the save explicitly or eyre answers 403.
            header("Cookie", cookie)
            contentType(ContentType.Text.Plain)
            setBody(gemtext)
        }
        if (resp.status.value != 200) error("Lattice save returned ${resp.status.value}")
        return UrbHttp.canonicalUrbUrl(ourShip, slug)
    }

    /**
     * The same save, for a client whose own cookie store carries the
     * session. The overload above exists for the shared client, which
     * has none and has to be handed the header.
     */
    suspend fun publish(
        http: HttpClient,
        shipUrl: String,
        ourShip: String,
        slug: String,
        gemtext: String,
    ): String {
        val resp: HttpResponse = http.post(UrbHttp.saveUrl(shipUrl, slug)) {
            contentType(ContentType.Text.Plain)
            setBody(gemtext)
        }
        if (resp.status.value != 200) error("Lattice save returned ${resp.status.value}")
        return UrbHttp.canonicalUrbUrl(ourShip, slug)
    }

    /**
     * A stable, readable slug under a `talon/` namespace, made unique
     * per source thread by a hash of [seed] (the parent post id)
     * so two threads with the same title don't clobber each other,
     * while re-publishing the same thread edits in place.
     */
    fun slug(title: String, seed: String): String {
        val base = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(48)
            .ifEmpty { "note" }
        // 64-bit FNV-1a, eight base-36 digits (~41 bits). The 32-bit
        // hashCode suffix this replaces collided inside ~2^16 same-title
        // threads; String.hashCode was already stable, but four digits
        // only ever carried ~20 bits of it.
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (b in seed.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L
        }
        val suffix = (h and Long.MAX_VALUE).toString(36).padStart(8, '0').takeLast(8)
        return "talon/$base-$suffix"
    }
}
