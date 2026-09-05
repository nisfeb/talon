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
     * A stable, readable slug under a `talon/` namespace, made unique
     * per source thread by a short hash of [seed] (the parent post id)
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
        val suffix = seed.hashCode().toUInt().toString(36).takeLast(4)
        return "talon/$base-$suffix"
    }
}
