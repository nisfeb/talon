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

    /** Chat itself: a different desk from a different publisher. */
    const val GROUPS_PUBLISHER = "~nisfeb"
    const val GROUPS_DESK = "groups"

    /**
     * Whether %groups is on [shipUrl]. Unlike the lattice probe this one
     * is authenticated — groups has no unauthenticated surface — so it
     * needs the session's own client, and anything other than an answer
     * reads as absent rather than as an error.
     */
    suspend fun groupsInstalled(http: HttpClient, shipUrl: String): Boolean =
        runCatching {
            val resp: HttpResponse =
                http.get("${shipUrl.trimEnd('/')}/~/scry/groups/groups/v2/groups.json")
            resp.status.value == 200
        }.getOrDefault(false)

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
     * Install %grubbery and wait for it to arrive.
     *
     * Two steps that have to stay together: kiln accepting the poke is
     * not the desk being here — it arrives over the network afterwards
     * — so the only way to know is to keep asking. Callers that stopped
     * at the poke reported success onto a ship with no app on it.
     *
     * Failure carries a sentence worth showing. A timeout in particular
     * is not a refusal: the install may still land, and saying so is
     * more use than saying it failed.
     */
    suspend fun installAndWait(
        http: HttpClient,
        shipUrl: String,
        poke: suspend (String, String, JsonElement) -> Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        nowMs: () -> Long = { io.nisfeb.talon.util.nowMs() },
        wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        /** Which of ~ricsul-bilwyt's desks; each has its own way of
         *  showing it has arrived. */
        desk: String = DESK,
        installed: suspend () -> Boolean = { isInstalled(http, shipUrl) },
        /** Whose desk. Not every app Talon installs comes from one ship. */
        publisher: String = PUBLISHER,
    ): Result<Unit> {
        val (app, mark, body) = installPoke(desk, publisher)
        if (!poke(app, mark, body)) {
            return Result.failure(IllegalStateException("Your ship refused the install."))
        }
        val deadline = nowMs() + timeoutMs
        while (nowMs() < deadline) {
            wait(POLL_MS)
            if (installed()) return Result.success(Unit)
        }
        return Result.failure(
            IllegalStateException(
                "Install is taking a while — it may still finish. Try again shortly.",
            ),
        )
    }

    /** The install as one call, for the hosts that offer it from a menu. */
    fun installer(
        http: HttpClient,
        shipUrl: () -> String?,
        desk: String = DESK,
        installed: (suspend (String) -> Boolean)? = null,
        publisher: String = PUBLISHER,
        poke: suspend (String, String, JsonElement) -> Boolean,
    ): suspend () -> Result<Unit> = {
        val url = shipUrl()
        if (url == null) Result.failure(IllegalStateException("Not signed in to a ship."))
        else installAndWait(
            http, url, poke, desk = desk,
            installed = { installed?.invoke(url) ?: isInstalled(http, url) },
            publisher = publisher,
        )
    }

    private const val POLL_MS = 3_000L
    const val DEFAULT_TIMEOUT_MS = 90_000L

    /**
     * Poke our own %hood to install %grubbery from [PUBLISHER] — the
     * same action as `|install ~ricsul-bilwyt %grubbery`. kiln-install
     * takes json, so no dojo is needed. Returns (app, mark, body).
     */
    fun installPoke(desk: String = DESK, publisher: String = PUBLISHER): Triple<String, String, JsonElement> = Triple(
        "hood",
        "kiln-install",
        buildJsonObject {
            put("local", desk)
            put("ship", publisher)
            put("desk", desk)
        },
    )
}
