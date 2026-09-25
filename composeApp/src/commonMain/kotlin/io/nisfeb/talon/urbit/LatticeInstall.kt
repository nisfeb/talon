package io.nisfeb.talon.urbit

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    suspend fun isInstalled(http: HttpClient, shipUrl: String): Boolean = installedOrUnknown(http, shipUrl) == true

    /**
     * Whether lattice is on [shipUrl], or null where the ship could not
     * be asked. Only a 404 is absent: a failed probe read as absent
     * offered an install over a desk that was there.
     */
    suspend fun installedOrUnknown(http: HttpClient, shipUrl: String): Boolean? =
        probe { http.get("${shipUrl.trimEnd('/')}/apps/lattice/manifest.webmanifest") }

    /** A probe's answer: 2xx is there, 404 is not, and anything else (offline, a 5xx, signed out) is not knowing. */
    internal suspend fun probe(ask: suspend () -> HttpResponse): Boolean? {
        val status = try {
            ask().status
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return when {
            status.isSuccess() -> true
            status.value == 404 -> false
            else -> null
        }
    }

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
     * Add a desk to the grubbery shell, which is how an app that is not
     * part of grubbery itself arrives: orrery and armillary are shell
     * desks published by [PUBLISHER], not kiln desks, so `|install` can
     * never fetch them. The shell's own route takes the name and where
     * to read the code from, as the README's curl does.
     */
    suspend fun addDesk(
        http: HttpClient,
        shipUrl: String,
        name: String,
        publisher: String = PUBLISHER,
    ): Result<Unit> = runCatching {
        val body = buildJsonObject {
            put("name", name)
            put("code", "$publisher/apps/shell.shell/desks/$name.desk/desk/code")
        }
        val resp = http.post("${shipUrl.trimEnd('/')}/apps/grubbery/desks/add") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!resp.status.isSuccess()) {
            error(
                when (resp.status.value) {
                    403 -> "Your ship did not accept that; sign in to it again."
                    404 -> "The Grubbery shell is not answering on this ship."
                    else -> "The shell would not add $name (HTTP ${resp.status.value})."
                },
            )
        }
    }

    /**
     * Install a shell desk: the Grubbery shell first where it is not
     * here, then the desk itself through the shell, then wait for the
     * app to answer.
     *
     * The wait is the honest part. The shell syncs the desk over the
     * network in its own time, and the owner still has to approve the
     * roads it reaches outside its own tree, which happens on the
     * ship's own page and not here. So a timeout says that rather than
     * calling the install failed.
     */
    fun shellDesk(
        http: HttpClient,
        shipUrl: () -> String?,
        name: String,
        answers: suspend (String) -> Boolean,
        timeoutMs: Long = GRUBBERY_TIMEOUT_MS,
        poke: suspend (String, String, JsonElement) -> Boolean,
    ): suspend () -> Result<Unit> = {
        val url = shipUrl()
        if (url == null) {
            Result.failure(IllegalStateException("Not signed in to a ship."))
        } else {
            runCatching {
                // The shell has to be there before it can be asked for
                // anything. Where it already is, this costs one probe.
                if (installedOrUnknown(http, url) == false) {
                    installAndWait(http, url, poke, timeoutMs = timeoutMs).getOrThrow()
                }
                addDesk(http, url, name).getOrThrow()
                val deadline = io.nisfeb.talon.util.nowMs() + timeoutMs
                while (io.nisfeb.talon.util.nowMs() < deadline) {
                    kotlinx.coroutines.delay(POLL_MS)
                    if (answers(url)) return@runCatching
                }
                error(
                    "$name was asked for and has not answered yet. The shell syncs it over the network, " +
                        "and it asks you to approve what it reaches: open /apps/$name on your ship.",
                )
            }
        }
    }

    /**
     * Whether the Grubbery shell is on [shipUrl]: its stock list answers
     * the owner in JSON, and refuses anyone else with its own 403. Not a
     * status alone: eyre sends a caller without a session from a path
     * nothing serves to its login page, which a client that follows
     * redirects reads as 200. Lattice's manifest is not this either:
     * lattice is one of the shell's stock desks, and a ship can have the
     * shell and not yet the desk.
     */
    suspend fun hasShell(http: HttpClient, shipUrl: String, cookie: String? = null): Boolean = runCatching {
        val resp = http.get("${shipUrl.trimEnd('/')}/apps/grubbery/desks/stock") {
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }
        resp.status.value == 403 ||
            (resp.status.isSuccess() && resp.contentType()?.match(ContentType.Application.Json) == true)
    }.getOrDefault(false)

    /** Ask the shell to fetch its stock desks (lattice, mail, the calendar) from their repositories. Idempotent. */
    suspend fun syncStock(http: HttpClient, shipUrl: String, cookie: String?): Result<Unit> = runCatching {
        val resp = http.post("${shipUrl.trimEnd('/')}/apps/grubbery/desks/sync-defaults") {
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }
        if (!resp.status.isSuccess()) {
            error(
                if (resp.status.value == 401 || resp.status.value == 403) "Your ship did not accept that; sign in to it again."
                else "Grubbery would not fetch its apps (HTTP ${resp.status.value}).",
            )
        }
    }

    /** Whether the shell says every stock desk has been fetched; null where it did not say. */
    suspend fun stockSynced(http: HttpClient, shipUrl: String, cookie: String?): Boolean? = runCatching {
        val resp = http.get("${shipUrl.trimEnd('/')}/apps/grubbery/desks/stock") {
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }
        val arr = (if (resp.status.isSuccess()) Json.parseToJsonElement(resp.bodyAsText()) else null) as? JsonArray
        arr?.all { (it as? JsonObject)?.get("synced")?.jsonPrimitive?.booleanOrNull == true }
    }.getOrNull()

    /**
     * Grubbery and the apps that come with it. Lattice, mail and the
     * calendar are the shell's stock desks, which it fetches from their
     * repositories only when asked, so installing grubbery again on a
     * ship that has it changes nothing: kiln syncs it from its publisher
     * and the apps stay missing, which is what a user saw. So grubbery by
     * kiln only where the shell is not here, then the shell asked to
     * fetch its stock desks, then a wait for [answers].
     *
     * A desk the shell has fetched still answers nothing until the owner
     * approves what it reaches, so where approvals wait that is what this
     * says, rather than waiting out the clock. The shell's own calls are
     * the owner's: [cookie] is the session's.
     */
    fun grubbery(
        http: HttpClient,
        shipUrl: () -> String?,
        cookie: () -> String?,
        answers: suspend (String) -> Boolean = { isInstalled(http, it) },
        timeoutMs: Long = GRUBBERY_TIMEOUT_MS,
        poke: suspend (String, String, JsonElement) -> Boolean,
    ): suspend () -> Result<Unit> = {
        val url = shipUrl()
        if (url == null) {
            Result.failure(IllegalStateException("Not signed in to a ship."))
        } else {
            runCatching {
                if (answers(url)) return@runCatching
                if (!hasShell(http, url, cookie())) {
                    installAndWait(http, url, poke, timeoutMs, installed = { hasShell(http, url, cookie()) }).getOrThrow()
                }
                syncStock(http, url, cookie()).getOrThrow()
                val deadline = io.nisfeb.talon.util.nowMs() + timeoutMs
                while (io.nisfeb.talon.util.nowMs() < deadline) {
                    kotlinx.coroutines.delay(POLL_MS)
                    if (answers(url)) return@runCatching
                    if (stockSynced(http, url, cookie()) == true &&
                        io.nisfeb.talon.ui.fetchPendingPermits(http, url, cookie()).orEmpty().isNotEmpty()
                    ) {
                        error(APPROVE_APPS)
                    }
                }
                error("Grubbery is still fetching its apps from their repositories. Try again in a few minutes.")
            }
        }
    }

    /** What an install ends on while the fetched apps wait for the owner's approval. */
    const val APPROVE_APPS =
        "Grubbery has its apps now. Approve what they reach on your ship's permits page, and they will open."

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
