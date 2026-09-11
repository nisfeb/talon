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
    ): Result<Unit> {
        val (app, mark, body) = installPoke()
        if (!poke(app, mark, body)) {
            return Result.failure(IllegalStateException("Your ship refused the install."))
        }
        val deadline = nowMs() + timeoutMs
        while (nowMs() < deadline) {
            wait(POLL_MS)
            if (isInstalled(http, shipUrl)) return Result.success(Unit)
        }
        return Result.failure(
            IllegalStateException(
                "Install is taking a while — it may still finish. Try again shortly.",
            ),
        )
    }

    private const val POLL_MS = 3_000L
    const val DEFAULT_TIMEOUT_MS = 90_000L

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
