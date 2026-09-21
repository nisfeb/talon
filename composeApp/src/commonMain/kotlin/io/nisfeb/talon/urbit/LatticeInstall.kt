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
        /** Which of ~ricsul-bilwyt's desks; each has its own way of
         *  showing it has arrived. */
        desk: String = DESK,
        installed: suspend () -> Boolean = { isInstalled(http, shipUrl) },
        /** Whose desk. Not every app Talon installs comes from one ship. */
        publisher: String = PUBLISHER,
    ): Result<Unit> {
        val (app, mark, body) = installPoke(desk, publisher)
        if (!poke(app, mark, body)) {
            // The poke failing is not the ship saying no: it is also a
            // channel that is not up and a session that has gone. The
            // old wording blamed the ship for both.
            return Result.failure(
                IllegalStateException("Your ship did not take the install. It may be offline, or signed out here."),
            )
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
    ): suspend () -> Result<Unit> {
        // The default probe reads the lattice manifest, which only the
        // lattice desk serves — a caller installing another desk must
        // say how THAT desk shows it has arrived, or the wait would
        // watch the wrong surface and never finish.
        require(desk == DESK || installed != null) {
            "installing $desk needs an `installed` probe; the default checks the lattice manifest"
        }
        // A grubbery app is not a desk. Kiln would take the poke and
        // nothing would arrive, which is what a calendar install did.
        require(desk == DESK || desk !in GRUBBERY_APPS) {
            "$desk lives inside the ${DESK} desk; install $DESK and wait for $desk to answer (see grubberyApp)"
        }
        return {
            val url = shipUrl()
            if (url == null) Result.failure(IllegalStateException("Not signed in to a ship."))
            else installAndWait(
                http, url, poke, desk = desk,
                installed = { installed?.invoke(url) ?: isInstalled(http, url) },
                publisher = publisher,
            )
        }
    }

    /**
     * The apps that live INSIDE the grubbery desk rather than beside
     * it. None of them is a desk, so none of them can be installed with
     * kiln: asking ~ricsul-bilwyt for a desk it does not publish is a
     * poke that goes nowhere the owner can see, and a button that never
     * finishes. Installing any of them installs Grubbery.
     */
    val GRUBBERY_APPS = setOf("lattice", "auspex", "mail", "calendar", "orrery")

    /**
     * Install a grubbery app: fetch the desk it lives in, and wait for
     * the app itself to answer.
     *
     * A timeout where Grubbery is answering and the app is not says
     * something worth saying: the desk is here and predates the app,
     * so there is nothing to install and waiting will not help. It
     * updates itself from its publisher.
     */
    fun grubberyApp(
        http: HttpClient,
        shipUrl: () -> String?,
        app: String,
        answers: suspend (String) -> Boolean,
        timeoutMs: Long = GRUBBERY_TIMEOUT_MS,
        poke: suspend (String, String, JsonElement) -> Boolean,
    ): suspend () -> Result<Unit> = {
        val url = shipUrl()
        if (url == null) {
            Result.failure(IllegalStateException("Not signed in to a ship."))
        } else {
            installAndWait(http, url, poke, timeoutMs = timeoutMs, installed = { answers(url) })
                .recoverCatching { e ->
                    if (isInstalled(http, url)) {
                        // What was seen, and the two things it is. Saying
                        // only the first would tell somebody whose session
                        // went stale to go and wait for an update.
                        error(
                            "Grubbery is on this ship and its $app is not answering. " +
                                "A Grubbery older than the $app updates itself from its publisher; " +
                                "otherwise sign in to the ship again.",
                        )
                    }
                    throw e
                }
        }
    }

    private const val POLL_MS = 3_000L

    /** A whole desk over ames, on somebody's phone: ninety seconds was not always enough. */
    const val GRUBBERY_TIMEOUT_MS = 180_000L
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
