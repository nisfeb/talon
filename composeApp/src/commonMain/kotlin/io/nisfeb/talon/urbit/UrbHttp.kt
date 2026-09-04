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
    /** The lattice reader URL on [shipUrl] for the address [urbUrl]. */
    fun readerUrl(shipUrl: String, urbUrl: String): String =
        "${shipUrl.trimEnd('/')}/apps/lattice?url=${urbUrl.encodeURLParameter()}"
}
