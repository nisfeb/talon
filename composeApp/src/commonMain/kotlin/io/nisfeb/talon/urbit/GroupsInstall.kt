package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * Detecting and installing %groups, which is what chat itself runs on:
 * no groups, no channels and no DMs.
 *
 * Its own desk from its own publisher, unlike mail, the calendar and
 * lattice, which all ride inside Grubbery.
 */
object GroupsInstall {
    const val PUBLISHER = "~nisfeb"
    const val DESK = "groups"

    /**
     * Whether %groups is on [shipUrl]. Scries the same group listing the
     * chat bootstrap reads, so this asks nothing the app does not already
     * depend on. Authenticated: [http] has to be the session's client,
     * because a scry without the cookie is a 403 whatever is installed.
     */
    suspend fun isInstalled(http: HttpClient, shipUrl: String): Boolean =
        runCatching {
            val resp: HttpResponse = http.get("${shipUrl.trimEnd('/')}/~/scry/groups/v2/groups.json")
            resp.status.isSuccess()
        }.getOrDefault(false)

    /** The install, for the hosts that offer it. */
    fun installer(
        http: HttpClient,
        shipUrl: () -> String?,
        poke: suspend (String, String, kotlinx.serialization.json.JsonElement) -> Boolean,
    ): suspend () -> Result<Unit> = LatticeInstall.installer(
        http = http,
        shipUrl = shipUrl,
        desk = DESK,
        publisher = PUBLISHER,
        installed = { url -> isInstalled(http, url) },
        poke = poke,
    )
}
