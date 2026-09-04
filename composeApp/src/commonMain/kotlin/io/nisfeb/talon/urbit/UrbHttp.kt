package io.nisfeb.talon.urbit

import io.ktor.http.encodeURLParameter

/**
 * Turns a `urb://` address into the HTTP URL that renders it.
 *
 * Resolution runs on the VIEWER's own ship: its lattice app reads the
 * referent and, when the author ship differs, fetches that content
 * over Ames server-side — invisible to the client. So the URL always
 * targets the viewer's ship base; the `urb://` string rides in the
 * `url=` query param, and the lattice reader returns HTML (a note
 * inline, or a 303 to its /x explorer for a page/tree node).
 */
object UrbHttp {
    /** The lattice reader URL on [shipUrl] for the address [urbUrl]
     *  (returns HTML — for the webview / browser). */
    fun readerUrl(shipUrl: String, urbUrl: String): String =
        "${shipUrl.trimEnd('/')}/apps/lattice?url=${urbUrl.encodeURLParameter()}"

    /** The lattice /fetch URL for [urbUrl] on [shipUrl] — returns the
     *  referent's body as gemtext JSON, for the inline unfurl. */
    fun fetchUrl(shipUrl: String, urbUrl: String): String =
        "${shipUrl.trimEnd('/')}/apps/lattice/fetch?url=${urbUrl.encodeURLParameter()}"

    /** POST target that publishes a gemtext note at [slug] on [shipUrl]. */
    fun saveUrl(shipUrl: String, slug: String): String =
        "${shipUrl.trimEnd('/')}/apps/lattice/save?path=${slug.encodeURLParameter()}"

    /**
     * The canonical urb:// for a just-published note. Deterministic
     * from (ship, slug) per lattice's en-urb: a single-char first
     * segment needs the /n/ mount to disambiguate from p/k/t; "index"
     * or empty is the ship's front door.
     */
    fun canonicalUrbUrl(ship: String, slug: String): String {
        val s = slug.trim('/')
        if (s.isEmpty() || s == "index") return "urb://$ship"
        return if (s.substringBefore('/').length == 1) "urb://$ship/n/$s" else "urb://$ship/$s"
    }
}
