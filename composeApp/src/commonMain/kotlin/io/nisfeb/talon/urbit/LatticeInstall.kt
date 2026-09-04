package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Detecting and installing the lattice app (the %grubbery desk) on the
 * user's own ship, so urb:// links have something to resolve them.
 */
object LatticeInstall {
    const val PUBLISHER = "~ricsul-bilwyt"
    const val DESK = "grubbery"

    /**
     * Whether lattice is installed on [shipUrl]. Probes the PWA
     * manifest, which lattice serves UNAUTHENTICATED, so this needs no
     * cookie and returns 404 cleanly when the desk is absent — unlike a
     * %gu scry, which would crash on a missing agent.
     */
    suspend fun isInstalled(http: HttpClient, shipUrl: String): Boolean =
        runCatching {
            val resp: HttpResponse =
                http.get("${shipUrl.trimEnd('/')}/apps/lattice/manifest.webmanifest")
            resp.status.value == 200
        }.getOrDefault(false)

    /**
     * Poke our own %hood to install %grubbery from [PUBLISHER] — the
     * same action as `|install ~ricsul-bilwyt %grubbery`. kiln-install
     * takes json, so no dojo is needed. Returns (app, mark, body).
     */
    fun installPoke(): Triple<String, String, JsonElement> = Triple(
        "hood",
        "kiln-install",
        buildJsonObject {
            put("local", DESK)
            put("ship", PUBLISHER)
            put("desk", DESK)
        },
    )
}
